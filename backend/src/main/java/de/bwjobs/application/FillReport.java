package de.bwjobs.application;

import java.util.List;
import java.util.Map;

/** What the form filler found and filled. Shown to the user before they approve submission. */
public record FillReport(
        String url,
        List<Field> fields,
        boolean captcha,
        boolean loginRequired,
        boolean submitButtonFound,
        int unresolvedRequired,
        String message,
        EmailDraft emailDraft) {

    /** status: FILLED, FILE, NEEDS_INPUT, CONSENT, SKIPPED */
    public record Field(String key, String label, String type, boolean required, String status, String value,
                        List<String> options) {}

    public record EmailDraft(String to, String subject, String body, List<Long> attachmentDocumentIds) {}

    public static FillReport manual(String url, String message) {
        return new FillReport(url, List.of(), false, false, false, 0, message, null);
    }

    public Map<String, Object> summary() {
        long filled = fields.stream().filter(f -> f.status().equals("FILLED") || f.status().equals("FILE")).count();
        return Map.of("filled", filled, "needsInput", unresolvedRequired, "total", fields.size());
    }
}
