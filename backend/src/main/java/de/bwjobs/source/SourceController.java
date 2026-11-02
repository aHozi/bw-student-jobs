package de.bwjobs.source;

import de.bwjobs.common.ApiException;
import de.bwjobs.common.CurrentUser;
import de.bwjobs.common.Crypto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SourceController {
    private final EmailImportRepository settings;
    private final EmailAlertSource email;
    private final ArbeitsagenturSource ba;
    private final AdzunaSource adzuna;
    private final SourceScheduler scheduler;
    private final SourceRunLog runLog;
    private final Crypto crypto;

    public SourceController(EmailImportRepository settings, EmailAlertSource email, ArbeitsagenturSource ba, AdzunaSource adzuna,
                            SourceScheduler scheduler, SourceRunLog runLog, Crypto crypto) {
        this.settings = settings;
        this.email = email;
        this.ba = ba;
        this.adzuna = adzuna;
        this.scheduler = scheduler;
        this.runLog = runLog;
        this.crypto = crypto;
    }

    // ---------------- per-user job-alert e-mail import ----------------

    /** The password is never returned. */
    public record EmailSettingsView(boolean connected, String host, Integer port, String username, String folder, boolean enabled,
                                    Instant lastSyncAt, String lastError, boolean featureEnabled) {}

    public record EmailSettingsInput(@NotBlank String host, Integer port, @NotBlank String username, String password,
                                     String folder, Boolean enabled) {}

    @GetMapping("/email-import")
    public EmailSettingsView getEmail() {
        return settings.get(CurrentUser.id())
                .map(s -> new EmailSettingsView(true, s.host(), s.port(), s.username(), s.folder(), s.enabled(), s.lastSyncAt(),
                        s.lastError(), email.enabled()))
                .orElse(new EmailSettingsView(false, null, null, null, "INBOX", true, null, null, email.enabled()));
    }

    @PutMapping("/email-import")
    public EmailSettingsView saveEmail(@Valid @RequestBody EmailSettingsInput in) {
        long uid = CurrentUser.id();
        String passwordEnc = resolvePassword(uid, in.password());
        settings.save(uid, in.host().trim(), in.port() == null ? 993 : in.port(), in.username().trim(), passwordEnc,
                in.folder() == null || in.folder().isBlank() ? "INBOX" : in.folder().trim(), in.enabled() == null || in.enabled());
        return getEmail();
    }

    @PostMapping("/email-import/test")
    public EmailAlertSource.TestResult testEmail(@Valid @RequestBody EmailSettingsInput in) {
        String password = crypto.decryptString(resolvePassword(CurrentUser.id(), in.password()));
        return email.test(in.host().trim(), in.port() == null ? 993 : in.port(), in.username().trim(), password,
                in.folder() == null || in.folder().isBlank() ? "INBOX" : in.folder().trim());
    }

    @PostMapping("/email-import/run")
    public EmailAlertSource.SyncResult runEmail() {
        if (!email.enabled()) throw ApiException.badRequest("E-mail import is disabled on this server");
        return email.sync(CurrentUser.id());
    }

    @DeleteMapping("/email-import")
    public void deleteEmail() {
        settings.delete(CurrentUser.id());
    }

    private String resolvePassword(long uid, String password) {
        if (password != null && !password.isBlank()) return crypto.encryptString(password);
        return settings.get(uid).map(EmailImportRepository.Settings::passwordEnc)
                .orElseThrow(() -> ApiException.badRequest("Enter the mailbox password (an app password for Gmail)"));
    }

    // ---------------- admin ----------------

    @GetMapping("/admin/sources")
    public Map<String, Object> sources() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sources", List.of(
                Map.of("name", "ARBEITSAGENTUR", "label", "Bundesagentur für Arbeit", "enabled", ba.enabled()),
                Map.of("name", "ADZUNA", "label", "Adzuna", "enabled", adzuna.enabled(),
                        "hint", adzuna.enabled() ? "" : "Set ADZUNA_APP_ID and ADZUNA_APP_KEY to enable"),
                Map.of("name", "EMAIL_ALERT", "label", "Job-alert e-mails (LinkedIn, StepStone, Indeed)", "enabled", email.enabled())));
        m.put("openJobs", runLog.openJobsBySource());
        m.put("recentRuns", runLog.recent(40));
        return m;
    }

    @PostMapping("/admin/sources/{source}/run")
    public Map<String, Object> run(@PathVariable String source) {
        if (!List.of("ARBEITSAGENTUR", "ADZUNA", "EMAIL_ALERT").contains(source)) throw ApiException.badRequest("Unknown source");
        scheduler.runAsync(source);
        return Map.of("started", true);
    }
}
