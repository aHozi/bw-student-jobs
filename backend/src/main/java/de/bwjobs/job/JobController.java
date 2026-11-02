package de.bwjobs.job;

import de.bwjobs.common.ApiException;
import de.bwjobs.common.CurrentUser;
import de.bwjobs.common.Sql;
import de.bwjobs.company.Company;
import de.bwjobs.company.CompanyRepository;
import de.bwjobs.profile.ProfileRepository;
import de.bwjobs.profile.UserProfile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class JobController {
    private final JobSearchService search;
    private final JobRepository jobs;
    private final CompanyRepository companies;
    private final ProfileRepository profiles;
    private final JdbcClient jdbc;

    public JobController(JobSearchService search, JobRepository jobs, CompanyRepository companies,
                         ProfileRepository profiles, JdbcClient jdbc) {
        this.search = search;
        this.jobs = jobs;
        this.companies = companies;
        this.profiles = profiles;
        this.jdbc = jdbc;
    }

    @GetMapping("/jobs")
    public JobSearchService.Result list(@RequestParam(required = false) String q,
                                        @RequestParam(required = false) String sources,
                                        @RequestParam(required = false) String studentTypes,
                                        @RequestParam(required = false) String itFields,
                                        @RequestParam(required = false) String germanLevels,
                                        @RequestParam(required = false) String englishLevels,
                                        @RequestParam(required = false) Boolean includeUnknownLanguage,
                                        @RequestParam(required = false) String locationMode,
                                        @RequestParam(required = false) String city,
                                        @RequestParam(required = false) Integer postedWithinDays,
                                        @RequestParam(required = false) Integer minScore,
                                        @RequestParam(required = false) Long companyId,
                                        @RequestParam(required = false) Boolean hideApplied,
                                        @RequestParam(defaultValue = "match") String sort,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "25") int size) {
        return search.search(CurrentUser.id(), new JobQuery(q, csv(sources), csv(studentTypes), csv(itFields), csv(germanLevels),
                csv(englishLevels), includeUnknownLanguage, locationMode, city, postedWithinDays, minScore, companyId,
                hideApplied, sort, page, size));
    }

    public record AiScoreView(Integer score, String summary, Object details) {}

    public record ApplicationRef(long id, String status) {}

    public record JobDetail(JobPosting job, Company company, KeywordMatcher.Score keywordScore, AiScoreView aiScore,
                            ApplicationRef application, List<ApplicationRef> otherApplicationsAtCompany) {}

    @GetMapping("/jobs/{id}")
    public JobDetail get(@PathVariable long id) {
        long uid = CurrentUser.id();
        JobPosting j = jobs.findById(id).filter(x -> jobs.isVisible(uid, x.id())).orElseThrow(() -> ApiException.notFound("Job"));
        Company c = companies.findById(j.companyId()).orElseThrow();
        UserProfile p = profiles.get(uid);
        var score = KeywordMatcher.score(KeywordMatcher.userTerms(p, search.latestCvText(uid)), p, j.techTerms(),
                j.itFields(), j.studentTypes(), j.germanLevel(), j.englishLevel(), j.postingLanguage());
        AiScoreView ai = jdbc.sql("select ai_score, ai_summary, ai_details from match_score where user_id=? and job_id=?")
                .params(uid, id)
                .query((rs, i) -> new AiScoreView(Sql.intOrNull(rs, "ai_score"), rs.getString("ai_summary"),
                        Sql.readJson(rs.getString("ai_details"), Map.class)))
                .optional().orElse(null);
        ApplicationRef app = jdbc.sql("select id, status from application where user_id=? and job_id=?").params(uid, id)
                .query((rs, i) -> new ApplicationRef(rs.getLong("id"), rs.getString("status"))).optional().orElse(null);
        List<ApplicationRef> others = jdbc.sql("""
                select id, status from application where user_id=? and company_id=? and job_id<>? and status not in ('DRAFT','WITHDRAWN')""")
                .params(uid, c.id(), id).query((rs, i) -> new ApplicationRef(rs.getLong("id"), rs.getString("status"))).list();
        return new JobDetail(j, c, score, ai, app, others);
    }

    /** Static lists for filter UIs. */
    @GetMapping("/meta/filters")
    public Map<String, Object> filters() {
        return Map.of(
                "studentTypes", Arrays.stream(StudentType.values()).map(Enum::name).toList(),
                "itFields", List.copyOf(ItFieldTaxonomy.FIELDS.keySet()),
                "germanLevels", Arrays.stream(GermanLevel.values()).map(Enum::name).toList(),
                "englishLevels", Arrays.stream(EnglishLevel.values()).map(Enum::name).toList(),
                "locationModes", List.of("BW_ONLY", "BW_OR_REMOTE", "ALL"),
                "sources", List.of("EMAIL_ALERT", "ARBEITSAGENTUR", "ADZUNA", "CAREER_PAGE"));
    }

    private static List<String> csv(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
}
