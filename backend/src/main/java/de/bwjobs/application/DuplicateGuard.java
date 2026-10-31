package de.bwjobs.application;

import de.bwjobs.common.ApiException;
import de.bwjobs.common.Text;
import de.bwjobs.job.JobPosting;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Prevents duplicate applications:
 * - hard block: an application for the same job already exists (also enforced by a DB unique key);
 * - soft block: the user already applied at this company for a very similar title (e.g. a repost).
 *   The user must confirm explicitly to continue.
 */
@Component
public class DuplicateGuard {
    private final ApplicationRepository apps;

    public DuplicateGuard(ApplicationRepository apps) {
        this.apps = apps;
    }

    public void check(long userId, JobPosting job, boolean confirmSimilar) {
        apps.findByUserAndJob(userId, job.id()).ifPresent(existing -> {
            throw ApiException.conflict("You already have an application for this job",
                    Map.of("type", "SAME_JOB", "applicationId", existing.id(), "status", existing.status()));
        });
        if (confirmSimilar) return;
        List<ApplicationRepository.Similar> similar = apps.appliedAtCompany(userId, job.companyId(), job.id()).stream()
                .filter(s -> similarity(s.title(), job.title()) >= 0.75).toList();
        if (!similar.isEmpty()) {
            throw ApiException.conflict("You recently applied for a very similar position at this company",
                    Map.of("type", "SIMILAR_AT_COMPANY", "similar", similar));
        }
    }

    /** Jaccard similarity of normalized title words. */
    static double similarity(String a, String b) {
        Set<String> x = new HashSet<>(Arrays.asList(Text.normalizeTitle(a).split(" ")));
        Set<String> y = new HashSet<>(Arrays.asList(Text.normalizeTitle(b).split(" ")));
        x.removeIf(String::isBlank);
        y.removeIf(String::isBlank);
        if (x.isEmpty() || y.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return (double) inter.size() / union.size();
    }
}
