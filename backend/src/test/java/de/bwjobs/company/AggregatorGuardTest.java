package de.bwjobs.company;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AggregatorGuardTest {
    private final AggregatorGuard guard = new AggregatorGuard();

    @Test
    void blocksJobBoards() {
        assertThat(guard.isAggregator("https://www.linkedin.com/jobs/view/123")).isTrue();
        assertThat(guard.isAggregator("https://de.linkedin.com/jobs")).isTrue();
        assertThat(guard.isAggregator("https://www.stepstone.de/stellenangebote--Werkstudent")).isTrue();
        assertThat(guard.isAggregator("https://de.indeed.com/viewjob?jk=1")).isTrue();
        assertThat(guard.isAggregator("https://www.xing.com/jobs/x")).isTrue();
        assertThat(guard.isAggregator("www.glassdoor.de/job")).isTrue();
        assertThat(guard.isAggregator("https://www.arbeitsagentur.de/jobsuche/")).isTrue();
    }

    @Test
    void allowsOfficialPagesAndAtsTenants() {
        assertThat(guard.isAggregator("https://jobs.sap.com/job/123")).isFalse();
        assertThat(guard.isAggregator("https://jobs.smartrecruiters.com/BoschGroup/1")).isFalse();
        assertThat(guard.isAggregator("https://acme.jobs.personio.de/job/1")).isFalse();
        assertThat(guard.isAggregator("https://www.notlinkedin.com/")).isFalse();
    }
}
