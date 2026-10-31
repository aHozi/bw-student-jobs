package de.bwjobs.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileExtractorTest {
    @Test
    void ruleBasedReadsEachLanguageSeparately() {
        var p = ProfileExtractor.ruleBased("""
                Lea Becker, lea@example.org, +49 151 23456789
                Kenntnisse: Java, Spring Boot, SQL, Docker
                Sprachen: Englisch: C1 (fließend), Deutsch: B2 (gut), Albanisch: Muttersprache""");
        assertThat(p.languages()).extracting(AiModels.LanguageEntry::language, AiModels.LanguageEntry::level)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("German", "B2"), org.assertj.core.groups.Tuple.tuple("English", "C1"));
        assertThat(p.skills()).contains("java", "spring boot", "sql", "docker");
        assertThat(p.email()).isEqualTo("lea@example.org");
        assertThat(p.phone()).startsWith("+49 151");
    }

    @Test
    void nativeGerman() {
        var p = ProfileExtractor.ruleBased("Deutsch (Muttersprache)\nEnglisch (B2)");
        assertThat(p.languages()).extracting(AiModels.LanguageEntry::level).containsExactlyInAnyOrder("NATIVE", "B2");
    }
}
