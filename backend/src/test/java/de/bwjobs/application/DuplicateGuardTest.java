package de.bwjobs.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateGuardTest {
    @Test
    void similarTitlesAreDetected() {
        assertThat(DuplicateGuard.similarity("Werkstudent Softwareentwicklung (m/w/d)", "Werkstudent Softwareentwicklung (w/m/d)")).isEqualTo(1.0);
        assertThat(DuplicateGuard.similarity("Werkstudent Java Backend (m/w/d)", "Werkstudent Java Backend - Stuttgart")).isGreaterThanOrEqualTo(0.75);
        assertThat(DuplicateGuard.similarity("Werkstudent Java Backend", "Praktikum Data Science")).isLessThan(0.3);
    }
}
