package de.bwjobs.job;

import de.bwjobs.common.Sql;
import de.bwjobs.profile.ProfileRepository;
import de.bwjobs.profile.UserPreferences;
import de.bwjobs.profile.UserProfile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Filters jobs in SQL, scores them per user in memory, then sorts and pages.
 * Language requirements are only filters chosen by the user; nothing is excluded by default.
 */
@Service
public class JobSearchService {
    private static final int MAX_CANDIDATES = 5000;

    private final JdbcClient jdbc;
    private final ProfileRepository profiles;

    public JobSearchService(JdbcClient jdbc, ProfileRepository profiles) {
        this.jdbc = jdbc;
        this.profiles = profiles;
    }

    public record Result(List<JobSummary> items, long total, UserPreferences appliedPreferences) {}

    public Result search(long userId, JobQuery q) {
        UserProfile profile = profiles.get(userId);
        Set<String> userTerms = KeywordMatcher.userTerms(profile, latestCvText(userId));

        StringBuilder sql = new StringBuilder("""
                select j.id, j.title, j.company_id, c.name as company_name, c.hq_city, j.locations, j.in_bw, j.remote,
                       j.student_types, j.it_fields, j.tech_terms, j.german_level, j.english_level, j.posting_language,
                       j.posted_at, j.first_seen_at, j.url, m.ai_score, a.status as app_status, a.id as app_id
                from job_posting j
                join company c on c.id = j.company_id
                left join match_score m on m.job_id = j.id and m.user_id = :uid
                left join application a on a.job_id = j.id and a.user_id = :uid
                where j.status = 'OPEN'""");
        Map<String, Object> p = new HashMap<>();
        p.put("uid", userId);

        if (notEmpty(q.studentTypes())) {
            sql.append(" and j.student_types && cast(:types as text[])");
            p.put("types", Sql.arrayLiteral(q.studentTypes()));
        }
        if (notEmpty(q.itFields())) {
            sql.append(" and j.it_fields && cast(:fields as text[])");
            p.put("fields", Sql.arrayLiteral(q.itFields()));
        }
        boolean includeUnknown = q.includeUnknownLanguage() == null || q.includeUnknownLanguage();
        if (notEmpty(q.germanLevels())) {
            List<String> levels = new ArrayList<>(q.germanLevels());
            if (includeUnknown) levels.add("UNKNOWN");
            sql.append(" and j.german_level in (:german)");
            p.put("german", levels);
        }
        if (notEmpty(q.englishLevels())) {
            List<String> levels = new ArrayList<>(q.englishLevels());
            if (includeUnknown) levels.add("UNKNOWN");
            sql.append(" and j.english_level in (:english)");
            p.put("english", levels);
        }
        String mode = q.locationMode() == null ? "BW_OR_REMOTE" : q.locationMode();
        switch (mode) {
            case "BW_ONLY" -> sql.append(" and j.in_bw = true");
            case "BW_OR_REMOTE" -> sql.append(" and (j.in_bw = true or j.remote = true or j.in_bw is null)");
            default -> { }
        }
        if (q.city() != null && !q.city().isBlank()) {
            sql.append(" and (array_to_string(j.locations, ' ') ilike :city or c.hq_city ilike :city)");
            p.put("city", "%" + q.city().trim() + "%");
        }
        if (q.postedWithinDays() != null && q.postedWithinDays() > 0) {
            sql.append(" and coalesce(j.posted_at, j.first_seen_at) >= now() - make_interval(days => :days)");
            p.put("days", q.postedWithinDays());
        }
        if (q.companyId() != null) {
            sql.append(" and j.company_id = :cid");
            p.put("cid", q.companyId());
        }
        if (Boolean.TRUE.equals(q.hideApplied())) {
            sql.append(" and (a.id is null or a.status in ('DRAFT','WITHDRAWN'))");
        }
        if (q.q() != null && !q.q().isBlank()) {
            sql.append(" and (j.title ilike :q or c.name ilike :q or j.description_text ilike :q)");
            p.put("q", "%" + q.q().trim() + "%");
        }
        sql.append(" order by coalesce(j.posted_at, j.first_seen_at) desc limit ").append(MAX_CANDIDATES);

        List<JobSummary> all = jdbc.sql(sql.toString()).params(p).query((rs, i) -> {
            List<String> terms = Sql.textArray(rs, "tech_terms");
            List<String> fields = Sql.textArray(rs, "it_fields");
            List<String> types = Sql.textArray(rs, "student_types");
            String german = rs.getString("german_level");
            String english = rs.getString("english_level");
            String postingLang = rs.getString("posting_language");
            KeywordMatcher.Score s = KeywordMatcher.score(userTerms, profile, terms, fields, types, german, english, postingLang);
            Integer ai = Sql.intOrNull(rs, "ai_score");
            return new JobSummary(rs.getLong("id"), rs.getString("title"), rs.getLong("company_id"), rs.getString("company_name"),
                    rs.getString("hq_city"), Sql.textArray(rs, "locations"), Sql.boolOrNull(rs, "in_bw"), rs.getBoolean("remote"),
                    types, fields, german, english, postingLang, Sql.instant(rs, "posted_at"), Sql.instant(rs, "first_seen_at"),
                    rs.getString("url"), s.total(), ai, ai != null ? ai : s.total(), s.languageFit(),
                    rs.getString("app_status"), Sql.longOrNull(rs, "app_id"));
        }).list();

        int min = q.minScore() == null ? 0 : q.minScore();
        List<JobSummary> filtered = new ArrayList<>(all.stream().filter(j -> j.score() >= min).toList());
        if (!"date".equals(q.sort())) {
            filtered.sort(Comparator.comparingInt(JobSummary::score).reversed()
                    .thenComparing(JobSummary::firstSeenAt, Comparator.reverseOrder()));
        }
        int size = Math.min(Math.max(q.size(), 1), 100);
        int from = Math.min(Math.max(q.page(), 0) * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return new Result(filtered.subList(from, to), filtered.size(), profile.preferences());
    }

    public String latestCvText(long userId) {
        return jdbc.sql("""
                select extracted_text from document where user_id = ? and kind = 'CV' and original = true
                order by created_at desc limit 1""")
                .param(userId).query(String.class).optional().orElse(null);
    }

    private static boolean notEmpty(List<?> l) {
        return l != null && !l.isEmpty();
    }
}
