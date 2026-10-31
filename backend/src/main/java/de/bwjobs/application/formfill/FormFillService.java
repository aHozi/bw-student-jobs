package de.bwjobs.application.formfill;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import de.bwjobs.application.FillReport;
import de.bwjobs.common.Sql;
import de.bwjobs.scan.BrowserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Fills application forms in a headless browser. prepare() fills and STOPS before submitting;
 * submit() is only called after the user approved, re-fills in a fresh session and clicks submit.
 */
@Service
public class FormFillService {
    private static final Logger log = LoggerFactory.getLogger(FormFillService.class);

    private static final Pattern APPLY_BUTTON = Pattern.compile("(?i)jetzt bewerben|online bewerben|bewerben|apply now|apply for this job|apply|zur bewerbung|bewerbung starten");
    private static final Pattern SUBMIT_TEXT = Pattern.compile("(?i)absenden|abschicken|bewerbung (?:ab)?senden|senden|submit|send application|bewerbung abschließen|jetzt bewerben|apply|bewerben");
    private static final Pattern SUCCESS_TEXT = Pattern.compile("(?i)vielen dank|danke f(?:ü|ue)r ihre bewerbung|danke f(?:ü|ue)r deine bewerbung|thank you|thanks for applying|erfolgreich|successfully|received your application|bewerbung (?:ist )?eingegangen|application (?:has been )?(?:submitted|received)");
    private static final Pattern COOKIE_DECLINE = Pattern.compile("(?i)^(?:nur (?:notwendige|erforderliche)(?: cookies)?|ablehnen|alle ablehnen|reject all|decline|necessary only|only necessary)$");
    private static final Pattern COOKIE_ACCEPT = Pattern.compile("(?i)^(?:alle akzeptieren|akzeptieren|accept all|accept|zustimmen|einverstanden|ok|verstanden)$");

    private final BrowserService browser;

    public FormFillService(BrowserService browser) {
        this.browser = browser;
    }

    public record Result(FillReport report, byte[] screenshot, boolean submitted, boolean confirmed, String error) {}

    public Result prepare(String url, FieldMapper.Material material) {
        return browser.withPage(page -> run(page, url, material, Map.of(), Set.of(), false));
    }

    public Result submit(String url, FieldMapper.Material material, Map<String, String> overrides, Set<String> consents) {
        return browser.withPage(page -> run(page, url, material, overrides, consents, true));
    }

    private Result run(Page page, String url, FieldMapper.Material m, Map<String, String> overrides, Set<String> consents, boolean doSubmit) {
        try {
            page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(45_000));
            settle(page);
            dismissCookies(page);
            if (countFormFields(page) < 2) {
                openApplicationForm(page);
                dismissCookies(page);
            }
            boolean captcha = page.locator("iframe[src*='recaptcha'], iframe[src*='hcaptcha'], iframe[src*='turnstile'], .g-recaptcha, .h-captcha, [data-sitekey]").count() > 0;
            boolean login = page.locator("input[type=password]:visible").count() > 0;

            List<Map<String, Object>> raw = collectFields(page);
            if (raw.isEmpty()) {
                return new Result(new FillReport(page.url(), List.of(), captcha, login, false, 0,
                        "No application form found on the page.", null), screenshot(page), false, false, null);
            }
            int fileInputs = (int) raw.stream().filter(f -> "file".equals(f.get("type"))).count();
            List<FillReport.Field> fields = new ArrayList<>();
            int unresolved = 0;
            for (Map<String, Object> f : raw) {
                FillReport.Field r = fillOne(page, f, fileInputs, m, overrides, consents);
                fields.add(r);
                if (r.required() && (r.status().equals("NEEDS_INPUT") || (r.status().equals("CONSENT") && !consents.contains(r.key())))) {
                    unresolved++;
                }
            }
            Locator submitBtn = findSubmit(page);
            boolean submitFound = submitBtn != null;
            String message = captcha ? "This form uses a CAPTCHA - please submit it yourself."
                    : login ? "This career site requires an account/login - please apply manually."
                    : !submitFound ? "No submit button found."
                    : unresolved > 0 ? unresolved + " required field(s) need your input."
                    : "All required fields are filled.";
            FillReport report = new FillReport(page.url(), fields, captcha, login, submitFound, unresolved, message, null);

            if (!doSubmit) return new Result(report, screenshot(page), false, false, null);

            if (captcha || login || !submitFound) return new Result(report, screenshot(page), false, false, message);
            if (unresolved > 0) return new Result(report, screenshot(page), false, false, "Required fields are still missing: " + unresolved);

            String before = page.url();
            submitBtn.click();
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(20_000));
            } catch (PlaywrightException ignored) {}
            page.waitForTimeout(1500);
            String body = page.locator("body").innerText();
            boolean success = SUCCESS_TEXT.matcher(body).find();
            boolean errors = page.locator("[aria-invalid=true]:visible, .error:visible, .invalid-feedback:visible, .field-error:visible").count() > 0;
            byte[] shot = screenshot(page);
            if (errors && !success) return new Result(report, shot, false, false, "The form reported validation errors after submitting. Check the screenshot.");
            boolean urlChanged = !page.url().equals(before);
            return new Result(report, shot, true, success, success ? null
                    : urlChanged ? "Submitted, but no confirmation text was found. Check the screenshot."
                    : "Clicked submit, but could not confirm success. Check the screenshot and the company's confirmation e-mail.");
        } catch (PlaywrightException e) {
            log.warn("Form fill failed for {}: {}", url, e.getMessage());
            byte[] shot = null;
            try { shot = screenshot(page); } catch (Exception ignored) {}
            return new Result(FillReport.manual(url, "Browser automation failed: " + firstLine(e.getMessage())), shot, false, false,
                    firstLine(e.getMessage()));
        }
    }

    private FillReport.Field fillOne(Page page, Map<String, Object> f, int fileInputs, FieldMapper.Material m,
                                     Map<String, String> overrides, Set<String> consents) {
        String key = (String) f.get("key");
        String label = (String) f.get("label");
        String type = (String) f.get("type");
        boolean required = Boolean.TRUE.equals(f.get("required"));
        boolean multiple = Boolean.TRUE.equals(f.get("multiple"));
        @SuppressWarnings("unchecked") List<String> options = (List<String>) f.getOrDefault("options", List.of());
        Locator el = page.locator("[data-bwjobs-key=\"" + key + "\"]").first();

        String override = overrides.get(key);
        try {
            if (override != null && !override.isBlank()) {
                setValue(el, type, override);
                return new FillReport.Field(key, label, type, required, "FILLED", override, options);
            }
            FieldMapper.Mapped mapped = FieldMapper.map(label, (String) f.get("name"), type, multiple, fileInputs, m);
            if (mapped == null) {
                return new FillReport.Field(key, label, type, required, required ? "NEEDS_INPUT" : "SKIPPED", null, options);
            }
            switch (mapped.kind()) {
                case TEXT -> {
                    setValue(el, type, mapped.value());
                    return new FillReport.Field(key, label, type, required, "FILLED", preview(mapped.value()), options);
                }
                case FILE -> {
                    el.setInputFiles(mapped.files().toArray(Path[]::new));
                    return new FillReport.Field(key, label, type, required, "FILE",
                            String.join(", ", mapped.files().stream().map(p -> p.getFileName().toString()).toList()), options);
                }
                case CONSENT -> {
                    if (consents.contains(key)) {
                        el.check();
                        return new FillReport.Field(key, label, type, required, "FILLED", "accepted by you", options);
                    }
                    return new FillReport.Field(key, label, type, required, "CONSENT", null, options);
                }
                case NEEDS_INPUT -> {
                    return new FillReport.Field(key, label, type, required, required ? "NEEDS_INPUT" : "SKIPPED", null, options);
                }
                default -> {
                    return new FillReport.Field(key, label, type, required, required ? "NEEDS_INPUT" : "SKIPPED", null, options);
                }
            }
        } catch (PlaywrightException e) {
            return new FillReport.Field(key, label, type, required, required ? "NEEDS_INPUT" : "SKIPPED", null, options);
        }
    }

    private static void setValue(Locator el, String type, String value) {
        switch (type) {
            case "select-one", "select-multiple" -> {
                try {
                    el.selectOption(new com.microsoft.playwright.options.SelectOption().setLabel(value));
                } catch (PlaywrightException e) {
                    el.selectOption(value);
                }
            }
            case "checkbox" -> {
                if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("ja")) el.check();
            }
            case "radio" -> el.page().locator("input[type=radio][name=\"" + el.getAttribute("name") + "\"][value=\"" + value.replace("\"", "") + "\"]").check();
            default -> el.fill(value);
        }
    }

    /** Marks every visible form field with a stable key and returns its metadata. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> collectFields(Page page) {
        Object res = page.evaluate("""
                () => {
                  const out = []; const seenRadio = new Set(); let idx = 0;
                  const els = document.querySelectorAll('input, textarea, select');
                  const labelOf = (el) => {
                    if (el.id) { const l = document.querySelector('label[for="' + CSS.escape(el.id) + '"]'); if (l && l.innerText.trim()) return l.innerText.trim(); }
                    const wrap = el.closest('label'); if (wrap && wrap.innerText.trim()) return wrap.innerText.trim();
                    if (el.getAttribute('aria-label')) return el.getAttribute('aria-label');
                    const lb = el.getAttribute('aria-labelledby');
                    if (lb) { const t = lb.split(' ').map(i => document.getElementById(i)).filter(Boolean).map(e => e.innerText).join(' '); if (t.trim()) return t.trim(); }
                    if (el.placeholder) return el.placeholder;
                    const group = el.closest('fieldset, .form-group, .field, [class*=field], [class*=form-row], div');
                    if (group) { const l = group.querySelector('label, legend, .label'); if (l && l.innerText.trim()) return l.innerText.trim(); }
                    return el.name || el.id || '';
                  };
                  for (const el of els) {
                    const type = (el.tagName === 'SELECT') ? el.type : (el.tagName === 'TEXTAREA' ? 'textarea' : (el.type || 'text'));
                    if (['hidden', 'submit', 'button', 'image', 'reset'].includes(type)) continue;
                    const style = window.getComputedStyle(el);
                    const visible = el.offsetParent !== null || type === 'file' || type === 'checkbox';
                    if (!visible || style.display === 'none') continue;
                    if (type === 'radio') { if (seenRadio.has(el.name)) continue; seenRadio.add(el.name); }
                    const key = (el.name ? 'n:' + el.name : (el.id ? 'i:' + el.id : 'x:' + idx));
                    idx++;
                    el.setAttribute('data-bwjobs-key', key);
                    let options = [];
                    if (el.tagName === 'SELECT') options = Array.from(el.options).map(o => o.text.trim()).filter(t => t);
                    if (type === 'radio') options = Array.from(document.querySelectorAll('input[type=radio][name="' + CSS.escape(el.name) + '"]')).map(r => r.value);
                    const label = (labelOf(el) || '').replace(/\\s+/g, ' ').slice(0, 200);
                    const required = el.required || el.getAttribute('aria-required') === 'true' || /\\*\\s*$/.test(label);
                    out.push({ key, label, type, name: el.name || '', required, multiple: !!el.multiple, options });
                  }
                  return out;
                }""");
        return res == null ? List.of() : (List<Map<String, Object>>) res;
    }

    private static int countFormFields(Page page) {
        return page.locator("form input:visible:not([type=hidden]):not([type=search]), form textarea:visible, input[type=file]").count();
    }

    private static void openApplicationForm(Page page) {
        Locator candidates = page.locator("a, button");
        int n = Math.min(candidates.count(), 300);
        for (int i = 0; i < n; i++) {
            Locator c = candidates.nth(i);
            try {
                if (!c.isVisible()) continue;
                String text = c.innerText().trim();
                if (text.length() > 40 || !APPLY_BUTTON.matcher(text).find()) continue;
                String href = c.getAttribute("href");
                if (href != null && !href.isBlank() && !href.startsWith("#") && !href.startsWith("javascript") && !href.startsWith("mailto")) {
                    page.navigate(new java.net.URI(page.url()).resolve(href).toString());
                } else {
                    c.click();
                }
                settle(page);
                return;
            } catch (Exception ignored) {
                // try next candidate
            }
        }
    }

    private static Locator findSubmit(Page page) {
        Locator typed = page.locator("form button[type=submit]:visible, form input[type=submit]:visible");
        if (typed.count() > 0) return typed.last();
        Locator buttons = page.locator("button:visible, input[type=button]:visible, a[role=button]:visible");
        for (int i = buttons.count() - 1; i >= 0; i--) {
            Locator b = buttons.nth(i);
            String t;
            try {
                t = b.innerText().trim();
                if (t.isEmpty()) t = Optional.ofNullable(b.getAttribute("value")).orElse("");
            } catch (PlaywrightException e) {
                continue;
            }
            if (t.length() < 40 && SUBMIT_TEXT.matcher(t).find()) return b;
        }
        return null;
    }

    private static void dismissCookies(Page page) {
        for (Pattern p : List.of(COOKIE_DECLINE, COOKIE_ACCEPT)) {
            Locator btns = page.locator("button:visible, a[role=button]:visible");
            int n = Math.min(btns.count(), 60);
            for (int i = 0; i < n; i++) {
                try {
                    String t = btns.nth(i).innerText().trim();
                    if (p.matcher(t).matches()) {
                        btns.nth(i).click(new Locator.ClickOptions().setTimeout(3000));
                        page.waitForTimeout(500);
                        return;
                    }
                } catch (PlaywrightException ignored) {}
            }
        }
    }

    private static void settle(Page page) {
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(10_000));
        } catch (PlaywrightException ignored) {}
    }

    private static byte[] screenshot(Page page) {
        return page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
    }

    private static String preview(String v) {
        return v.length() > 80 ? v.substring(0, 77) + "..." : v;
    }

    private static String firstLine(String s) {
        if (s == null) return "unknown error";
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }

    static String json(Object o) {
        return Sql.toJson(o);
    }
}
