package de.bwjobs.ai;

import de.bwjobs.common.ApiException;
import de.bwjobs.common.Sql;
import de.bwjobs.company.Company;
import de.bwjobs.company.CompanyRepository;
import de.bwjobs.job.JobPosting;
import de.bwjobs.job.JobRepository;
import de.bwjobs.job.JobSearchService;
import de.bwjobs.profile.ProfileRepository;
import de.bwjobs.profile.UserProfile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** On-demand AI match score for one user and one job, cached in match_score. */
@Service
public class MatchScorer {
    private final AiClient claude;
    private final JobRepository jobs;
    private final CompanyRepository companies;
    private final ProfileRepository profiles;
    private final JobSearchService search;
    private final JdbcClient jdbc;

    public MatchScorer(AiClient claude, JobRepository jobs, CompanyRepository companies, ProfileRepository profiles,
                       JobSearchService search, JdbcClient jdbc) {
        this.claude = claude;
        this.jobs = jobs;
        this.companies = companies;
        this.profiles = profiles;
        this.search = search;
        this.jdbc = jdbc;
    }

    public AiModels.MatchAssessment score(long userId, long jobId) {
        UserProfile p = profiles.get(userId);
        AiConsent.require(p);
        JobPosting j = jobs.findById(jobId).orElseThrow(() -> ApiException.notFound("Job"));
        Company c = companies.findById(j.companyId()).orElseThrow();
        String cv = search.latestCvText(userId);
        if (cv == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Upload your CV first to get an AI match score.");
        AiModels.MatchAssessment a = claude.structured(userId, Prompts.get("match-score"),
                AiContext.job(j, c) + AiContext.cv(cv) + AiContext.profile(p) + "\nAssess the fit.",
                AiModels.MatchAssessment.class, 3000);
        int score = Math.max(0, Math.min(100, a.score()));
        jdbc.sql("""
                insert into match_score(user_id, job_id, ai_score, ai_summary, ai_details, computed_at) values (?,?,?,?,?,now())
                on conflict (user_id, job_id) do update set ai_score = excluded.ai_score, ai_summary = excluded.ai_summary,
                  ai_details = excluded.ai_details, computed_at = now()""")
                .params(userId, jobId, score, a.summary(), Sql.jsonb(a)).update();
        return a;
    }
}
