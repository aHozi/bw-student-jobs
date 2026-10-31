package de.bwjobs.scan;

import com.microsoft.playwright.*;
import de.bwjobs.common.AppProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * Headless Chromium via Playwright. Used to render JavaScript-heavy career pages and to fill
 * application forms. Each call gets its own Playwright instance (Playwright is not thread-safe).
 */
@Component
public class BrowserService {
    private final AppProperties.Browser cfg;
    private final String userAgent;

    public BrowserService(AppProperties props) {
        this.cfg = props.browser();
        this.userAgent = props.scan().userAgent();
    }

    public <T> T withPage(Function<Page, T> action) {
        try (Playwright pw = Playwright.create()) {
            BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions().setHeadless(cfg == null || cfg.headless());
            if (cfg != null && cfg.executablePath() != null && !cfg.executablePath().isBlank()) {
                opts.setExecutablePath(Path.of(cfg.executablePath()));
            }
            try (Browser browser = pw.chromium().launch(opts);
                 BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                         .setUserAgent(userAgent).setLocale("de-DE").setViewportSize(1366, 900))) {
                Page page = ctx.newPage();
                page.setDefaultTimeout(30_000);
                return action.apply(page);
            }
        }
    }

    /** Loads a page, waits for network to settle, returns the rendered HTML. */
    public String renderHtml(String url) {
        return withPage(page -> {
            page.navigate(url, new Page.NavigateOptions().setTimeout(45_000));
            try {
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(15_000));
            } catch (PlaywrightException ignored) {
                // some pages never become idle; use what we have
            }
            return page.content();
        });
    }
}
