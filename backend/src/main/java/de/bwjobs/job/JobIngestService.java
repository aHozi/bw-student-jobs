package de.bwjobs.job;

import de.bwjobs.company.AggregatorGuard;
import de.bwjobs.company.Company;
import de.bwjobs.common.AppProperties;
import de.bwjobs.common.Text;
import de.bwjobs.scan.JobSourceAdapter;
import de.bwjobs.scan.RawJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw scanned jobs into stored postings: student filter → details → IT filter →
 * language + location tagging → upsert by fingerprint → close jobs that disappeared.
 */
@Service
public class JobIngestService {
    private static final Logger log = LoggerFactory.getLogger(JobIngestService.class);
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private final JobRepository jobs;
    private final AggregatorGuard guard;
    private final AppProperties.Scan cfg;

    public JobIngestService(JobRepository jobs, AggregatorGuard guard, AppProperties props) {
        this.jobs = jobs;
        this.guard = guard;
        this.cfg = props.scan();
    }

    public record Stats(int listed, int studentLike, int accepted, int newJobs, int closed) {}

    public Stats ingest(Company company, JobSourceAdapter adapter, List<RawJob> raw, Instant runStart) {
        int studentLike = 0, accepted = 0, created = 0, detailFetches = 0;
        Set<String> seenFingerprints = new HashSet<>();
        for (RawJob r : raw) {
            if (r.title() == null || r.url() == null) continue;
            if (guard.isAggregator(r.url()) || (r.applyUrl() != null && guard.isAggregator(r.applyUrl()))) continue;
            Set<StudentType> types = StudentTypeRules.detect(r.title());
            if (types.isEmpty()) continue;
            studentLike++;

            RawJob job = r;
            if (!job.detailsLoaded() && detailFetches < cfg.maxDetailPagesPerCompany()) {
                detailFetches++;
                try {
                    job = adapter.details(company, job);
                } catch (Exception e) {
                    log.debug("Details failed for {}: {}", r.url(), e.getMessage());
                }
            }
            String text = HtmlText.toText(job.descriptionHtml());
            ItFieldTaxonomy.Result it = ItFieldTaxonomy.classify(job.title(), text);
            if (!it.isIt()) continue;

            LanguageRequirementExtractor.Result lang = LanguageRequirementExtractor.extract(job.title(), text);
            BwLocationMatcher.Result loc = BwLocationMatcher.match(job.locations(), text);
            String fp = JobFingerprint.of(company.id(), job.title(), job.locations());
            if (!seenFingerprints.add(fp)) continue;

            JobRepository.UpsertResult res = jobs.upsert(new JobRepository.Upsert(
                    company.id(), Text.truncate(job.externalId(), 500), job.url(), job.applyUrl(), Text.truncate(job.title(), 300),
                    HtmlText.safeHtml(job.descriptionHtml(), job.url()), text, job.locations(), job.remote() || loc.remote(), loc.inBw(),
                    types.stream().map(Enum::name).sorted().toList(), new ArrayList<>(it.fields()), new ArrayList<>(it.techTerms()),
                    lang.german().name(), lang.english().name(), lang.postingLanguage(), contactEmail(text), job.postedAt(), fp));
            accepted++;
            if (res.inserted()) created++;
        }
        int closed = jobs.markUnseen(company.id(), runStart);
        return new Stats(raw.size(), studentLike, accepted, created, closed);
    }

    /** Application e-mail address mentioned in the posting, if any (for e-mail applications). */
    static String contactEmail(String text) {
        if (text == null) return null;
        Matcher m = EMAIL.matcher(text);
        String fallback = null;
        while (m.find()) {
            String e = m.group().toLowerCase(Locale.ROOT);
            if (e.endsWith(".png") || e.endsWith(".jpg")) continue;
            if (e.matches(".*(bewerbung|karriere|career|jobs|recruiting|hr|personal|application|apply).*@.*")) return e;
            if (fallback == null) fallback = e;
        }
        return fallback;
    }
}
