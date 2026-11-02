package de.bwjobs.source;

import de.bwjobs.common.Text;
import de.bwjobs.job.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Runs the job sources on a schedule. A Postgres advisory lock held for the whole run makes sure only one
 * backend instance runs a source at a time.
 */
@Component
public class SourceScheduler {
    private static final Logger log = LoggerFactory.getLogger(SourceScheduler.class);
    private static final long LOCK_BA = 4_242_101L, LOCK_ADZUNA = 4_242_102L, LOCK_EMAIL = 4_242_103L;

    private final ArbeitsagenturSource ba;
    private final AdzunaSource adzuna;
    private final EmailAlertSource email;
    private final EmailImportRepository emailSettings;
    private final ExternalJobIngest ingest;
    private final SourceRunLog runLog;
    private final JobRepository jobs;
    private final JdbcTemplate jdbc;

    public SourceScheduler(ArbeitsagenturSource ba, AdzunaSource adzuna, EmailAlertSource email, EmailImportRepository emailSettings,
                           ExternalJobIngest ingest, SourceRunLog runLog, JobRepository jobs, JdbcTemplate jdbc) {
        this.ba = ba;
        this.adzuna = adzuna;
        this.email = email;
        this.emailSettings = emailSettings;
        this.ingest = ingest;
        this.runLog = runLog;
        this.jobs = jobs;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT1M")
    public void scheduledApis() {
        runArbeitsagentur();
        runAdzuna();
    }

    @Scheduled(fixedDelayString = "PT${app.sources.email.poll-minutes:30}M", initialDelayString = "PT90S")
    public void scheduledEmail() {
        if (!email.enabled()) return;
        withLock(LOCK_EMAIL, () -> {
            for (EmailImportRepository.Settings s : emailSettings.enabled()) {
                try {
                    email.sync(s.userId());
                } catch (Exception e) {
                    log.debug("E-mail sync failed for user {}: {}", s.userId(), e.getMessage());
                }
            }
            return null;
        });
    }

    @Scheduled(cron = "0 30 4 * * *")
    public void closeStale() {
        int n = jobs.closeStaleExternal();
        if (n > 0) log.info("Closed {} stale external jobs", n);
    }

    public ExternalJobIngest.Stats runArbeitsagentur() {
        if (!ba.enabled()) return null;
        return withLock(LOCK_BA, () -> runApi("ARBEITSAGENTUR", ba::fetch));
    }

    public ExternalJobIngest.Stats runAdzuna() {
        if (!adzuna.enabled()) return null;
        return withLock(LOCK_ADZUNA, () -> runApi("ADZUNA", adzuna::fetch));
    }

    @Async("applicationExecutor")
    public void runAsync(String source) {
        switch (source) {
            case "ARBEITSAGENTUR" -> runArbeitsagentur();
            case "ADZUNA" -> runAdzuna();
            case "EMAIL_ALERT" -> scheduledEmail();
            default -> throw new IllegalArgumentException("Unknown source " + source);
        }
    }

    private ExternalJobIngest.Stats runApi(String name, Supplier<List<ExternalJob>> fetch) {
        Instant started = Instant.now();
        try {
            List<ExternalJob> list = fetch.get();
            ExternalJobIngest.Stats s = ingest.ingest(list);
            runLog.insert(name, null, started, s.fetched(), s.accepted(), s.newJobs(), null);
            log.info("Source {}: fetched={} accepted={} new={}", name, s.fetched(), s.accepted(), s.newJobs());
            return s;
        } catch (Exception e) {
            String msg = Text.truncate(e.getClass().getSimpleName() + ": " + e.getMessage(), 500);
            log.warn("Source {} failed: {}", name, msg);
            runLog.insert(name, null, started, 0, 0, 0, msg);
            return null;
        }
    }

    /** Runs the action while holding a session-level advisory lock on one dedicated connection. */
    private <T> T withLock(long key, Supplier<T> action) {
        return jdbc.execute((java.sql.Connection c) -> {
            boolean locked;
            try (PreparedStatement ps = c.prepareStatement("select pg_try_advisory_lock(?)")) {
                ps.setLong(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    locked = rs.getBoolean(1);
                }
            }
            if (!locked) {
                log.info("Source run {} skipped: already running elsewhere", key);
                return null;
            }
            try {
                return action.get();
            } finally {
                try (PreparedStatement ps = c.prepareStatement("select pg_advisory_unlock(?)")) {
                    ps.setLong(1, key);
                    ps.execute();
                }
            }
        });
    }
}
