package de.bwjobs.company;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AtsDetectorTest {

    @Test
    void detectsFromUrls() {
        assertThat(AtsDetector.fromUrl("https://jobs.smartrecruiters.com/BoschGroup/7439").get())
                .extracting(AtsDetector.Detection::type, AtsDetector.Detection::tenant).containsExactly(AtsType.SMARTRECRUITERS, "BoschGroup");
        assertThat(AtsDetector.fromUrl("https://acme.jobs.personio.de/job/123").get().tenant()).isEqualTo("acme.jobs.personio.de");
        assertThat(AtsDetector.fromUrl("https://boards.greenhouse.io/acme").get().tenant()).isEqualTo("acme");
        assertThat(AtsDetector.fromUrl("https://boards.greenhouse.io/embed/job_board?for=acme2").get().tenant()).isEqualTo("acme2");
        assertThat(AtsDetector.fromUrl("https://jobs.eu.lever.co/acme").get().tenant()).isEqualTo("eu/acme");
        assertThat(AtsDetector.fromUrl("https://acme.wd3.myworkdayjobs.com/de-DE/Acme_Careers").get().tenant())
                .isEqualTo("acme.wd3.myworkdayjobs.com|acme|Acme_Careers");
        assertThat(AtsDetector.fromUrl("https://acme.recruitee.com/").get().type()).isEqualTo(AtsType.RECRUITEE);
        assertThat(AtsDetector.fromUrl("https://jobs.sap.com/search/?q=").get().type()).isEqualTo(AtsType.SUCCESSFACTORS);
        assertThat(AtsDetector.fromUrl("https://www.example.de/karriere")).isEmpty();
    }

    @Test
    void detectsEmbeddedAtsInCareerPage() {
        String html = """
                <html><body><h1>Karriere</h1>
                <iframe src="https://example-gmbh.jobs.personio.de/?display=de"></iframe></body></html>""";
        var d = AtsDetector.fromPage("https://www.example.de/karriere", html).get();
        assertThat(d.type()).isEqualTo(AtsType.PERSONIO);
        assertThat(d.tenant()).isEqualTo("example-gmbh.jobs.personio.de");
    }

    @Test
    void detectsSuccessFactorsCareerSiteBuilderOnOwnDomain() {
        String html = "<html><body><a class=\"jobTitle-link\" href=\"/job/Stuttgart-Werkstudent/123/\">Werkstudent</a></body></html>";
        var d = AtsDetector.fromPage("https://karriere.example.de/search/", html).get();
        assertThat(d.type()).isEqualTo(AtsType.SUCCESSFACTORS);
        assertThat(d.tenant()).isEqualTo("https://karriere.example.de");
    }

    @Test
    void jsonLdMeansGeneric() {
        String html = "<script type=\"application/ld+json\">{\"@type\":\"JobPosting\",\"title\":\"x\"}</script>";
        assertThat(AtsDetector.fromPage("https://www.example.de/jobs", html).get().type()).isEqualTo(AtsType.GENERIC);
    }
}
