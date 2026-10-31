package de.bwjobs.ai;

import de.bwjobs.company.Company;
import de.bwjobs.job.JobPosting;
import de.bwjobs.profile.UserProfile;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the data blocks sent to Claude. Only fields needed for the task are included. */
final class AiContext {
    private AiContext() {}

    static String job(JobPosting j, Company c) {
        String body = "Title: " + j.title() + "\n"
                + "Company: " + c.name() + (c.industry() != null ? " (" + c.industry() + ")" : "") + "\n"
                + "Locations: " + String.join("; ", j.locations()) + (j.remote() ? " (remote possible)" : "") + "\n"
                + "Student job type: " + String.join(", ", j.studentTypes()) + "\n"
                + "Posting language: " + (j.postingLanguage() == null ? "unknown" : j.postingLanguage()) + "\n\n"
                + Prompts.limit(j.descriptionText(), 14_000);
        return Prompts.block("job_posting", body);
    }

    static String profile(UserProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fullName", p.fullName());
        m.put("city", p.city());
        m.put("university", p.university());
        m.put("studyProgram", p.studyProgram());
        m.put("degree", p.degree());
        m.put("semester", p.semester());
        m.put("availableFrom", p.availableFrom());
        m.put("hoursPerWeek", p.hoursPerWeek());
        m.put("skills", p.skills());
        m.put("languages", p.languages());
        m.put("summary", p.summary());
        m.values().removeIf(v -> v == null || (v instanceof String s && s.isBlank()));
        return Prompts.block("profile", de.bwjobs.common.Sql.toJson(m));
    }

    static String cv(String text) {
        return Prompts.block("cv", Prompts.limit(text, 20_000));
    }
}
