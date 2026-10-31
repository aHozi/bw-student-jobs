package de.bwjobs.scan;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** DB-backed task queue. Workers on any number of instances claim tasks with SKIP LOCKED. */
@Repository
public class ScanQueue {
    private final JdbcClient jdbc;

    public ScanQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds a task unless one is already pending/running for this company and kind. */
    public boolean enqueue(long companyId, String kind) {
        return jdbc.sql("""
                insert into scan_task(company_id, kind) values (?, ?)
                on conflict (company_id, kind) where status in ('PENDING','RUNNING') do nothing""")
                .params(companyId, kind).update() > 0;
    }

    public Optional<ScanTask> claimNext(String workerId) {
        return jdbc.sql("""
                update scan_task set status = 'RUNNING', locked_by = ?, locked_at = now(), attempts = attempts + 1
                where id = (select id from scan_task where status = 'PENDING' and run_after <= now()
                            order by run_after, id for update skip locked limit 1)
                returning id, company_id, kind, attempts""")
                .param(workerId)
                .query((rs, i) -> new ScanTask(rs.getLong("id"), rs.getLong("company_id"), rs.getString("kind"), rs.getInt("attempts")))
                .optional();
    }

    public void complete(long id) {
        jdbc.sql("update scan_task set status = 'DONE', finished_at = now(), error = null where id = ?").param(id).update();
    }

    /** Retries up to 3 attempts with growing delay, then marks FAILED. */
    public void fail(long id, int attempts, String error) {
        if (attempts < 3) {
            jdbc.sql("update scan_task set status = 'PENDING', run_after = now() + make_interval(mins => ?), error = ? where id = ?")
                    .params(attempts * 15, error, id).update();
        } else {
            jdbc.sql("update scan_task set status = 'FAILED', finished_at = now(), error = ? where id = ?")
                    .params(error, id).update();
        }
    }

    public int resetStale() {
        return jdbc.sql("update scan_task set status = 'PENDING', locked_by = null where status = 'RUNNING' and locked_at < now() - interval '30 minutes'")
                .update();
    }

    public int purgeOld() {
        return jdbc.sql("delete from scan_task where status in ('DONE','FAILED') and finished_at < now() - interval '7 days'").update();
    }

    public Map<String, Long> stats() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String s : List.of("PENDING", "RUNNING", "DONE", "FAILED")) out.put(s, 0L);
        jdbc.sql("select status, count(*) c from scan_task group by status")
                .query((rs, i) -> Map.entry(rs.getString("status"), rs.getLong("c"))).list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
