package de.bwjobs.ai;

import de.bwjobs.common.AppProperties;
import de.bwjobs.company.Company;
import de.bwjobs.company.CompanyRepository;
import de.bwjobs.job.EnglishLevel;
import de.bwjobs.job.GermanLevel;
import de.bwjobs.job.JobPosting;
import de.bwjobs.job.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Optional (app.ai.language-fallback=true): asks Claude to classify language requirements for
 * postings where the rules found nothing. Runs in small batches in the background.
 */
@Service
public class LanguageAiFallback {
    private static final Logger log = LoggerFactory.getLogger(LanguageAiFallback.class);

    private final AiClient claude;
    private final JobRepository jobs;
    private final CompanyRepository companies;
    private final JdbcClient jdbc;
    private final AppProperties.Ai cfg;

    public LanguageAiFallback(AiClient claude, JobRepository jobs, CompanyRepository companies, JdbcClient jdbc, AppProperties props) {
        this.claude = claude;
        this.jobs = jobs;
        this.companies = companies;
        this.jdbc = jdbc;
        this.cfg = props.ai();
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT2M")
    public void run() {
        if (!cfg.languageFallback() || !claude.enabled()) return;
        List<Long> ids = jdbc.sql("""
                select id from job_posting where status = 'OPEN' and language_source = 'RULES' and german_level = 'UNKNOWN'
                  and length(coalesce(description_text, '')) > 300 order by first_seen_at desc limit 20""")
                .query(Long.class).list();
        for (long id : ids) {
            try {
                JobPosting j = jobs.findById(id).orElseThrow();
                Company c = companies.findById(j.companyId()).orElseThrow();
                AiModels.LanguageAnalysis a = claude.structured(null, Prompts.get("language-requirements"),
                        AiContext.job(j, c) + "\nClassify the language requirements.", AiModels.LanguageAnalysis.class, 1000);
                jobs.updateLanguage(id, safeGerman(a.germanLevel()), safeEnglish(a.englishLevel()), "AI");
            } catch (Exception e) {
                log.warn("Language fallback failed for job {}: {}", id, e.getMessage());
                return;
            }
        }
    }

    static String safeGerman(String v) {
        try {
            return GermanLevel.valueOf(v).name();
        } catch (Exception e) {
            return GermanLevel.UNKNOWN.name();
        }
    }

    static String safeEnglish(String v) {
        try {
            return EnglishLevel.valueOf(v).name();
        } catch (Exception e) {
            return EnglishLevel.UNKNOWN.name();
        }
    }
}
