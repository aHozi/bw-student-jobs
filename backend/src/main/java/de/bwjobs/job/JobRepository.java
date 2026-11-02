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
            Sql.instant(rs, "first_seen_at"), Sql.instant(rs, "last_seen_at"), rs.getString("status"), rs.getString("fingerprint"),
            Sql.textArray(rs, "sources"), Sql.textArray(rs, "platforms"), rs.getBoolean("apply_is_board"), rs.getBoolean("snippet_only"));

    public JobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Upsert(long companyId, String externalId, String url, String applyUrl, String title, String descriptionHtml,
                         String descriptionText, List<String> locations, boolean remote, Boolean inBw, List<String> studentTypes,
                         List<String> itFields, List<String> techTerms, String germanLevel, String englishLevel,
                         String postingLanguage, String contactEmail, Instant postedAt, String fingerprint,
                         String source, String platform, boolean publicVisible, boolean applyIsBoard, boolean snippetOnly) {

        /** Career-page job (the original source type). */
        public Upsert(long companyId, String externalId, String url, String applyUrl, String title, String descriptionHtml,
                      String descriptionText, List<String> locations, boolean remote, Boolean inBw, List<String> studentTypes,
                      List<String> itFields, List<String> techTerms, String germanLevel, String englishLevel,
                      String postingLanguage, String contactEmail, Instant postedAt, String fingerprint) {
            this(companyId, externalId, url, applyUrl, title, descriptionHtml, descriptionText, locations, remote, inBw,
                    studentTypes, itFields, techTerms, germanLevel, englishLevel, postingLanguage, contactEmail, postedAt,
                    fingerprint, "CAREER_PAGE", "COMPANY", true, false, false);
        }
    }

    public record UpsertResult(long id, boolean inserted) {}

    /**
     * Inserts or merges a job. When the same job arrives from several sources (fingerprint match):
     * sources/platforms are combined, a company apply link beats a job-board link, the longer description wins,
     * and the job becomes public as soon as any public source has it.
     */
    public UpsertResult upsert(Upsert u) {
        String newer = "length(coalesce(excluded.description_text, '')) >= length(coalesce(job_posting.description_text, ''))";
        String keepOld = "(not job_posting.apply_is_board and excluded.apply_is_board)";
        return jdbc.sql("""
                insert into job_posting(company_id, external_id, url, apply_url, title, description_html, description_text,
                  locations, remote, in_bw, student_types, it_fields, tech_terms, german_level, english_level, posting_language,
                  contact_email, posted_at, fingerprint, sources, platforms, public_visible, apply_is_board, snippet_only)
                values (?,?,?,?,?,?,?,?::text[],?,?,?::text[],?::text[],?::text[],?,?,?,?,?,?,?::text[],?::text[],?,?,?)
                on conflict (fingerprint) do update set
                  external_id = case when KEEP then job_posting.external_id else excluded.external_id end,
                  url = case when KEEP then job_posting.url else excluded.url end,
                  apply_url = case when KEEP then job_posting.apply_url else excluded.apply_url end,
                  apply_is_board = job_posting.apply_is_board and excluded.apply_is_board,
                  title = case when NEWER then excluded.title else job_posting.title end,
                  description_html = case when NEWER then coalesce(excluded.description_html, job_posting.description_html) else job_posting.description_html end,
                  description_text = case when NEWER then coalesce(nullif(excluded.description_text, ''), job_posting.description_text) else job_posting.description_text end,
                  snippet_only = case when NEWER then excluded.snippet_only else job_posting.snippet_only end,
                  locations = case when NEWER and cardinality(excluded.locations) > 0 then excluded.locations else job_posting.locations end,
                  remote = job_posting.remote or excluded.remote,
                  in_bw = case when job_posting.in_bw or excluded.in_bw then true else coalesce(excluded.in_bw, job_posting.in_bw) end,
                  student_types = case when NEWER then excluded.student_types else job_posting.student_types end,
                  it_fields = case when NEWER then excluded.it_fields else job_posting.it_fields end,
                  tech_terms = case when NEWER then excluded.tech_terms else job_posting.tech_terms end,
                  german_level = case when job_posting.language_source = 'AI' or not (NEWER) then job_posting.german_level else excluded.german_level end,
                  english_level = case when job_posting.language_source = 'AI' or not (NEWER) then job_posting.english_level else excluded.english_level end,
                  posting_language = case when NEWER then coalesce(excluded.posting_language, job_posting.posting_language) else job_posting.posting_language end,
                  contact_email = coalesce(excluded.contact_email, job_posting.contact_email),
                  posted_at = coalesce(job_posting.posted_at, excluded.posted_at),
                  sources = array(select distinct unnest(job_posting.sources || excluded.sources) order by 1),
                  platforms = array(select distinct unnest(job_posting.platforms || excluded.platforms) order by 1),
                  public_visible = job_posting.public_visible or excluded.public_visible,
                  last_seen_at = now(), missed_scans = 0, status = 'OPEN'
                returning id, (xmax = 0) as inserted""".replace("NEWER", newer).replace("KEEP", keepOld))
                .params(u.companyId(), u.externalId(), u.url(), u.applyUrl(), u.title(), u.descriptionHtml(), u.descriptionText(),
                        Sql.arrayLiteral(u.locations()), u.remote(), u.inBw(), Sql.arrayLiteral(u.studentTypes()),
                        Sql.arrayLiteral(u.itFields()), Sql.arrayLiteral(u.techTerms()), u.germanLevel(), u.englishLevel(),
                        u.postingLanguage(), u.contactEmail(), Sql.ts(u.postedAt()), u.fingerprint(),
                        Sql.arrayLiteral(u.source()), Sql.arrayLiteral(u.platform()), u.publicVisible(), u.applyIsBoard(), u.snippetOnly())
                .query((rs, i) -> new UpsertResult(rs.getLong("id"), rs.getBoolean("inserted"))).single();
    }

    /** Makes a private (e-mail alert) job visible to one user. */
    public void grantVisibility(long userId, long jobId) {
        jdbc.sql("insert into job_visibility(user_id, job_id) values (?, ?) on conflict do nothing").params(userId, jobId).update();
    }

    public boolean isVisible(long userId, long jobId) {
        return jdbc.sql("""
                select exists(select 1 from job_posting j where j.id = ? and (j.public_visible
                  or exists(select 1 from job_visibility v where v.job_id = j.id and v.user_id = ?)))""")
                .params(jobId, userId).query(Boolean.class).single();
    }

    /**
     * External-source jobs cannot be re-checked one by one: API jobs not seen for 7 days and
     * e-mail-alert jobs older than 30 days are closed. Career-page jobs are closed by their scans.
     */
    public int closeStaleExternal() {
        return jdbc.sql("""
                update job_posting set status = 'CLOSED'
                where status = 'OPEN' and not ('CAREER_PAGE' = any(sources))
                  and last_seen_at < now() - case when sources && '{ARBEITSAGENTUR,ADZUNA}'::text[]
                                                  then interval '7 days' else interval '30 days' end""").update();
    }

    /** Jobs not seen in this run get a miss; after 2 consecutive misses they are CLOSED. Returns closed count. */
    public int markUnseen(long companyId, Instant runStart) {
        List<String> statuses = jdbc.sql("""
                update job_posting set missed_scans = missed_scans + 1,
                  status = case when missed_scans + 1 >= 2 then 'CLOSED' else status end
                where company_id = ? and status = 'OPEN' and last_seen_at < ? and 'CAREER_PAGE' = any(sources)
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
        return jdbc.sql("select * from job_posting where company_id = ? and status = 'OPEN' and public_visible order by first_seen_at desc")
                .param(companyId).query(MAPPER).list();
    }
}
