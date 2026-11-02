package de.bwjobs.company;

import de.bwjobs.common.ApiException;
import de.bwjobs.common.AppProperties;
import de.bwjobs.job.JobRepository;
import de.bwjobs.scan.ScanQueue;
import de.bwjobs.scan.ScanRunLogRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CompanyController {
    private final CompanyRepository companies;
    private final JobRepository jobs;
    private final ScanQueue queue;
    private final ScanRunLogRepository runLogs;
    private final AggregatorGuard guard;
    private final AppProperties props;

    public CompanyController(CompanyRepository companies, JobRepository jobs, ScanQueue queue,
                             ScanRunLogRepository runLogs, AggregatorGuard guard, AppProperties props) {
        this.companies = companies;
        this.jobs = jobs;
        this.queue = queue;
        this.runLogs = runLogs;
        this.guard = guard;
        this.props = props;
    }

    public record Page<T>(List<T> items, long total) {}

    @GetMapping("/companies")
    public Page<CompanyRepository.CompanyRow> list(@RequestParam(required = false) String q,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String city,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        int s = Math.min(Math.max(size, 1), 200);
        return new Page<>(companies.search(q, status, city, s, Math.max(page, 0) * s), companies.countSearch(q, status, city));
    }

    public record CompanyDetail(Company company, List<de.bwjobs.job.JobPosting> openJobs, List<ScanRunLogRepository.RunLog> recentScans) {}

    @GetMapping("/companies/{id}")
    public CompanyDetail get(@PathVariable long id) {
        Company c = companies.findById(id).orElseThrow(() -> ApiException.notFound("Company"));
        List<de.bwjobs.job.JobPosting> open = jobs.openByCompany(id).stream().map(de.bwjobs.job.JobPosting::withoutDescription).toList();
        return new CompanyDetail(c, open, runLogs.recent(10, id));
    }

    // ---------------- admin ----------------

    public record CompanyInput(@NotBlank String name, String website, String careerUrl, String city, String industry,
                               String size, String atsType, String atsTenant, String status) {}

    @PostMapping("/admin/companies")
    public Company create(@Valid @RequestBody CompanyInput in) {
        validateUrls(in);
        if (companies.findByName(in.name()).isPresent()) throw ApiException.conflict("Company already exists", null);
        AtsType ats = in.atsType() == null || in.atsType().isBlank() ? AtsType.UNKNOWN : AtsType.valueOf(in.atsType());
        String tenant = in.atsTenant();
        if (ats == AtsType.UNKNOWN && in.careerUrl() != null) {
            var d = AtsDetector.fromUrl(in.careerUrl());
            if (d.isPresent()) {
                ats = d.get().type();
                tenant = d.get().tenant();
            }
        }
        Company c = companies.insert(in.name().trim(), blankToNull(in.website()), blankToNull(in.careerUrl()), in.city(),
                in.industry(), in.size(), ats, tenant, "ACTIVE", "ADMIN", props.scan().defaultIntervalHours());
        if (ats == AtsType.UNKNOWN) queue.enqueue(c.id(), "DISCOVER");
        else queue.enqueue(c.id(), "SCAN");
        return c;
    }

    @PutMapping("/admin/companies/{id}")
    public Company update(@PathVariable long id, @Valid @RequestBody CompanyInput in) {
        validateUrls(in);
        Company c = companies.findById(id).orElseThrow(() -> ApiException.notFound("Company"));
        AtsType ats = in.atsType() == null || in.atsType().isBlank() ? c.atsType() : AtsType.valueOf(in.atsType());
        companies.updateBasics(id, in.name().trim(), blankToNull(in.website()), blankToNull(in.careerUrl()), in.city(),
                in.industry(), in.size(), ats, blankToNull(in.atsTenant()), in.status() == null ? c.status() : in.status());
        return companies.findById(id).orElseThrow();
    }

    @DeleteMapping("/admin/companies/{id}")
    public void delete(@PathVariable long id) {
        companies.delete(id);
    }

    @PostMapping("/admin/companies/{id}/scan")
    public Map<String, Object> scanNow(@PathVariable long id) {
        companies.findById(id).orElseThrow(() -> ApiException.notFound("Company"));
        return Map.of("queued", queue.enqueue(id, "SCAN"));
    }

    @PostMapping("/admin/companies/{id}/discover")
    public Map<String, Object> discoverNow(@PathVariable long id) {
        companies.findById(id).orElseThrow(() -> ApiException.notFound("Company"));
        return Map.of("queued", queue.enqueue(id, "DISCOVER"));
    }

    public record ScanHealth(Map<String, Long> queue, long companies, long openJobs, List<Company> broken,
                             List<Company> needsReview, List<ScanRunLogRepository.RunLog> recentRuns) {}

    @GetMapping("/admin/scan-health")
    public ScanHealth health() {
        return new ScanHealth(queue.stats(), companies.count(), jobs.countOpen(), companies.findByStatus("BROKEN"),
                companies.findByStatus("NEEDS_REVIEW"), runLogs.recent(50, null));
    }

    private void validateUrls(CompanyInput in) {
        for (String u : new String[]{in.website(), in.careerUrl()}) {
            if (u != null && !u.isBlank() && guard.isAggregator(u)) {
                throw ApiException.badRequest("Job aggregators are not allowed. Use the company's official career page: " + u);
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
