package de.bwjobs.application;

import com.fasterxml.jackson.core.type.TypeReference;
import de.bwjobs.common.Sql;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ApplicationRepository {
    private final JdbcClient jdbc;

    static final RowMapper<Application> MAPPER = (rs, i) -> new Application(
            rs.getLong("id"), rs.getLong("user_id"), rs.getLong("job_id"), rs.getLong("company_id"), rs.getString("status"),
            rs.getString("mode"), Sql.longOrNull(rs, "cv_document_id"), Sql.longOrNull(rs, "cover_letter_id"),
            Sql.longArray(rs, "extra_document_ids"), rs.getBoolean("used_original_docs"),
            Sql.readJson(rs.getString("fill_report"), FillReport.class),
            Sql.readJson(rs.getString("overrides"), new TypeReference<Map<String, String>>() {}),
            Sql.readJson(rs.getString("consents"), new TypeReference<List<String>>() {}),
            rs.getString("screenshot_key"), rs.getString("confirmation_key"), rs.getString("manual_reason"),
            rs.getString("error_message"), rs.getString("notes"),
            rs.getDate("follow_up_on") == null ? null : rs.getDate("follow_up_on").toLocalDate(),
            Sql.instant(rs, "approved_at"), Sql.instant(rs, "submitted_at"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"));

    public ApplicationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Application insert(long userId, long jobId, long companyId, Long cv, Long cl, List<Long> extras, boolean original, String mode) {
        long id = jdbc.sql("""
                insert into application(user_id, job_id, company_id, cv_document_id, cover_letter_id, extra_document_ids,
                  used_original_docs, mode) values (?,?,?,?,?,?::bigint[],?,?) returning id""")
                .params(userId, jobId, companyId, cv, cl, Sql.arrayLiteral(extras), original, mode).query(Long.class).single();
        return findById(id).orElseThrow();
    }

    public Optional<Application> findById(long id) {
        return jdbc.sql("select * from application where id = ?").param(id).query(MAPPER).optional();
    }

    public Optional<Application> findOwned(long userId, long id) {
        return jdbc.sql("select * from application where id = ? and user_id = ?").params(id, userId).query(MAPPER).optional();
    }

    public Optional<Application> findByUserAndJob(long userId, long jobId) {
        return jdbc.sql("select * from application where user_id = ? and job_id = ?").params(userId, jobId).query(MAPPER).optional();
    }

    public record Row(Application application, String jobTitle, String jobUrl, String companyName, String jobStatus) {}

    public List<Row> listByUser(long userId) {
        return jdbc.sql("""
                select a.*, j.title as job_title, j.url as job_url, j.status as job_status, c.name as company_name
                from application a join job_posting j on j.id = a.job_id join company c on c.id = a.company_id
                where a.user_id = ? order by a.updated_at desc""")
                .param(userId)
                .query((rs, i) -> new Row(MAPPER.mapRow(rs, i), rs.getString("job_title"), rs.getString("job_url"),
                        rs.getString("company_name"), rs.getString("job_status")))
                .list();
    }

    public record Similar(long id, String title, String status) {}

    public List<Similar> appliedAtCompany(long userId, long companyId, long excludeJobId) {
        return jdbc.sql("""
                select a.id, j.title, a.status from application a join job_posting j on j.id = a.job_id
                where a.user_id = ? and a.company_id = ? and a.job_id <> ?
                  and a.status in ('APPROVED','SUBMITTING','SUBMITTED','INTERVIEW','OFFER','REJECTED')
                  and a.created_at > now() - interval '180 days'""")
                .params(userId, companyId, excludeJobId)
                .query((rs, i) -> new Similar(rs.getLong("id"), rs.getString("title"), rs.getString("status"))).list();
    }

    public void setStatus(long id, String status) {
        jdbc.sql("update application set status = ?, updated_at = now() where id = ?").params(status, id).update();
    }

    /** Atomic transition; returns false if the application was not in the expected status. */
    public boolean transition(long id, String from, String to) {
        return jdbc.sql("update application set status = ?, updated_at = now() where id = ? and status = ?")
                .params(to, id, from).update() == 1;
    }

    public void saveFillResult(long id, String status, String mode, FillReport report, String screenshotKey, String manualReason) {
        jdbc.sql("""
                update application set status = ?, mode = ?, fill_report = ?, screenshot_key = coalesce(?, screenshot_key),
                  manual_reason = ?, error_message = null, updated_at = now() where id = ?""")
                .params(status, mode, Sql.jsonb(report), screenshotKey, manualReason, id).update();
    }

    public void approve(long id, Map<String, String> overrides, List<String> consents) {
        jdbc.sql("""
                update application set status = 'APPROVED', overrides = ?, consents = ?, approved_at = now(), updated_at = now()
                where id = ? and status = 'READY_FOR_REVIEW'""")
                .params(Sql.jsonb(overrides), Sql.jsonb(consents), id).update();
    }

    public void markSubmitted(long id, String confirmationKey, String note) {
        jdbc.sql("""
                update application set status = 'SUBMITTED', submitted_at = now(), confirmation_key = coalesce(?, confirmation_key),
                  error_message = ?, updated_at = now() where id = ?""")
                .params(confirmationKey, note, id).update();
    }

    public void markFailed(long id, String error, String screenshotKey) {
        jdbc.sql("""
                update application set status = 'FAILED', error_message = ?, confirmation_key = coalesce(?, confirmation_key),
                  updated_at = now() where id = ?""")
                .params(error, screenshotKey, id).update();
    }

    public void updateTracking(long id, String status, String notes, LocalDate followUp) {
        jdbc.sql("""
                update application set status = coalesce(?, status), notes = ?, follow_up_on = ?,
                  submitted_at = case when ? = 'SUBMITTED' and submitted_at is null then now() else submitted_at end,
                  updated_at = now() where id = ?""")
                .params(status, notes, followUp == null ? null : java.sql.Date.valueOf(followUp), status, id).update();
    }

    public void updateDocuments(long id, Long cv, Long cl, List<Long> extras, boolean original) {
        jdbc.sql("""
                update application set cv_document_id = ?, cover_letter_id = ?, extra_document_ids = ?::bigint[],
                  used_original_docs = ?, updated_at = now() where id = ?""")
                .params(cv, cl, Sql.arrayLiteral(extras), original, id).update();
    }

    public void delete(long id) {
        jdbc.sql("delete from application where id = ?").param(id).update();
    }
}
