package de.bwjobs.company;

import de.bwjobs.common.Sql;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class CompanyRepository {
    private final JdbcClient jdbc;

    static final RowMapper<Company> MAPPER = (rs, i) -> new Company(
            rs.getLong("id"), rs.getString("name"), rs.getString("website"), rs.getString("career_url"),
            rs.getString("hq_city"), rs.getString("industry"), rs.getString("size"),
            AtsType.valueOf(rs.getString("ats_type")), rs.getString("ats_tenant"), rs.getString("status"),
            rs.getString("status_message"), rs.getString("source"), rs.getInt("scan_interval_hours"),
            rs.getInt("consecutive_failures"), Sql.instant(rs, "last_scanned_at"), Sql.instant(rs, "next_scan_at"),
            Sql.instant(rs, "created_at"));

    public CompanyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Company> findById(long id) {
        return jdbc.sql("select * from company where id = ?").param(id).query(MAPPER).optional();
    }

    public Optional<Company> findByName(String name) {
        return jdbc.sql("select * from company where lower(name) = lower(?)").param(name).query(MAPPER).optional();
    }

    public long count() {
        return jdbc.sql("select count(*) from company").query(Long.class).single();
    }

    public record CompanyRow(Company company, long openJobs) {}

    public List<CompanyRow> search(String q, String status, String city, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                select c.*, (select count(*) from job_posting j where j.company_id = c.id and j.status = 'OPEN') as open_jobs
                from company c where 1=1""");
        Map<String, Object> params = new java.util.HashMap<>();
        if (q != null && !q.isBlank()) {
            sql.append(" and (c.name ilike :q or c.industry ilike :q or c.hq_city ilike :q)");
            params.put("q", "%" + q.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and c.status = :status");
            params.put("status", status);
        }
        if (city != null && !city.isBlank()) {
            sql.append(" and c.hq_city ilike :city");
            params.put("city", "%" + city.trim() + "%");
        }
        sql.append(" order by open_jobs desc, c.name limit :limit offset :offset");
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql(sql.toString()).params(params)
                .query((rs, i) -> new CompanyRow(MAPPER.mapRow(rs, i), rs.getLong("open_jobs"))).list();
    }

    public long countSearch(String q, String status, String city) {
        StringBuilder sql = new StringBuilder("select count(*) from company c where 1=1");
        Map<String, Object> params = new java.util.HashMap<>();
        if (q != null && !q.isBlank()) {
            sql.append(" and (c.name ilike :q or c.industry ilike :q or c.hq_city ilike :q)");
            params.put("q", "%" + q.trim() + "%");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and c.status = :status");
            params.put("status", status);
        }
        if (city != null && !city.isBlank()) {
            sql.append(" and c.hq_city ilike :city");
            params.put("city", "%" + city.trim() + "%");
        }
        return jdbc.sql(sql.toString()).params(params).query(Long.class).single();
    }

    public Company insert(String name, String website, String careerUrl, String city, String industry, String size,
                          AtsType ats, String tenant, String status, String source, int intervalHours) {
        long id = jdbc.sql("""
                insert into company(name, website, career_url, hq_city, industry, size, ats_type, ats_tenant, status, source, scan_interval_hours)
                values (?,?,?,?,?,?,?,?,?,?,?) returning id""")
                .params(name, website, careerUrl, city, industry, size, ats.name(), tenant, status, source, intervalHours)
                .query(Long.class).single();
        return findById(id).orElseThrow();
    }

    public void updateBasics(long id, String name, String website, String careerUrl, String city, String industry,
                             String size, AtsType ats, String tenant, String status) {
        jdbc.sql("""
                update company set name=?, website=?, career_url=?, hq_city=?, industry=?, size=?, ats_type=?, ats_tenant=?,
                  status=?, updated_at=now() where id=?""")
                .params(name, website, careerUrl, city, industry, size, ats.name(), tenant, status, id).update();
    }

    public void updateAts(long id, AtsType ats, String tenant, String careerUrl, String status, String message) {
        jdbc.sql("""
                update company set ats_type=?, ats_tenant=?, career_url=coalesce(?, career_url), status=?, status_message=?,
                  updated_at=now() where id=?""")
                .params(ats.name(), tenant, careerUrl, status, message, id).update();
    }

    public void markScanSuccess(long id, int nextIntervalHours) {
        jdbc.sql("""
                update company set last_scanned_at=now(), next_scan_at=now() + make_interval(hours => ?),
                  scan_interval_hours=?, consecutive_failures=0,
                  status=case when status in ('BROKEN','NEEDS_REVIEW') then 'ACTIVE' else status end,
                  status_message=null, updated_at=now() where id=?""")
                .params(nextIntervalHours, nextIntervalHours, id).update();
    }

    public void markScanFailure(long id, String message, int brokenAfter) {
        jdbc.sql("""
                update company set last_scanned_at=now(), consecutive_failures=consecutive_failures+1,
                  next_scan_at=now() + make_interval(hours => least(48, 6 * (consecutive_failures + 1))),
                  status=case when consecutive_failures + 1 >= ? then 'BROKEN' else status end,
                  status_message=?, updated_at=now() where id=?""")
                .params(brokenAfter, message, id).update();
    }

    public List<Company> findDueForScan(Instant now, int limit) {
        return jdbc.sql("""
                select c.* from company c
                where c.scan_enabled and c.status in ('ACTIVE','NEEDS_REVIEW','BROKEN') and c.next_scan_at <= ?
                  and not exists (select 1 from scan_task t where t.company_id = c.id and t.status in ('PENDING','RUNNING'))
                order by c.next_scan_at limit ?""")
                .params(Sql.ts(now), limit).query(MAPPER).list();
    }

    public List<Company> findByStatus(String status) {
        return jdbc.sql("select * from company where status = ? order by name").param(status).query(MAPPER).list();
    }

    public Optional<Company> findByNameKey(String key) {
        return jdbc.sql("select * from company where name_key = ?").param(key).query(MAPPER).optional();
    }

    /** Company created automatically from an external job (not scanned, no career page needed). */
    public Company insertAuto(String name, String nameKey, String city) {
        long id = jdbc.sql("""
                insert into company(name, name_key, hq_city, status, source, scan_enabled) values (?,?,?,'ACTIVE','AUTO',false)
                returning id""").params(name, nameKey, city).query(Long.class).single();
        return findById(id).orElseThrow();
    }

    public List<Map.Entry<Long, String>> withoutNameKey() {
        return jdbc.sql("select id, name from company where name_key is null")
                .query((rs, i) -> Map.entry(rs.getLong("id"), rs.getString("name"))).list();
    }

    /** Sets the key unless another company already owns it (then the row keeps a null key). */
    public void setNameKey(long id, String key) {
        jdbc.sql("""
                update company set name_key = ? where id = ?
                  and not exists (select 1 from company o where o.name_key = ? and o.id <> ?)""")
                .params(key, id, key, id).update();
    }

    public void delete(long id) {
        jdbc.sql("delete from company where id = ?").param(id).update();
    }
}
