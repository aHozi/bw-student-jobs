package de.bwjobs.ai;

import de.bwjobs.common.CurrentUser;
import de.bwjobs.document.Document;
import de.bwjobs.profile.UserProfile;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AiController {
    private final MatchScorer scorer;
    private final TailoringService tailoring;
    private final ProfileExtractor extractor;
    private final ClaudeClient claude;
    private final AiQuota quota;

    public AiController(MatchScorer scorer, TailoringService tailoring, ProfileExtractor extractor, ClaudeClient claude, AiQuota quota) {
        this.scorer = scorer;
        this.tailoring = tailoring;
        this.extractor = extractor;
        this.claude = claude;
        this.quota = quota;
    }

    @GetMapping("/ai/status")
    public Map<String, Object> status() {
        long uid = CurrentUser.id();
        return Map.of("enabled", claude.enabled(), "model", claude.model(), "usedToday", quota.usedToday(uid), "dailyLimit", quota.limit());
    }

    @PostMapping("/jobs/{id}/ai-score")
    public AiModels.MatchAssessment aiScore(@PathVariable long id) {
        return scorer.score(CurrentUser.id(), id);
    }

    public record TailorRequest(long cvDocumentId, String language, String notes) {}

    @PostMapping("/jobs/{id}/tailor-cv")
    public Document tailorCv(@PathVariable long id, @RequestBody TailorRequest req) {
        return tailoring.tailorCv(CurrentUser.id(), id, req.cvDocumentId(), req.language());
    }

    @PostMapping("/jobs/{id}/cover-letter")
    public Document coverLetter(@PathVariable long id, @RequestBody TailorRequest req) {
        return tailoring.coverLetter(CurrentUser.id(), id, req.cvDocumentId(), req.language(), req.notes());
    }

    @PutMapping("/documents/{id}/content")
    public Document updateContent(@PathVariable long id, @RequestBody Map<String, Object> content) {
        return tailoring.updateContent(CurrentUser.id(), id, content);
    }

    @PostMapping("/profile/extract/{documentId}")
    public ProfileExtractor.Suggestion extract(@PathVariable long documentId) {
        return extractor.suggest(CurrentUser.id(), documentId);
    }

    @PostMapping("/profile/apply-extraction")
    public UserProfile applyExtraction(@RequestBody AiModels.ExtractedProfile suggestion) {
        return extractor.apply(CurrentUser.id(), suggestion);
    }
}
