package de.bwjobs.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import de.bwjobs.common.ApiException;
import de.bwjobs.common.Sql;
import de.bwjobs.company.Company;
import de.bwjobs.company.CompanyRepository;
import de.bwjobs.document.Document;
import de.bwjobs.document.DocumentService;
import de.bwjobs.document.PdfRenderer;
import de.bwjobs.job.JobPosting;
import de.bwjobs.job.JobRepository;
import de.bwjobs.profile.LanguageSkill;
import de.bwjobs.profile.ProfileRepository;
import de.bwjobs.profile.UserProfile;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Per-job documents: a tailored CV and a cover letter. Both are saved as new documents linked to the
 * original CV and the job; the original upload is never modified. Users can edit the content and re-render.
 */
@Service
public class TailoringService {
    private final ClaudeClient claude;
    private final DocumentService documents;
    private final PdfRenderer pdf;
    private final JobRepository jobs;
    private final CompanyRepository companies;
    private final ProfileRepository profiles;

    public TailoringService(ClaudeClient claude, DocumentService documents, PdfRenderer pdf, JobRepository jobs,
                            CompanyRepository companies, ProfileRepository profiles) {
        this.claude = claude;
        this.documents = documents;
        this.pdf = pdf;
        this.jobs = jobs;
        this.companies = companies;
        this.profiles = profiles;
    }

    public Document tailorCv(long userId, long jobId, long cvDocumentId, String language) {
        Ctx ctx = load(userId, jobId, cvDocumentId);
        String lang = resolveLanguage(language, ctx);
        AiModels.TailoredCv cv = claude.structured(userId, Prompts.get("tailor-cv"),
                AiContext.job(ctx.job, ctx.company) + AiContext.cv(ctx.cv.extractedText()) + AiContext.profile(ctx.profile)
                        + "\nOutput language: " + lang + "\nCreate the tailored CV.",
                AiModels.TailoredCv.class, 12000);
        byte[] bytes = renderCv(ctx.profile, cv);
        String title = "CV – " + ctx.company.name() + " – " + shortTitle(ctx.job.title());
        return documents.saveGenerated(userId, "CV", title, fileName("CV", ctx.profile, ctx.company), bytes, cv,
                ctx.cv.id(), ctx.job.id(), cvToText(cv));
    }

    public Document coverLetter(long userId, long jobId, long cvDocumentId, String language, String notes) {
        Ctx ctx = load(userId, jobId, cvDocumentId);
        String lang = resolveLanguage(language, ctx);
        String notesBlock = notes == null || notes.isBlank() ? "" : Prompts.block("notes", Prompts.limit(notes, 2000));
        AiModels.CoverLetterDraft letter = claude.structured(userId, Prompts.get("cover-letter"),
                AiContext.job(ctx.job, ctx.company) + AiContext.cv(ctx.cv.extractedText()) + AiContext.profile(ctx.profile)
                        + notesBlock + "\nOutput language: " + lang + "\nWrite the cover letter.",
                AiModels.CoverLetterDraft.class, 6000);
        byte[] bytes = renderLetter(ctx.profile, ctx.company, letter);
        String title = "Anschreiben – " + ctx.company.name() + " – " + shortTitle(ctx.job.title());
        return documents.saveGenerated(userId, "COVER_LETTER", title, fileName("Anschreiben", ctx.profile, ctx.company), bytes,
                letter, ctx.cv.id(), ctx.job.id(), letterToText(letter));
    }

    /** Saves user edits to a generated document and re-renders the PDF. */
    public Document updateContent(long userId, long documentId, Map<String, Object> content) {
        Document d = documents.get(userId, documentId);
        if (d.original() || d.contentJson() == null) throw ApiException.badRequest("Only generated documents can be edited");
        UserProfile p = profiles.get(userId);
        if ("CV".equals(d.kind())) {
            AiModels.TailoredCv cv = Sql.JSON.convertValue(content, AiModels.TailoredCv.class);
            documents.replaceGenerated(d, renderCv(p, cv), cv, cvToText(cv));
        } else {
            AiModels.CoverLetterDraft l = Sql.JSON.convertValue(content, AiModels.CoverLetterDraft.class);
            Company c = d.jobId() == null ? null : jobs.findById(d.jobId()).flatMap(j -> companies.findById(j.companyId())).orElse(null);
            documents.replaceGenerated(d, renderLetter(p, c, l), l, letterToText(l));
        }
        return documents.get(userId, documentId);
    }

    // ---------------- rendering ----------------

    byte[] renderCv(UserProfile p, AiModels.TailoredCv cv) {
        boolean de = !"EN".equalsIgnoreCase(cv.language());
        Map<String, Object> vars = new HashMap<>();
        vars.put("de", de);
        vars.put("name", Optional.ofNullable(p.fullName()).orElse(""));
        vars.put("cv", Sql.JSON.convertValue(cv, new TypeReference<Map<String, Object>>() {}));
        vars.put("contact", Stream.of(p.email(), p.phone(), p.city(), p.linkedinUrl(), p.githubUrl(), p.websiteUrl())
                .filter(s -> s != null && !s.isBlank()).collect(Collectors.joining("  ·  ")));
        String langs = p.languages() == null ? "" : p.languages().stream()
                .map(l -> languageName(l, de) + " (" + l.level() + ")").collect(Collectors.joining(", "));
        boolean cvHasLanguageSection = cv.sections() != null && cv.sections().stream()
                .anyMatch(s -> s.title() != null && s.title().toLowerCase().matches(".*(sprach|language).*"));
        vars.put("languages", langs.isBlank() || cvHasLanguageSection ? null : langs);
        return pdf.pdf("cv", vars);
    }

    byte[] renderLetter(UserProfile p, Company c, AiModels.CoverLetterDraft l) {
        boolean de = !"EN".equalsIgnoreCase(l.language());
        Map<String, Object> vars = new HashMap<>();
        vars.put("de", de);
        vars.put("name", Optional.ofNullable(p.fullName()).orElse(""));
        vars.put("street", p.street());
        String postalCity = String.join(" ", Stream.of(p.postalCode(), p.city()).filter(s -> s != null && !s.isBlank()).toList());
        vars.put("postalCity", postalCity.isBlank() ? null : postalCity);
        vars.put("email", p.email());
        vars.put("phone", p.phone());
        vars.put("company", c == null ? "" : c.name());
        vars.put("companyCity", c == null ? null : c.hqCity());
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern(de ? "dd.MM.yyyy" : "d MMMM yyyy", de ? Locale.GERMANY : Locale.UK));
        vars.put("dateLine", (p.city() != null && !p.city().isBlank() ? p.city() + ", " : "") + date);
        vars.put("letter", Sql.JSON.convertValue(l, new TypeReference<Map<String, Object>>() {}));
        return pdf.pdf("cover-letter", vars);
    }

    // ---------------- helpers ----------------

    private record Ctx(UserProfile profile, JobPosting job, Company company, Document cv) {}

    private Ctx load(long userId, long jobId, long cvDocumentId) {
        UserProfile p = profiles.get(userId);
        AiConsent.require(p);
        JobPosting j = jobs.findById(jobId).orElseThrow(() -> ApiException.notFound("Job"));
        Company c = companies.findById(j.companyId()).orElseThrow();
        Document cv = documents.get(userId, cvDocumentId);
        if (!"CV".equals(cv.kind())) throw ApiException.badRequest("The selected document is not a CV");
        if (cv.extractedText() == null || cv.extractedText().isBlank()) throw ApiException.badRequest("The CV has no readable text");
        return new Ctx(p, j, c, cv);
    }

    private String resolveLanguage(String requested, Ctx ctx) {
        if (requested != null && (requested.equalsIgnoreCase("DE") || requested.equalsIgnoreCase("EN"))) return requested.toUpperCase();
        String pref = ctx.profile.preferences().withDefaults().coverLetterLanguage();
        if ("DE".equals(pref) || "EN".equals(pref)) return pref;
        return "EN".equals(ctx.job.postingLanguage()) ? "EN" : "DE";
    }

    private static String languageName(LanguageSkill l, boolean de) {
        String n = l.language() == null ? "" : l.language();
        if (!de) return n;
        return switch (n.toLowerCase()) {
            case "german" -> "Deutsch";
            case "english" -> "Englisch";
            case "french" -> "Französisch";
            case "spanish" -> "Spanisch";
            case "italian" -> "Italienisch";
            case "turkish" -> "Türkisch";
            case "albanian" -> "Albanisch";
            case "arabic" -> "Arabisch";
            case "russian" -> "Russisch";
            default -> n;
        };
    }

    private static String shortTitle(String t) {
        return t.length() > 60 ? t.substring(0, 57) + "..." : t;
    }

    private static String fileName(String prefix, UserProfile p, Company c) {
        String name = Optional.ofNullable(p.lastName()).filter(s -> !s.isBlank()).orElse("Bewerbung");
        String company = c.name().replaceAll("\\(.*?\\)", "").trim();
        return (prefix + "_" + name + "_" + company).replaceAll("[^A-Za-z0-9äöüÄÖÜß_-]+", "_") + ".pdf";
    }

    static String cvToText(AiModels.TailoredCv cv) {
        StringBuilder sb = new StringBuilder();
        append(sb, cv.headline());
        append(sb, cv.summary());
        if (cv.skills() != null) append(sb, String.join(", ", cv.skills()));
        if (cv.sections() != null) for (AiModels.CvSection s : cv.sections()) {
            append(sb, s.title());
            if (s.entries() != null) for (AiModels.CvEntry e : s.entries()) {
                append(sb, e.heading() + " | " + e.subheading() + " | " + e.period());
                if (e.bullets() != null) e.bullets().forEach(b -> append(sb, "• " + b));
            }
        }
        return sb.toString();
    }

    static String letterToText(AiModels.CoverLetterDraft l) {
        StringBuilder sb = new StringBuilder();
        append(sb, l.subject());
        append(sb, l.salutation());
        if (l.paragraphs() != null) l.paragraphs().forEach(p -> append(sb, p));
        append(sb, l.closing());
        return sb.toString();
    }

    private static void append(StringBuilder sb, String s) {
        if (s != null && !s.isBlank()) sb.append(s).append("\n\n");
    }
}
