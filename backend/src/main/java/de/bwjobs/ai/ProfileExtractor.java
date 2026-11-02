package de.bwjobs.ai;

import de.bwjobs.common.ApiException;
import de.bwjobs.document.Document;
import de.bwjobs.document.DocumentService;
import de.bwjobs.job.ItFieldTaxonomy;
import de.bwjobs.profile.LanguageSkill;
import de.bwjobs.profile.ProfileRepository;
import de.bwjobs.profile.UserProfile;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Suggests profile fields from an uploaded CV. Uses Claude when configured and consented,
 * otherwise a rule-based fallback (skills dictionary + regexes) so the platform works without AI.
 */
@Service
public class ProfileExtractor {
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?:\\+49|0)[0-9 ()/-]{7,18}[0-9]");

    private final AiClient claude;
    private final DocumentService documents;
    private final ProfileRepository profiles;

    public ProfileExtractor(AiClient claude, DocumentService documents, ProfileRepository profiles) {
        this.claude = claude;
        this.documents = documents;
        this.profiles = profiles;
    }

    public record Suggestion(String source, AiModels.ExtractedProfile profile) {}

    public Suggestion suggest(long userId, long documentId) {
        Document d = documents.get(userId, documentId);
        if (d.extractedText() == null || d.extractedText().isBlank()) throw ApiException.badRequest("This document has no readable text");
        UserProfile p = profiles.get(userId);
        if (claude.enabled() && p.aiConsent()) {
            AiModels.ExtractedProfile ai = claude.structured(userId, Prompts.get("profile-extract"),
                    AiContext.cv(d.extractedText()) + "\nExtract the profile.", AiModels.ExtractedProfile.class, 4000);
            return new Suggestion("AI", ai);
        }
        return new Suggestion("RULES", ruleBased(d.extractedText()));
    }

    /** Applies non-empty suggested fields to the profile; never overwrites with blanks. */
    public UserProfile apply(long userId, AiModels.ExtractedProfile s) {
        UserProfile p = profiles.get(userId);
        Set<String> skills = new LinkedHashSet<>(p.skills() == null ? List.of() : p.skills());
        if (s.skills() != null) skills.addAll(s.skills());
        List<LanguageSkill> langs = new ArrayList<>(p.languages() == null ? List.of() : p.languages());
        if (s.languages() != null) {
            for (AiModels.LanguageEntry l : s.languages()) {
                if (l.language() == null || l.language().isBlank()) continue;
                langs.removeIf(x -> x.language().equalsIgnoreCase(l.language()));
                langs.add(new LanguageSkill(l.language(), l.level()));
            }
        }
        UserProfile updated = new UserProfile(p.userId(), pick(s.fullName(), p.fullName()), pick(s.email(), p.email()),
                pick(s.phone(), p.phone()), p.street(), p.postalCode(), pick(s.city(), p.city()), p.linkedinUrl(), p.githubUrl(),
                p.websiteUrl(), pick(s.university(), p.university()), pick(s.studyProgram(), p.studyProgram()),
                pick(s.degree(), p.degree()), s.semester() > 0 ? Integer.valueOf(s.semester()) : p.semester(), p.availableFrom(),
                p.hoursPerWeek(), new ArrayList<>(skills), langs, pick(s.summary(), p.summary()), p.preferences(), p.aiConsent());
        profiles.update(updated);
        return profiles.get(userId);
    }

    static AiModels.ExtractedProfile ruleBased(String text) {
        Matcher em = EMAIL.matcher(text);
        Matcher ph = PHONE.matcher(text);
        List<String> skills = new ArrayList<>(ItFieldTaxonomy.techTerms(text));
        List<AiModels.LanguageEntry> langs = new ArrayList<>();
        String lower = text.toLowerCase(Locale.GERMAN);
        langLevel(lower, "deutsch|german", "German").ifPresent(langs::add);
        langLevel(lower, "englisch|english", "English").ifPresent(langs::add);
        return new AiModels.ExtractedProfile("", em.find() ? em.group() : "", ph.find() ? ph.group().trim() : "", "", "", "", "", 0,
                skills, langs, "");
    }

    private static Optional<AiModels.LanguageEntry> langLevel(String text, String names, String language) {
        // Context ends at the next list separator so "Deutsch: B2, Albanisch: Muttersprache" stays B2
        Matcher m = Pattern.compile("(?:" + names + ")[^\\n,;|•/]{0,40}").matcher(text);
        if (!m.find()) return Optional.empty();
        String ctx = m.group();
        String level = ctx.matches(".*(muttersprach|native).*") ? "NATIVE"
                : ctx.matches(".*\\b(c2)\\b.*") ? "C2"
                : ctx.matches(".*(\\bc1\\b|fließend|fliessend|fluent|verhandlungssicher|sehr gut).*") ? "C1"
                : ctx.matches(".*(\\bb2\\b|gut|good).*") ? "B2"
                : ctx.matches(".*\\bb1\\b.*") ? "B1"
                : ctx.matches(".*(\\ba2\\b|grundkenntnisse|basic).*") ? "A2" : null;
        return level == null ? Optional.empty() : Optional.of(new AiModels.LanguageEntry(language, level));
    }

    private static String pick(String suggested, String current) {
        return suggested != null && !suggested.isBlank() ? suggested.trim() : current;
    }
}
