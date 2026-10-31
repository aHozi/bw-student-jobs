package de.bwjobs.job;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BwLocationMatcherTest {

    @Test
    void bwCitiesAndPostalCodes() {
        assertThat(BwLocationMatcher.match(List.of("Stuttgart, Baden-Württemberg, Germany"), null).inBw()).isTrue();
        assertThat(BwLocationMatcher.match(List.of("76131 Karlsruhe"), null).inBw()).isTrue();
        assertThat(BwLocationMatcher.match(List.of("Walldorf, DE"), null).inBw()).isTrue();
        assertThat(BwLocationMatcher.match(List.of("Leinfelden-Echterdingen"), null).inBw()).isTrue();
        assertThat(BwLocationMatcher.match(List.of("71069 Sindelfingen"), null).inBw()).isTrue();
    }

    @Test
    void outsideBw() {
        assertThat(BwLocationMatcher.match(List.of("München, Bayern"), null).inBw()).isFalse();
        assertThat(BwLocationMatcher.match(List.of("Neu-Ulm"), null).inBw()).isFalse();
        assertThat(BwLocationMatcher.match(List.of("Bangalore, India"), null).inBw()).isFalse();
        assertThat(BwLocationMatcher.match(List.of("10115 Berlin"), null).inBw()).isFalse();
    }

    @Test
    void multipleLocationsAnyInBw() {
        assertThat(BwLocationMatcher.match(List.of("Berlin", "Stuttgart"), null).inBw()).isTrue();
    }

    @Test
    void remoteAndUnknown() {
        var r = BwLocationMatcher.match(List.of("Remote, Germany"), null);
        assertThat(r.remote()).isTrue();
        assertThat(r.inBw()).isNull();
    }
}
