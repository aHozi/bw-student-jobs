package de.bwjobs.source;

import de.bwjobs.common.Text;
import de.bwjobs.company.AggregatorGuard;
import de.bwjobs.company.Company;
import de.bwjobs.job.HtmlText;
import de.bwjobs.job.JobClassifier;
import de.bwjobs.job.JobIngestService;
import de.bwjobs.job.JobRepository;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Classifies external jobs and stores them; duplicates across sources merge on the fingerprint. */
@Service
public class ExternalJobIngest {
    private final JobRepository jobs;
    private final CompanyResolver resolver;
    private final AggregatorGuard boards;

    public ExternalJobIngest(JobRepository jobs, CompanyResolver resolver, AggregatorGuard boards) {
        this.jobs = jobs;
        this.resolver = resolver;
        this.boards = boards;
    }

    public record Stats(int fetched, int accepted, int newJobs) {}

    public Stats ingest(List<ExternalJob> list) {
        int accepted = 0, created = 0;
        Set<String> seen = new HashSet<>();
        for (ExternalJob e : list) {
            if (e.title() == null || e.url() == null) continue;
            String city = e.locations() == null || e.locations().isEmpty() ? null : e.locations().getFirst();
            Company company = resolver.resolve(e.companyName(), city);
            JobClassifier.Classified c = JobClassifier.classify(company.id(), e.title(), e.descriptionHtml(), e.locations());
            if (c == null) continue;
            String apply = e.applyUrl() != null ? e.applyUrl() : e.url();
            JobRepository.UpsertResult r = jobs.upsert(new JobRepository.Upsert(
                    company.id(), Text.truncate(e.externalId(), 500), e.url(), apply, Text.truncate(e.title(), 300),
                    HtmlText.safeHtml(e.descriptionHtml(), e.url()), c.text(), e.locations() == null ? List.of() : e.locations(),
                    c.loc().remote(), c.loc().inBw(), c.typeNames(), c.fieldNames(), c.techTermList(),
                    c.lang().german().name(), c.lang().english().name(), c.lang().postingLanguage(),
                    JobIngestService.contactEmail(c.text()), e.postedAt(), c.fingerprint(),
                    e.source(), e.platform(), e.ownerUserId() == null, boards.isAggregator(apply), e.snippetOnly()));
            if (e.ownerUserId() != null) jobs.grantVisibility(e.ownerUserId(), r.id());
            if (seen.add(c.fingerprint())) {
                accepted++;
                if (r.inserted()) created++;
            }
        }
        return new Stats(list.size(), accepted, created);
    }
}
