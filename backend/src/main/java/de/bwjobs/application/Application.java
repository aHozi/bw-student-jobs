package de.bwjobs.application;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record Application(
        long id, long userId, long jobId, long companyId, String status, String mode, Long cvDocumentId, Long coverLetterId,
        List<Long> extraDocumentIds, boolean usedOriginalDocs, FillReport fillReport, Map<String, String> overrides,
        List<String> consents, @JsonIgnore String screenshotKey, @JsonIgnore String confirmationKey, String manualReason,
        String errorMessage, String notes, LocalDate followUpOn, Instant approvedAt, Instant submittedAt, Instant createdAt,
        Instant updatedAt) {

    @JsonProperty("hasScreenshot")
    public boolean hasScreenshot() { return screenshotKey != null; }

    @JsonProperty("hasConfirmation")
    public boolean hasConfirmation() { return confirmationKey != null; }
}
