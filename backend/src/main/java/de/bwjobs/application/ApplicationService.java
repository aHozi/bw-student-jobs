package de.bwjobs.application;

import de.bwjobs.common.ApiException;
import de.bwjobs.document.Document;
import de.bwjobs.document.DocumentService;
import de.bwjobs.job.JobPosting;
import de.bwjobs.job.JobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
public class ApplicationService {
    private final ApplicationRepository apps;
    private final JobRepository jobs;
    private final DocumentService documents;
    private final DuplicateGuard duplicates;
    private final ApplicationRunner runner;

    public ApplicationService(ApplicationRepository apps, JobRepository jobs, DocumentService documents,
                              DuplicateGuard duplicates, ApplicationRunner runner) {
        this.apps = apps;
        this.jobs = jobs;
        this.documents = documents;
        this.duplicates = duplicates;
        this.runner = runner;
    }

    public record CreateRequest(long jobId, Long cvDocumentId, Long coverLetterId, List<Long> extraDocumentIds, boolean confirmSimilar) {}

    public Application create(long userId, CreateRequest req) {
        JobPosting job = jobs.findById(req.jobId()).orElseThrow(() -> ApiException.notFound("Job"));
        duplicates.check(userId, job, req.confirmSimilar());
        Docs d = validateDocs(userId, req.cvDocumentId(), req.coverLetterId(), req.extraDocumentIds());
        try {
            return apps.insert(userId, job.id(), job.companyId(), d.cv.id(), d.cl == null ? null : d.cl.id(), d.extraIds,
                    d.original, "AUTO_FORM");
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("You already have an application for this job", Map.of("type", "SAME_JOB"));
        }
    }

    public Application changeDocuments(long userId, long id, CreateRequest req) {
        Application a = owned(userId, id);
        requireStatus(a, ApplicationStatus.DRAFT, ApplicationStatus.READY_FOR_REVIEW, ApplicationStatus.MANUAL_REQUIRED, ApplicationStatus.FAILED);
        Docs d = validateDocs(userId, req.cvDocumentId(), req.coverLetterId(), req.extraDocumentIds());
        apps.updateDocuments(id, d.cv.id(), d.cl == null ? null : d.cl.id(), d.extraIds, d.original);
        apps.setStatus(id, ApplicationStatus.DRAFT); // previous review is no longer valid
        return owned(userId, id);
    }

    /** Starts filling the form in the background. The form is NOT submitted. */
    public Application prepare(long userId, long id) {
        Application a = owned(userId, id);
        requireStatus(a, ApplicationStatus.DRAFT, ApplicationStatus.READY_FOR_REVIEW, ApplicationStatus.MANUAL_REQUIRED, ApplicationStatus.FAILED);
        apps.setStatus(id, ApplicationStatus.PREPARING);
        runner.prepareAsync(id);
        return owned(userId, id);
    }

    public record ApproveRequest(boolean confirm, Map<String, String> overrides, List<String> consents) {}

    /** The user's explicit approval. Only then the form is submitted. */
    public Application approveAndSubmit(long userId, long id, ApproveRequest req) {
        if (req == null || !req.confirm()) {
            throw ApiException.badRequest("Submission needs your explicit confirmation (confirm=true).");
        }
        Application a = owned(userId, id);
        requireStatus(a, ApplicationStatus.READY_FOR_REVIEW);
        apps.approve(id, req.overrides() == null ? Map.of() : req.overrides(), req.consents() == null ? List.of() : req.consents());
        runner.submitAsync(id);
        return owned(userId, id);
    }

    /** For manual / e-mail applications the user sent themselves. */
    public Application markSubmitted(long userId, long id) {
        Application a = owned(userId, id);
        requireStatus(a, ApplicationStatus.DRAFT, ApplicationStatus.READY_FOR_REVIEW, ApplicationStatus.MANUAL_REQUIRED, ApplicationStatus.FAILED);
        apps.markSubmitted(id, null, null);
        return owned(userId, id);
    }

    public record TrackingUpdate(String status, String notes, LocalDate followUpOn) {}

    public Application updateTracking(long userId, long id, TrackingUpdate u) {
        Application a = owned(userId, id);
        if (u.status() != null && !u.status().equals(a.status())) {
            if (!ApplicationStatus.TRACKING.contains(u.status())) {
                throw ApiException.badRequest("Status can only be set to " + ApplicationStatus.TRACKING);
            }
            if (!u.status().equals(ApplicationStatus.WITHDRAWN) && !ApplicationStatus.APPLIED.contains(a.status())
                    && !u.status().equals(ApplicationStatus.SUBMITTED)) {
                throw ApiException.badRequest("Mark the application as submitted first.");
            }
        }
        apps.updateTracking(id, u.status(), u.notes(), u.followUpOn());
        return owned(userId, id);
    }

    public void delete(long userId, long id) {
        Application a = owned(userId, id);
        if (ApplicationStatus.APPLIED.contains(a.status())) {
            throw ApiException.badRequest("Submitted applications are kept for tracking. Set them to WITHDRAWN instead.");
        }
        apps.delete(id);
    }

    public Application owned(long userId, long id) {
        return apps.findOwned(userId, id).orElseThrow(() -> ApiException.notFound("Application"));
    }

    private static void requireStatus(Application a, String... allowed) {
        if (!Arrays.asList(allowed).contains(a.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Not possible while the application is " + a.status());
        }
    }

    private record Docs(Document cv, Document cl, List<Long> extraIds, boolean original) {}

    private Docs validateDocs(long userId, Long cvId, Long clId, List<Long> extras) {
        if (cvId == null) throw ApiException.badRequest("Choose a CV");
        Document cv = documents.get(userId, cvId);
        if (!"CV".equals(cv.kind())) throw ApiException.badRequest("The selected CV document is not a CV");
        Document cl = clId == null ? null : documents.get(userId, clId);
        if (cl != null && !"COVER_LETTER".equals(cl.kind())) throw ApiException.badRequest("The selected cover letter is not a cover letter");
        List<Long> extraIds = new ArrayList<>();
        if (extras != null) for (Long e : extras) extraIds.add(documents.get(userId, e).id());
        return new Docs(cv, cl, extraIds, cv.original() && (cl == null || cl.original()));
    }
}
