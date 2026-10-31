package de.bwjobs.application;

import java.util.Set;

public final class ApplicationStatus {
    private ApplicationStatus() {}

    public static final String DRAFT = "DRAFT";
    public static final String PREPARING = "PREPARING";
    public static final String READY_FOR_REVIEW = "READY_FOR_REVIEW";
    public static final String MANUAL_REQUIRED = "MANUAL_REQUIRED";
    public static final String APPROVED = "APPROVED";
    public static final String SUBMITTING = "SUBMITTING";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String FAILED = "FAILED";
    public static final String INTERVIEW = "INTERVIEW";
    public static final String OFFER = "OFFER";
    public static final String REJECTED = "REJECTED";
    public static final String WITHDRAWN = "WITHDRAWN";

    /** Statuses where the user really applied (used for duplicate detection). */
    public static final Set<String> APPLIED = Set.of(APPROVED, SUBMITTING, SUBMITTED, INTERVIEW, OFFER, REJECTED);

    /** Statuses a user can set by hand to track progress after applying. */
    public static final Set<String> TRACKING = Set.of(SUBMITTED, INTERVIEW, OFFER, REJECTED, WITHDRAWN);
}
