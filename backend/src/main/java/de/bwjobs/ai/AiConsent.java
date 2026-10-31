package de.bwjobs.ai;

import de.bwjobs.common.ApiException;
import de.bwjobs.profile.UserProfile;
import org.springframework.http.HttpStatus;

/** AI features send CV data to the Claude API, so the user must opt in first (GDPR). */
final class AiConsent {
    private AiConsent() {}

    static void require(UserProfile p) {
        if (!p.aiConsent()) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Please allow AI processing of your documents in Settings first. Your CV is then sent to the Claude API to generate results.");
        }
    }
}
