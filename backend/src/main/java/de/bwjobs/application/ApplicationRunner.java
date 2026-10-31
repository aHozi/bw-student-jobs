package de.bwjobs.application;

import de.bwjobs.application.formfill.FieldMapper;
import de.bwjobs.application.formfill.FormFillService;
import de.bwjobs.document.Document;
import de.bwjobs.document.DocumentService;
import de.bwjobs.document.DocumentStorage;
import de.bwjobs.job.JobPosting;
import de.bwjobs.job.JobRepository;
import de.bwjobs.profile.ProfileRepository;
import de.bwjobs.profile.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Runs browser work in the background (prepare, submit) and stores the results. */
@Component
public class ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ApplicationRunner.class);

    private final ApplicationRepository apps;
    private final JobRepository jobs;
    private final ProfileRepository profiles;
    private final DocumentService documents;
    private final DocumentStorage storage;
    private final FormFillService filler;

    public ApplicationRunner(ApplicationRepository apps, JobRepository jobs, ProfileRepository profiles,
                             DocumentService documents, DocumentStorage storage, FormFillService filler) {
        this.apps = apps;
        this.jobs = jobs;
        this.profiles = profiles;
        this.documents = documents;
        this.storage = storage;
        this.filler = filler;
    }

    @Async("applicationExecutor")
    public void prepareAsync(long id) {
        prepare(id);
    }

    @Async("applicationExecutor")
    public void submitAsync(long id) {
        submit(id);
    }

    public void prepare(long id) {
        Application a = apps.findById(id).orElseThrow();
        JobPosting job = jobs.findById(a.jobId()).orElseThrow();
        try (Material m = material(a)) {
            FormFillService.Result r = filler.prepare(job.bestApplyUrl(), m.fieldMaterial());
            String shotKey = r.screenshot() == null ? null : storage.put(r.screenshot());
            FillReport rep = r.report();
            boolean noForm = rep.fields().isEmpty();
            if (noForm && job.contactEmail() != null) {
                FillReport withEmail = new FillReport(rep.url(), rep.fields(), rep.captcha(), rep.loginRequired(), false, 0,
                        "This posting asks for applications by e-mail.", emailDraft(a, job, m.profile, m.coverLetterText));
                apps.saveFillResult(id, ApplicationStatus.MANUAL_REQUIRED, "EMAIL", withEmail, shotKey, "Apply by e-mail");
            } else if (noForm || rep.captcha() || rep.loginRequired() || !rep.submitButtonFound()) {
                apps.saveFillResult(id, ApplicationStatus.MANUAL_REQUIRED, "MANUAL", rep, shotKey, rep.message());
            } else {
                apps.saveFillResult(id, ApplicationStatus.READY_FOR_REVIEW, "AUTO_FORM", rep, shotKey, null);
            }
        } catch (Exception e) {
            log.warn("Prepare failed for application {}: {}", id, e.getMessage());
            apps.saveFillResult(id, ApplicationStatus.MANUAL_REQUIRED, "MANUAL",
                    FillReport.manual(job.bestApplyUrl(), "Automatic filling failed: " + e.getMessage()), null, "Automation failed");
        }
    }

    public void submit(long id) {
        // Guard: only an application the user approved can be submitted, and only once.
        if (!apps.transition(id, ApplicationStatus.APPROVED, ApplicationStatus.SUBMITTING)) {
            log.warn("Refusing to submit application {}: not in APPROVED state", id);
            return;
        }
        Application a = apps.findById(id).orElseThrow();
        if (a.approvedAt() == null) {
            apps.markFailed(id, "Missing approval", null);
            return;
        }
        JobPosting job = jobs.findById(a.jobId()).orElseThrow();
        String url = a.fillReport() != null && a.fillReport().url() != null ? a.fillReport().url() : job.bestApplyUrl();
        try (Material m = material(a)) {
            FormFillService.Result r = filler.submit(url, m.fieldMaterial(),
                    a.overrides() == null ? Map.of() : a.overrides(),
                    new HashSet<>(a.consents() == null ? List.of() : a.consents()));
            String shotKey = r.screenshot() == null ? null : storage.put(r.screenshot());
            if (r.submitted()) {
                apps.markSubmitted(id, shotKey, r.confirmed() ? null : r.error());
            } else {
                apps.markFailed(id, r.error() == null ? "Not submitted" : r.error(), shotKey);
            }
        } catch (Exception e) {
            log.warn("Submit failed for application {}: {}", id, e.getMessage());
            apps.markFailed(id, "Submission failed: " + e.getMessage(), null);
        }
    }

    private FillReport.EmailDraft emailDraft(Application a, JobPosting job, UserProfile p, String coverLetterText) {
        boolean de = !"EN".equals(job.postingLanguage());
        String subject = (de ? "Bewerbung: " : "Application: ") + job.title();
        String name = p.fullName() == null ? "" : p.fullName();
        String body = de
                ? "Sehr geehrte Damen und Herren,\n\nanbei sende ich Ihnen meine Bewerbungsunterlagen für die Stelle \"" + job.title()
                  + "\". Ich freue mich auf Ihre Rückmeldung.\n\nMit freundlichen Grüßen\n" + name
                : "Dear Hiring Team,\n\nplease find attached my application documents for the position \"" + job.title()
                  + "\". I look forward to hearing from you.\n\nKind regards\n" + name;
        List<Long> attachments = new ArrayList<>();
        if (a.cvDocumentId() != null) attachments.add(a.cvDocumentId());
        if (a.coverLetterId() != null) attachments.add(a.coverLetterId());
        attachments.addAll(a.extraDocumentIds());
        return new FillReport.EmailDraft(job.contactEmail(), subject, body, attachments);
    }

    /** Decrypted copies of the chosen documents in a private temp dir, deleted after use. */
    private final class Material implements AutoCloseable {
        final Path dir;
        final UserProfile profile;
        Path cv;
        Path coverLetter;
        final List<Path> extras = new ArrayList<>();
        String coverLetterText;

        Material(Application a) throws IOException {
            dir = Files.createTempDirectory("bwjobs-app-" + a.id() + "-");
            profile = profiles.get(a.userId());
            if (a.cvDocumentId() != null) cv = write(documents.get(a.userId(), a.cvDocumentId()));
            if (a.coverLetterId() != null) {
                Document cl = documents.get(a.userId(), a.coverLetterId());
                coverLetter = write(cl);
                coverLetterText = cl.extractedText();
            }
            for (Long e : a.extraDocumentIds()) extras.add(write(documents.get(a.userId(), e)));
        }

        private Path write(Document d) throws IOException {
            Path sub = Files.createDirectories(dir.resolve(Long.toString(d.id())));
            Path p = sub.resolve(d.filename());
            Files.write(p, documents.content(d));
            return p;
        }

        FieldMapper.Material fieldMaterial() {
            return new FieldMapper.Material(profile, cv, coverLetter, extras, coverLetterText);
        }

        @Override
        public void close() {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            } catch (IOException ignored) {}
        }
    }

    private Material material(Application a) throws IOException {
        return new Material(a);
    }
}
