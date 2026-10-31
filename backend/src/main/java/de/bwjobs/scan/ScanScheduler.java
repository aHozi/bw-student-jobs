package de.bwjobs.scan;

import de.bwjobs.common.AppProperties;
import de.bwjobs.company.Company;
import de.bwjobs.company.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Creates scan tasks for companies that are due (one instance at a time via a Postgres advisory lock)
 * and runs worker threads that process the queue. Scale out by running more instances.
 */
@Component
public class ScanScheduler {
    private static final Logger log = LoggerFactory.getLogger(ScanScheduler.class);
    private static final long LOCK_KEY = 4_242_001L;

    private final CompanyRepository companies;
    private final ScanQueue queue;
    private final ScanService scanService;
    private final JdbcClient jdbc;
    private final AppProperties.Scan cfg;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running = true;

    public ScanScheduler(CompanyRepository companies, ScanQueue queue, ScanService scanService, JdbcClient jdbc, AppProperties props) {
        this.companies = companies;
        this.queue = queue;
        this.scanService = scanService;
        this.jdbc = jdbc;
        this.cfg = props.scan();
    }

    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT20S")
    @Transactional
    public void enqueueDue() {
        if (!cfg.enabled()) return;
        Boolean locked = jdbc.sql("select pg_try_advisory_xact_lock(?)").param(LOCK_KEY).query(Boolean.class).single();
        if (!Boolean.TRUE.equals(locked)) return;
        queue.resetStale();
        int n = 0;
        for (Company c : companies.findDueForScan(Instant.now(), 200)) {
            if (queue.enqueue(c.id(), "SCAN")) n++;
        }
        if (n > 0) log.info("Enqueued {} scan tasks", n);
    }

    @Scheduled(cron = "0 15 3 * * *")
    public void cleanup() {
        queue.purgeOld();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWorkers() {
        if (!cfg.enabled()) {
            log.info("Scanning disabled (app.scan.enabled=false)");
            return;
        }
        String instance = ManagementFactory.getRuntimeMXBean().getName();
        for (int i = 0; i < Math.max(1, cfg.workers()); i++) {
            String id = instance + "-w" + i;
            Thread t = Thread.ofPlatform().daemon(true).name("scan-" + i).start(() -> workLoop(id));
            workers.add(t);
        }
        log.info("Started {} scan workers", workers.size());
    }

    private void workLoop(String workerId) {
        while (running) {
            try {
                Optional<ScanTask> task = queue.claimNext(workerId);
                if (task.isEmpty()) {
                    Thread.sleep(5000);
                    continue;
                }
                process(task.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Worker {} error", workerId, e);
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    void process(ScanTask t) {
        try {
            if ("DISCOVER".equals(t.kind())) {
                companies.findById(t.companyId()).ifPresent(scanService::discover);
            } else {
                scanService.scan(t.companyId());
            }
            queue.complete(t.id());
        } catch (Exception e) {
            queue.fail(t.id(), t.attempts(), e.getMessage());
        }
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        running = false;
        workers.forEach(Thread::interrupt);
    }
}
