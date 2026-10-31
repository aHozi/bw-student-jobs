package de.bwjobs.job;

import de.bwjobs.common.Sql;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JobRepository {
    private final JdbcClient jdbc;

    public static final RowMapper<JobPosting> MAPPER = (rs, i) -> new JobPosting(
            rs.getLong("id"), rs.getLong("company_id"), rs.getString("external_id"), rs.getString("url"),
            rs.getString("apply_url"), rs.getString("title"), rs.getString("description_html"), rs.getString("description_text"),
            Sql.textArray(rs, "locations"), rs.getBoolean("remote"), Sql.boolOrNull(rs, "in_bw"),
            Sql.textArray(rs, "student_types"), Sql.textArray(rs, "it_fields"), Sql.textArray(rs, "tech_terms"),
            rs.getString("german_level"), rs.getString("english_level"), rs.getString("posting_language"),
            rs.getString("language_source"), rs.getString("contact_email"), Sql.instant(rs, "posted_at"),
            Sql.instant(rs, "first_seen_at"), Sql.instant(rs, "last_seen_at"), rs.getString("status"), rs.getString("fingerprint"));

    public JobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Upsert(long companyId, String externalId, String url, String applyUrl, String title, String descriptionHtml,
                         String descriptionText, List<String> locations, boolean remote, Boolean inBw, List<String> studentTypes,
                         List<String> itFields, List<String> techTerms, String germanLevel, String englishLevel,
                         String postingLanguage, String contactEmail, Instant postedAt, String fingerprint) {}

    public record UpsertResult(long id, boolean inserted) {}

    public UpsertResult upsert(Upsert u) {
        return jdbc.sql("""
                insert into job_posting(company_id, external_id, url, apply_url, title, description_html, description_text,
                  locations, remote, in_bw, student_types, it_fields, tech_terms, german_level, english_level, posting_language,
                  contact_email, posted_at, fingerprint)
                values (?,?,?,?,?,?,?,?::text[],?,?,?::text[],?::text[],?::text[],?,?,?,?,?,?)
                on conflict (fingerprint) do update set
                  external_id = excluded.external_id, url = excluded.url, apply_url = excluded.apply_url, title = excluded.title,
                  description_html = coalesce(excluded.description_html, job_posting.description_html),
                  description_text = coalesce(nullif(excluded.description_text, ''), job_posting.description_text),
                  locations = excluded.locations, remote = excluded.remote, in_bw = excluded.in_bw,
                  student_types = excluded.student_types, it_fields = excluded.it_fields, tech_terms = excluded.tech_terms,
                  german_level = case when job_posting.language_source = 'AI' then job_posting.german_level else excluded.german_level end,
                  english_level = case when job_posting.language_source = 'AI' then job_posting.english_level else excluded.english_level end,
                  posting_language = excluded.posting_language, contact_email = excluded.contact_email,
                  posted_at = coalesce(job_posting.posted_at, excluded.posted_at),
                  last_seen_at = now(), missed_scans = 0, status = 'OPEN'
                returning id, (xmax = 0) as inserted""")
                .params(u.companyId(), u.externalId(), u.url(), u.applyUrl(), u.title(), u.descriptionHtml(), u.descriptionText(),
                        Sql.arrayLiteral(u.locations()), u.remote(), u.inBw(), Sql.arrayLiteral(u.studentTypes()),
                        Sql.arrayLiteral(u.itFields()), Sql.arrayLiteral(u.techTerms()), u.germanLevel(), u.englishLevel(),
                        u.postingLanguage(), u.contactEmail(), Sql.ts(u.postedAt()), u.fingerprint())
                .query((rs, i) -> new UpsertResult(rs.getLong("id"), rs.getBoolean("inserted"))).single();
    }

    /** Jobs not seen in this run get a miss; after 2 consecutive misses they are CLOSED. Returns closed count. */
    public int markUnseen(long companyId, Instant runStart) {
        List<String> statuses = jdbc.sql("""
                update job_posting set missed_scans = missed_scans + 1,
                  status = case when missed_scans + 1 >= 2 then 'CLOSED' else status end
                where company_id = ? and status = 'OPEN' and last_seen_at < ?
                returning status""")
                .params(companyId, Sql.ts(runStart)).query(String.class).list();
        return (int) statuses.stream().filter("CLOSED"::equals).count();
    }

    public Optional<JobPosting> findById(long id) {
        return jdbc.sql("select * from job_posting where id = ?").param(id).query(MAPPER).optional();
    }

    public void updateLanguage(long id, String german, String english, String source) {
        jdbc.sql("update job_posting set german_level=?, english_level=?, language_source=? where id=?")
                .params(german, english, source, id).update();
    }

    public long countOpen() {
        return jdbc.sql("select count(*) from job_posting where status = 'OPEN'").query(Long.class).single();
    }

    public List<JobPosting> openByCompany(long companyId) {
        return jdbc.sql("select * from job_posting where company_id = ? and status = 'OPEN' order by first_seen_at desc")
                .param(companyId).query(MAPPER).list();
    }
}
