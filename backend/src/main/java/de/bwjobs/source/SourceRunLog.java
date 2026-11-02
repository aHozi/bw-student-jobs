package de.bwjobs.source;

import de.bwjobs.common.Sql;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class SourceRunLog {
    private final JdbcClient jdbc;

    public SourceRunLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Run(long id, String source, Long userId, Instant startedAt, Instant finishedAt, int fetched, int accepted,
                      int newJobs, String error) {}

    public void insert(String source, Long userId, Instant started, int fetched, int accepted, int newJobs, String error) {
        jdbc.sql("insert into source_run_log(source, user_id, started_at, fetched, accepted, new_jobs, error) values (?,?,?,?,?,?,?)")
                .params(source, userId, Sql.ts(started), fetched, accepted, newJobs, error).update();
    }

    public List<Run> recent(int limit) {
        return jdbc.sql("select * from source_run_log order by started_at desc limit ?").param(limit)
                .query((rs, i) -> new Run(rs.getLong("id"), rs.getString("source"), Sql.longOrNull(rs, "user_id"),
                        Sql.instant(rs, "started_at"), Sql.instant(rs, "finished_at"), rs.getInt("fetched"), rs.getInt("accepted"),
                        rs.getInt("new_jobs"), rs.getString("error")))
                .list();
    }

    /** Open jobs per source, counting a job once per source it came from. */
    public Map<String, Long> openJobsBySource() {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        jdbc.sql("select s, count(*) c from job_posting, unnest(sources) s where status = 'OPEN' group by s order by s")
                .query((rs, i) -> Map.entry(rs.getString("s"), rs.getLong("c"))).list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
