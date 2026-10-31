package de.bwjobs.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageRequirementExtractorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Sehr gute Deutsch- und Englischkenntnisse in Wort und Schrift.                  | C1_FLUENT    | FLUENT",
            "Fließende Deutschkenntnisse, Englisch von Vorteil.                              | C1_FLUENT    | NICE_TO_HAVE",
            "Gute Deutschkenntnisse (mindestens B2) und gute Englischkenntnisse.             | B2           | GOOD",
            "Deutsch auf Muttersprachniveau.                                                  | NATIVE       | UNKNOWN",
            "Grundkenntnisse in Deutsch (B1).                                                 | B1           | UNKNOWN",
            "Fluent English is required. German is a plus.                                    | NICE_TO_HAVE | FLUENT",
            "Very good English skills; German language skills are not required.              | NONE         | FLUENT",
            "Our working language is English.                                                 | NONE         | FLUENT",
            "Verhandlungssicheres Deutsch sowie gute Englischkenntnisse.                     | C1_FLUENT    | GOOD",
    })
    void extractsLevels(String text, String german, String english) {
        var r = LanguageRequirementExtractor.extract("Werkstudent Software", "Dein Profil:\n" + text);
        assertThat(r.german().name()).as("german").isEqualTo(german);
        assertThat(r.english().name()).as("english").isEqualTo(english);
    }

    @Test
    void sentencesAreSplitOnLineBreaks() {
        String text = "Was du mitbringst\n• Erfahrung mit Java\n• Sehr gute Deutschkenntnisse\n• Englisch ist ein Plus";
        var r = LanguageRequirementExtractor.extract("Werkstudent", text);
        assertThat(r.german()).isEqualTo(GermanLevel.C1_FLUENT);
        assertThat(r.english()).isEqualTo(EnglishLevel.NICE_TO_HAVE);
    }

    @Test
    void unknownWhenNotMentioned_andPostingLanguageDetected() {
        var de = LanguageRequirementExtractor.extract("Werkstudent", "Wir suchen dich für unser Team und du hast Spaß an der Entwicklung mit Java.");
        assertThat(de.german()).isEqualTo(GermanLevel.UNKNOWN);
        assertThat(de.postingLanguage()).isEqualTo("DE");
        var en = LanguageRequirementExtractor.extract("Working Student", "We are looking for you to join our team and you will work with Java.");
        assertThat(en.postingLanguage()).isEqualTo("EN");
        assertThat(en.english()).isEqualTo(EnglishLevel.GOOD);
    }

    @Test
    void germanyIsNotGerman() {
        var r = LanguageRequirementExtractor.extract("Working Student", "Our office is located in Stuttgart, Germany. You speak fluent English.");
        assertThat(r.german()).isEqualTo(GermanLevel.UNKNOWN);
        assertThat(r.english()).isEqualTo(EnglishLevel.FLUENT);
    }
}
