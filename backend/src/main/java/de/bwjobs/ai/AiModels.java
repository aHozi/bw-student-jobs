package de.bwjobs.ai;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** Structured output schemas for Claude. The JSON schema is derived from these records. */
public final class AiModels {
    private AiModels() {}

    public record LanguageEntry(
            @JsonPropertyDescription("Language name in English, e.g. German, English") String language,
            @JsonPropertyDescription("One of A1, A2, B1, B2, C1, C2, NATIVE") String level) {}

    public record ExtractedProfile(
            @JsonPropertyDescription("Full name, empty if not found") String fullName,
            @JsonPropertyDescription("E-mail, empty if not found") String email,
            @JsonPropertyDescription("Phone, empty if not found") String phone,
            @JsonPropertyDescription("City of residence, empty if not found") String city,
            @JsonPropertyDescription("Current university, empty if not found") String university,
            @JsonPropertyDescription("Current study program, e.g. 'Informatik (B.Sc.)'") String studyProgram,
            @JsonPropertyDescription("Degree being pursued, e.g. Bachelor, Master") String degree,
            @JsonPropertyDescription("Current semester number, 0 if unknown") int semester,
            @JsonPropertyDescription("Technical skills, tools and programming languages, max 40") List<String> skills,
            List<LanguageEntry> languages,
            @JsonPropertyDescription("Two-sentence neutral profile summary in English") String summary) {}

    public record MatchAssessment(
            @JsonPropertyDescription("Fit score 0-100 for this student job") int score,
            @JsonPropertyDescription("One or two sentences explaining the score") String summary,
            @JsonPropertyDescription("Concrete strengths of the candidate for this role, max 5") List<String> strengths,
            @JsonPropertyDescription("Missing skills or requirements, max 5") List<String> gaps,
            @JsonPropertyDescription("Whether the candidate's language skills meet the posting's language requirements") String languageAssessment,
            @JsonPropertyDescription("Short advice: apply / apply with tailored CV / probably skip, and why") String recommendation) {}

    public record CvEntry(
            @JsonPropertyDescription("Role, degree or project name") String heading,
            @JsonPropertyDescription("Organisation and place") String subheading,
            @JsonPropertyDescription("Time period exactly as in the original CV") String period,
            List<String> bullets) {}

    public record CvSection(
            @JsonPropertyDescription("Section title, e.g. Berufserfahrung / Experience") String title,
            List<CvEntry> entries) {}

    public record TailoredCv(
            @JsonPropertyDescription("DE or EN") String language,
            @JsonPropertyDescription("Short headline under the name, e.g. 'Informatikstudent (B.Sc.) mit Fokus Backend'") String headline,
            @JsonPropertyDescription("Profile summary, 2-4 sentences, tailored to the job") String summary,
            @JsonPropertyDescription("Skills ordered by relevance for the job, only skills present in the original CV") List<String> skills,
            List<CvSection> sections,
            @JsonPropertyDescription("List of what was changed compared to the original CV, for the user to review") List<String> changes) {}

    public record CoverLetterDraft(
            @JsonPropertyDescription("DE or EN") String language,
            @JsonPropertyDescription("Subject line, e.g. 'Bewerbung als Werkstudent Softwareentwicklung (m/w/d)'") String subject,
            @JsonPropertyDescription("Salutation line, e.g. 'Sehr geehrte Damen und Herren,'") String salutation,
            @JsonPropertyDescription("3-5 body paragraphs") List<String> paragraphs,
            @JsonPropertyDescription("Closing, e.g. 'Mit freundlichen Grüßen'") String closing) {}

    public record LanguageAnalysis(
            @JsonPropertyDescription("German requirement: NONE, NICE_TO_HAVE, B1, B2, C1_FLUENT, NATIVE or UNKNOWN") String germanLevel,
            @JsonPropertyDescription("English requirement: NONE, NICE_TO_HAVE, GOOD, FLUENT or UNKNOWN") String englishLevel) {}
}
