package de.bwjobs.scan;

import de.bwjobs.common.Sql;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class ScanRunLogRepository {
    private final JdbcClient jdbc;

    public ScanRunLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record RunLog(long id, long companyId, String companyName, String adapter, Instant startedAt, Instant finishedAt,
                         int listed, int accepted, int newJobs, int closedJobs, String error) {}

    public void insert(long companyId, String adapter, Instant started, int listed, int accepted, int newJobs, int closed, String error) {
        jdbc.sql("""
                insert into scan_run_log(company_id, adapter, started_at, finished_at, listed, accepted, new_jobs, closed_jobs, error)
                values (?,?,?,now(),?,?,?,?,?)""")
                .params(companyId, adapter, Sql.ts(started), listed, accepted, newJobs, closed, error).update();
    }

    public List<RunLog> recent(int limit, Long companyId) {
        String where = companyId == null ? "" : " where l.company_id = " + companyId;
        return jdbc.sql("select l.*, c.name from scan_run_log l join company c on c.id = l.company_id" + where
                        + " order by l.started_at desc limit ?")
                .param(limit)
                .query((rs, i) -> new RunLog(rs.getLong("id"), rs.getLong("company_id"), rs.getString("name"), rs.getString("adapter"),
                        Sql.instant(rs, "started_at"), Sql.instant(rs, "finished_at"), rs.getInt("listed"), rs.getInt("accepted"),
                        rs.getInt("new_jobs"), rs.getInt("closed_jobs"), rs.getString("error")))
                .list();
    }
}
