package de.bwjobs.scan;

import de.bwjobs.common.AppProperties;
import de.bwjobs.common.Text;
import de.bwjobs.company.*;
import de.bwjobs.job.JobIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Scans one company: discover career page if needed → adapter list → ingest. */
@Service
public class ScanService {
    private static final Logger log = LoggerFactory.getLogger(ScanService.class);
    private static final int BROKEN_AFTER_FAILURES = 3;

    private final CompanyRepository companies;
    private final CareerPageDiscovery discovery;
    private final AdapterRegistry adapters;
    private final JobIngestService ingest;
    private final ScanRunLogRepository runLogs;
    private final AppProperties.Scan cfg;

    public ScanService(CompanyRepository companies, CareerPageDiscovery discovery, AdapterRegistry adapters,
                       JobIngestService ingest, ScanRunLogRepository runLogs, AppProperties props) {
        this.companies = companies;
        this.discovery = discovery;
        this.adapters = adapters;
        this.ingest = ingest;
        this.runLogs = runLogs;
        this.cfg = props.scan();
    }

    public record Outcome(boolean ok, String message, JobIngestService.Stats stats) {}

    /** Runs discovery and stores the result. Returns the updated company. */
    public Company discover(Company c) {
        CareerPageDiscovery.Result r = discovery.discover(c.website(), c.careerUrl());
        if (!r.found()) {
            companies.updateAts(c.id(), AtsType.UNKNOWN, null, null, "NEEDS_REVIEW", r.message());
            return companies.findById(c.id()).orElseThrow();
        }
        companies.updateAts(c.id(), r.atsType(), r.tenant(), r.careerUrl(), c.status().equals("BROKEN") ? "ACTIVE" : c.status(), r.message());
        return companies.findById(c.id()).orElseThrow();
    }

    public Outcome scan(long companyId) {
        Company c = companies.findById(companyId).orElseThrow();
        Instant started = Instant.now();
        String adapterName = c.atsType().name();
        try {
            if (c.atsType() == AtsType.UNKNOWN || (c.atsType() == AtsType.GENERIC && c.careerUrl() == null)) {
                c = discover(c);
                adapterName = c.atsType().name();
                if (c.atsType() == AtsType.UNKNOWN) {
                    companies.markScanFailure(c.id(), "Career page not found: " + c.statusMessage(), BROKEN_AFTER_FAILURES);
                    runLogs.insert(c.id(), "DISCOVERY", started, 0, 0, 0, 0, c.statusMessage());
                    return new Outcome(false, c.statusMessage(), null);
                }
            }
            Company company = c;
            JobSourceAdapter adapter = adapters.forType(c.atsType())
                    .orElseThrow(() -> new IllegalStateException("No adapter for " + company.atsType()));
            List<RawJob> raw = adapter.list(c);
            JobIngestService.Stats stats = ingest.ingest(c, adapter, raw, started);
            companies.markScanSuccess(c.id(), nextInterval(c.scanIntervalHours(), stats.newJobs()));
            runLogs.insert(c.id(), adapterName, started, stats.listed(), stats.accepted(), stats.newJobs(), stats.closed(), null);
            log.info("Scanned {} via {}: listed={} student={} accepted={} new={} closed={}", c.name(), adapterName,
                    stats.listed(), stats.studentLike(), stats.accepted(), stats.newJobs(), stats.closed());
            return new Outcome(true, "ok", stats);
        } catch (Exception e) {
            String msg = Text.truncate(e.getClass().getSimpleName() + ": " + e.getMessage(), 500);
            log.warn("Scan failed for {}: {}", c.name(), msg);
            if (e instanceof FetchException fe && (fe.status() == 404 || fe.status() == 410) && c.atsType() != AtsType.GENERIC) {
                // ATS tenant moved or wrong: rediscover on the next run
                companies.updateAts(c.id(), AtsType.UNKNOWN, null, null, c.status(), "ATS endpoint gone (" + fe.status() + "), will rediscover");
            }
            companies.markScanFailure(c.id(), msg, BROKEN_AFTER_FAILURES);
            runLogs.insert(c.id(), adapterName, started, 0, 0, 0, 0, msg);
            return new Outcome(false, msg, null);
        }
    }

    /** Adaptive schedule: companies with new postings get scanned more often. */
    int nextInterval(int current, int newJobs) {
        int next = newJobs > 0 ? (int) Math.round(current * 0.75) : (int) Math.round(current * 1.25);
        return Math.max(cfg.minIntervalHours(), Math.min(cfg.maxIntervalHours(), next));
    }
}
