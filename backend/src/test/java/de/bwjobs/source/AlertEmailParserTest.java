package de.bwjobs.source;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Synthetic alert e-mails modelled on the platforms' layouts; real e-mails should be added as fixtures when available. */
class AlertEmailParserTest {

    static String fixture(String name) throws Exception {
        try (var in = AlertEmailParserTest.class.getResourceAsStream("/fixtures/alerts/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void platformFromSender() {
        assertThat(AlertEmailParser.platformOf("jobalerts-noreply@linkedin.com")).isEqualTo("LINKEDIN");
        assertThat(AlertEmailParser.platformOf("Jobagent <jobagent@stepstone.de>")).isEqualTo("STEPSTONE");
        assertThat(AlertEmailParser.platformOf("alert@indeed.com")).isEqualTo("INDEED");
        assertThat(AlertEmailParser.platformOf("news@example.org")).isNull();
    }

    @Test
    void linkedin() throws Exception {
        List<AlertEmailParser.ParsedJob> jobs = AlertEmailParser.parse("LINKEDIN", fixture("linkedin.html"));
        assertThat(jobs).hasSize(3); // the senior job is filtered later by the student classifier
        AlertEmailParser.ParsedJob bosch = jobs.getFirst();
        assertThat(bosch.title()).isEqualTo("Werkstudent Softwareentwicklung Java (m/w/d)");
        assertThat(bosch.company()).isEqualTo("Robert Bosch GmbH");
        assertThat(bosch.location()).isEqualTo("Stuttgart, Baden-Württemberg, Deutschland");
        assertThat(bosch.url()).isEqualTo("https://www.linkedin.com/jobs/view/4012345678/");
        assertThat(bosch.externalId()).isEqualTo("4012345678");
        assertThat(jobs.get(1).company()).isEqualTo("SAP");
        assertThat(jobs.get(1).location()).isEqualTo("Walldorf, Baden-Württemberg, Germany");
    }

    @Test
    void stepstoneWithTrackingRedirect() throws Exception {
        List<AlertEmailParser.ParsedJob> jobs = AlertEmailParser.parse("STEPSTONE", fixture("stepstone.html"));
        assertThat(jobs).extracting(AlertEmailParser.ParsedJob::title)
                .containsExactly("Praktikum Informatik (m/w/d)", "Werkstudent Cloud & DevOps (m/w/d)");
        assertThat(jobs.getFirst().company()).isEqualTo("Porsche AG");
        assertThat(jobs.getFirst().location()).isEqualTo("Stuttgart");
        assertThat(jobs.getFirst().url()).isEqualTo(
                "https://www.stepstone.de/stellenangebote--Praktikum-Informatik-m-w-d-Stuttgart-Porsche-AG-10123456-inline.html"
                        .replace("Porsche-AG-10123456", "Porsche-AG--10123456"));
        assertThat(jobs.get(1).company()).isEqualTo("dm-drogerie markt GmbH + Co. KG");
    }

    @Test
    void indeedIncludingWrappedLinks() throws Exception {
        List<AlertEmailParser.ParsedJob> jobs = AlertEmailParser.parse("INDEED", fixture("indeed.html"));
        assertThat(jobs).hasSize(2);
        assertThat(jobs.getFirst().title()).isEqualTo("Praktikum Data Engineering (m/w/d)");
        assertThat(jobs.getFirst().company()).isEqualTo("Mercedes-Benz Group AG");
        assertThat(jobs.getFirst().location()).isEqualTo("71059 Sindelfingen");
        assertThat(jobs.getFirst().url()).isEqualTo("https://de.indeed.com/viewjob?jk=a1b2c3d4e5f6a7b8");
        assertThat(jobs.get(1).externalId()).isEqualTo("0fedcba987654321");
        assertThat(jobs.get(1).company()).isEqualTo("Schwarz IT KG");
    }

    @Test
    void unknownLayoutFallsBackToStudentLinks() {
        String html = "<p><a href='https://www.linkedin.com/x/redirect?id=9'>Werkstudent Frontend React (m/w/d)</a></p><p>Acme GmbH</p>"
                + "<p><a href='https://www.linkedin.com/settings'>Settings</a></p>";
        List<AlertEmailParser.ParsedJob> jobs = AlertEmailParser.parse("LINKEDIN", html);
        assertThat(jobs).extracting(AlertEmailParser.ParsedJob::title).containsExactly("Werkstudent Frontend React (m/w/d)");
    }
}
