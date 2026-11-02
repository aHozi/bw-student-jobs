package de.bwjobs.it;

import com.fasterxml.jackson.databind.JsonNode;
import de.bwjobs.application.Application;
import de.bwjobs.application.ApplicationRepository;
import de.bwjobs.application.ApplicationRunner;
import de.bwjobs.common.Sql;
import de.bwjobs.source.ExternalJob;
import de.bwjobs.source.ExternalJobIngest;
import de.bwjobs.source.SourceScheduler;
import de.bwjobs.support.MockSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Bundesagentur + Adzuna (mock APIs), merging with e-mail-alert jobs, visibility and manual apply on job boards. */
class SourcesIT extends IntegrationTestBase {
    static final MockSite API;

    static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static {
        try {
            API = new MockSite();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        // Bundesagentur search (spec: /pc/v6/jobs) - the same answer for every term/place, deduplicated by refnr
        API.json("/ba/pc/v6/jobs", """
                {"stellenangebote":[
                  {"refnr":"10000-1111111111-S","beruf":"Softwareentwickler/in","arbeitgeber":"Robert Bosch GmbH",
                   "arbeitsort":{"plz":"70469","ort":"Stuttgart","region":"Baden-Württemberg","land":"Deutschland"},
                   "aktuelleVeroeffentlichungsdatum":"2026-10-28"},
                  {"refnr":"10000-2222222222-S","titel":"Senior Softwarearchitekt (m/w/d)","arbeitgeber":"Acme GmbH",
                   "arbeitsort":{"plz":"76131","ort":"Karlsruhe"}},
                  {"refnr":"10000-3333333333-S","beruf":"Informatiker/in","arbeitgeber":"Neckar Software GmbH",
                   "arbeitsort":{"plz":"69117","ort":"Heidelberg","region":"Baden-Württemberg"},
                   "externeUrl":"https://karriere.neckar-software.example/jobs/42"}
                ],"maxErgebnisse":"3","page":"1","size":"100"}""");
        API.json("/ba/pc/v4/jobdetails/" + b64("10000-1111111111-S"), """
                {"stellenangebotsTitel":"Werkstudent Softwareentwicklung Java (m/w/d)","arbeitgeber":"Robert Bosch GmbH",
                 "stellenangebotsBeschreibung":"Deine Aufgaben:\\nDu entwickelst Services mit Java und Spring Boot.\\n\\nDein Profil:\\nGute Deutschkenntnisse (B2) und gute Englischkenntnisse.\\nErfahrung mit Docker und SQL."}""");
        API.json("/ba/pc/v4/jobdetails/" + b64("10000-3333333333-S"), """
                {"titel":"Praktikum Data Science (m/w/d)","stellenangebotsBeschreibung":"Python, Pandas und Machine Learning. Englisch fließend, Deutsch von Vorteil."}""");
        // Adzuna search
        API.json("/adzuna/1", """
                {"count":1,"results":[{"id":"4455667788","title":"<strong>Werkstudent</strong> Frontend React (m/w/d)",
                  "description":"Du entwickelst Oberflächen mit React und TypeScript...","redirect_url":"https://www.adzuna.de/land/ad/4455667788?se=x",
                  "created":"2026-10-30T08:00:00Z","company":{"display_name":"ETAS GmbH"},
                  "location":{"display_name":"Stuttgart, Baden-Württemberg","area":["Deutschland","Baden-Württemberg","Stuttgart"]}}]}""");
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry r) {
        r.add("app.sources.arbeitsagentur.base-url", () -> API.base() + "/ba");
        r.add("app.sources.arbeitsagentur.terms", () -> "Werkstudent Informatik,Praktikum Informatik");
        r.add("app.sources.arbeitsagentur.places", () -> "Stuttgart");
        r.add("app.sources.adzuna.base-url", () -> API.base() + "/adzuna");
        r.add("app.sources.adzuna.app-id", () -> "test-id");
        r.add("app.sources.adzuna.app-key", () -> "test-key");
        r.add("app.sources.adzuna.terms", () -> "werkstudent informatik");
    }

    @AfterAll
    static void stop() {
        API.close();
    }

    @Autowired SourceScheduler scheduler;
    @Autowired ExternalJobIngest ingest;
    @Autowired ApplicationRunner runner;
    @Autowired ApplicationRepository apps;

    @Test
    void importsMergesAndKeepsAlertJobsPrivate() throws Exception {
        var ba = scheduler.runArbeitsagentur();
        assertThat(ba.accepted()).isEqualTo(2); // senior job skipped before the detail call
        assertThat(API.requests.stream().anyMatch(r -> r.path().endsWith(b64("10000-2222222222-S")))).isFalse();
        assertThat(API.requests.stream().filter(r -> r.path().equals("/ba/pc/v6/jobs")).findFirst().orElseThrow().headers().get("x-api-key"))
                .isEqualTo("jobboerse-jobsuche");
        var adzuna = scheduler.runAdzuna();
        assertThat(adzuna.accepted()).isEqualTo(1);

        assertThat(jdbc.sql("select german_level from job_posting where title like 'Werkstudent Softwareentwicklung%'").query(String.class).single())
                .isEqualTo("B2");
        assertThat(jdbc.sql("select apply_url from job_posting where title like 'Praktikum Data%'").query(String.class).single())
                .isEqualTo("https://karriere.neckar-software.example/jobs/42");

        // The same Bosch job arrives through Lea's LinkedIn alert → merged into one public job
        String lea = register("lea@example.org");
        long leaId = jdbc.sql("select id from app_user where email = 'lea@example.org'").query(Long.class).single();
        ingest.ingest(List.of(
                new ExternalJob("EMAIL_ALERT", "LINKEDIN", "4012345678", "Werkstudent Softwareentwicklung Java (w/m/d)", "Robert Bosch",
                        List.of("Stuttgart, Baden-Württemberg, Deutschland"), "https://www.linkedin.com/jobs/view/4012345678/",
                        "https://www.linkedin.com/jobs/view/4012345678/", null, Instant.now(), leaId, true),
                new ExternalJob("EMAIL_ALERT", "STEPSTONE", "10123456", "Praktikum Informatik (m/w/d)", "Porsche AG", List.of("Stuttgart"),
                        "https://www.stepstone.de/stellenangebote--Praktikum-Informatik--10123456-inline.html",
                        "https://www.stepstone.de/stellenangebote--Praktikum-Informatik--10123456-inline.html", null, Instant.now(), leaId, true)));
        assertThat(jdbc.sql("select count(*) from job_posting where title like 'Werkstudent Softwareentwicklung%'").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select array_to_string(sources, ',') || '|' || array_to_string(platforms, ',') || '|' || apply_is_board "
                + "from job_posting where title like 'Werkstudent Softwareentwicklung%'").query(String.class).single())
                .isEqualTo("ARBEITSAGENTUR,EMAIL_ALERT|ARBEITSAGENTUR,LINKEDIN|true");
        // The richer BA description is kept
        assertThat(jdbc.sql("select snippet_only from job_posting where title like 'Werkstudent Softwareentwicklung%'").query(Boolean.class).single()).isFalse();

        // Lea sees 4 jobs (3 public + her private StepStone alert); another user sees only the 3 public ones
        assertThat(getJson(lea, "/api/jobs?locationMode=ALL").get("total").asInt()).isEqualTo(4);
        String max = register("max@example.org");
        assertThat(getJson(max, "/api/jobs?locationMode=ALL").get("total").asInt()).isEqualTo(3);
        long porscheJob = jdbc.sql("select id from job_posting where title like 'Praktikum Informatik%'").query(Long.class).single();
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/jobs/" + porscheJob)
                .header("Authorization", "Bearer " + max)).andReturn().getResponse().getStatus()).isEqualTo(404);

        // Source filter
        JsonNode onlyAlerts = getJson(lea, "/api/jobs?locationMode=ALL&sources=EMAIL_ALERT");
        assertThat(onlyAlerts.get("total").asInt()).isEqualTo(2);
        assertThat(getJson(lea, "/api/jobs?locationMode=ALL&sources=ADZUNA").get("items").get(0).get("platforms").get(0).asText()).isEqualTo("ADZUNA");

        // Applying to a StepStone job never opens StepStone in the browser: manual mode
        long cv = uploadCv(lea, CV_TEXT);
        long appId = Sql.JSON.readTree(postJson(lea, "/api/applications", "{\"jobId\":" + porscheJob + ",\"cvDocumentId\":" + cv + "}")
                .getResponse().getContentAsString()).get("id").asLong();
        runner.prepare(appId);
        Application a = apps.findById(appId).orElseThrow();
        assertThat(a.status()).isEqualTo("MANUAL_REQUIRED");
        assertThat(a.fillReport().message()).contains("StepStone");
        assertThat(a.hasScreenshot()).isFalse();

        // Stale external jobs are closed after 7 days (API) / 30 days (alerts)
        jdbc.sql("update job_posting set last_seen_at = now() - interval '8 days' where 'ADZUNA' = any(sources)").update();
        jdbc.sql("update job_posting set last_seen_at = now() - interval '8 days' where title like 'Praktikum Informatik%'").update();
        int closed = jobRepository.closeStaleExternal();
        assertThat(closed).isEqualTo(1); // Adzuna job closed, the 8-day-old alert job stays open
    }

    @Autowired de.bwjobs.job.JobRepository jobRepository;
}
