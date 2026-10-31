package de.bwjobs.job;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ItFieldTaxonomyTest {

    @Test
    void titleHitsMakeItIt() {
        var r = ItFieldTaxonomy.classify("Werkstudent Backend-Entwicklung Java/Spring Boot", "");
        assertThat(r.isIt()).isTrue();
        assertThat(r.fields()).contains("BACKEND", "JAVA");
    }

    @Test
    void aiAndDataFields() {
        assertThat(ItFieldTaxonomy.classify("Abschlussarbeit Machine Learning für Predictive Maintenance", "").fields()).contains("AI_ML");
        assertThat(ItFieldTaxonomy.classify("Working Student Data Analytics", "").fields()).contains("DATA_SCIENCE");
        assertThat(ItFieldTaxonomy.classify("Praktikum Testautomatisierung", "").fields()).contains("QA_TESTING");
    }

    @Test
    void nonItDepartmentIsRejectedEvenWithSomeTools() {
        var r = ItFieldTaxonomy.classify("Werkstudent Marketing (m/w/d)", "Du arbeitest mit Excel, SAP und Jira im Marketing-Team.");
        assertThat(r.isIt()).isFalse();
        assertThat(r.fields()).isEmpty();
    }

    @Test
    void neutralTitleNeedsSeveralTechTermsInDescription() {
        var it = ItFieldTaxonomy.classify("Werkstudent (m/w/d) im Team Digital Solutions",
                "Du entwickelst Microservices mit Java und Spring Boot, nutzt Docker und Kubernetes und schreibst SQL.");
        assertThat(it.isIt()).isTrue();
        var notIt = ItFieldTaxonomy.classify("Werkstudent (m/w/d) im Team Operations", "Du unterstützt das Team bei der Planung.");
        assertThat(notIt.isIt()).isFalse();
    }

    @Test
    void techTermsDoNotConfuseJavaAndJavascript() {
        assertThat(ItFieldTaxonomy.techTerms("Kenntnisse in JavaScript und TypeScript")).contains("javascript", "typescript").doesNotContain("java");
        assertThat(ItFieldTaxonomy.techTerms("Erfahrung mit Java, C++ und Python")).contains("java", "c++", "python");
    }
}
