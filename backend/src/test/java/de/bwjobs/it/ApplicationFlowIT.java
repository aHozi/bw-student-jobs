package de.bwjobs.it;

import com.fasterxml.jackson.databind.JsonNode;
import de.bwjobs.application.Application;
import de.bwjobs.application.ApplicationRepository;
import de.bwjobs.application.ApplicationRunner;
import de.bwjobs.application.FillReport;
import de.bwjobs.common.Sql;
import de.bwjobs.company.AtsType;
import de.bwjobs.company.Company;
import de.bwjobs.company.CompanyRepository;
import de.bwjobs.job.JobFingerprint;
import de.bwjobs.job.JobRepository;
import de.bwjobs.support.MockSite;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real headless browser against a mock company application form:
 * prepare fills but never submits; submit only happens after explicit approval; duplicates are blocked.
 */
class ApplicationFlowIT extends IntegrationTestBase {
    @Autowired CompanyRepository companies;
    @Autowired JobRepository jobs;
    @Autowired ApplicationRunner runner;
    @Autowired ApplicationRepository apps;

    static final String JOB_PAGE = """
            <html><body><main><h1>Werkstudent Java (m/w/d)</h1><p>Spring Boot, Docker.</p>
            <a href="/apply/1">Jetzt bewerben</a></main></body></html>""";

    static final String FORM = """
            <html><body>
            <div id="cookie"><button onclick="document.getElementById('cookie').remove()">Nur notwendige Cookies</button></div>
            <form action="/apply/1/submit" method="post" enctype="multipart/form-data">
              <label for="fn">Vorname *</label><input id="fn" name="firstName" required>
              <label for="ln">Nachname *</label><input id="ln" name="lastName" required>
              <label for="em">E-Mail *</label><input id="em" name="email" type="email" required>
              <label for="ph">Telefon</label><input id="ph" name="phone" type="tel">
              <label for="cv">Lebenslauf (PDF) *</label><input id="cv" name="cv" type="file" required>
              <label for="cl">Anschreiben</label><input id="cl" name="coverLetter" type="file">
              <label for="sal">Gehaltsvorstellung *</label><input id="sal" name="salary" required>
              <label for="tp"><input id="tp" name="talentpool" type="checkbox"> In den Talentpool aufnehmen</label>
              <label for="pp"><input id="pp" name="privacy" type="checkbox" required> Ich akzeptiere die Datenschutzerklärung *</label>
              <button type="submit">Bewerbung absenden</button>
            </form></body></html>""";

    @Test
    void prepareReviewApproveSubmit() throws Exception {
        try (MockSite site = new MockSite()) {
            site.html("/jobs/1", JOB_PAGE);
            site.html("/apply/1", FORM);
            site.html("POST /apply/1/submit", "<html><body><h1>Vielen Dank für Ihre Bewerbung!</h1></body></html>");

            Company c = companies.insert("FormTest AG", site.base(), site.base() + "/karriere", "Stuttgart", "IT", "M",
                    AtsType.GENERIC, null, "ACTIVE", "ADMIN", 12);
            long jobId = jobs.upsert(new JobRepository.Upsert(c.id(), "1", site.base() + "/jobs/1", null, "Werkstudent Java (m/w/d)",
                    "<p>Spring Boot</p>", "Spring Boot", List.of("Stuttgart"), false, true, List.of("WERKSTUDENT"), List.of("JAVA"),
                    List.of("java"), "B2", "GOOD", "DE", null, Instant.now(), JobFingerprint.of(c.id(), "Werkstudent Java", List.of("Stuttgart")))).id();

            String token = register("max@example.org");
            setProfile(token, "{\"fullName\":\"Max Mustermann\",\"email\":\"max@example.org\",\"phone\":\"+49 170 1234567\"}");
            long cvId = uploadCv(token, CV_TEXT);

            // Create application; a second one for the same job is blocked
            MvcResult created = postJson(token, "/api/applications", "{\"jobId\":" + jobId + ",\"cvDocumentId\":" + cvId + "}");
            assertThat(created.getResponse().getStatus()).isEqualTo(200);
            long appId = Sql.JSON.readTree(created.getResponse().getContentAsString()).get("id").asLong();
            MvcResult dup = postJson(token, "/api/applications", "{\"jobId\":" + jobId + ",\"cvDocumentId\":" + cvId + "}");
            assertThat(dup.getResponse().getStatus()).isEqualTo(409);

            // Prepare: fills the form in a real browser but does NOT submit
            runner.prepare(appId);
            Application prepared = apps.findById(appId).orElseThrow();
            assertThat(prepared.status()).isEqualTo("READY_FOR_REVIEW");
            Map<String, FillReport.Field> f = prepared.fillReport().fields().stream()
                    .collect(Collectors.toMap(FillReport.Field::key, x -> x));
            assertThat(f.get("n:firstName").value()).isEqualTo("Max");
            assertThat(f.get("n:lastName").value()).isEqualTo("Mustermann");
            assertThat(f.get("n:email").value()).isEqualTo("max@example.org");
            assertThat(f.get("n:cv").status()).isEqualTo("FILE");
            assertThat(f.get("n:salary").status()).isEqualTo("NEEDS_INPUT");
            assertThat(f.get("n:privacy").status()).isEqualTo("CONSENT");
            assertThat(f.get("n:talentpool").status()).isEqualTo("SKIPPED");
            assertThat(prepared.fillReport().unresolvedRequired()).isEqualTo(2);
            assertThat(prepared.hasScreenshot()).isTrue();
            assertThat(submissions(site)).isZero();

            // Without approval the runner refuses to submit
            runner.submit(appId);
            assertThat(apps.findById(appId).orElseThrow().status()).isEqualTo("READY_FOR_REVIEW");
            assertThat(submissions(site)).isZero();

            // Approval must be explicit
            assertThat(postJson(token, "/api/applications/" + appId + "/approve", "{\"confirm\":false}").getResponse().getStatus()).isEqualTo(400);

            // Approve with the user's answers → background submission
            MvcResult approved = postJson(token, "/api/applications/" + appId + "/approve",
                    "{\"confirm\":true,\"overrides\":{\"n:salary\":\"16 EUR/h\"},\"consents\":[\"n:privacy\"]}");
            assertThat(approved.getResponse().getStatus()).isEqualTo(200);
            Application done = waitFor(appId, "SUBMITTED", "FAILED");
            assertThat(done.status()).as(done.errorMessage()).isEqualTo("SUBMITTED");
            assertThat(done.submittedAt()).isNotNull();
            assertThat(done.hasConfirmation()).isTrue();
            assertThat(submissions(site)).isEqualTo(1);
            String body = site.requests.stream().filter(r -> r.method().equals("POST")).findFirst().orElseThrow().bodyText();
            assertThat(body).contains("Max").contains("Mustermann").contains("16 EUR/h").contains("Lebenslauf.txt").contains("name=\"privacy\"");
            assertThat(body).doesNotContain("name=\"talentpool\"");

            // Approving again cannot submit twice
            assertThat(postJson(token, "/api/applications/" + appId + "/approve", "{\"confirm\":true}").getResponse().getStatus()).isEqualTo(409);

            // A repost with a near-identical title at the same company needs confirmation
            long repost = jobs.upsert(new JobRepository.Upsert(c.id(), "2", site.base() + "/jobs/2", null, "Werkstudent Java (w/m/d)",
                    null, "", List.of("Ludwigsburg"), false, true, List.of("WERKSTUDENT"), List.of("JAVA"), List.of(),
                    "B2", "GOOD", "DE", null, null, JobFingerprint.of(c.id(), "Werkstudent Java", List.of("Ludwigsburg")))).id();
            MvcResult similar = postJson(token, "/api/applications", "{\"jobId\":" + repost + ",\"cvDocumentId\":" + cvId + "}");
            assertThat(similar.getResponse().getStatus()).isEqualTo(409);
            JsonNode details = Sql.JSON.readTree(similar.getResponse().getContentAsString()).get("details");
            assertThat(details.get("type").asText()).isEqualTo("SIMILAR_AT_COMPANY");
            assertThat(postJson(token, "/api/applications", "{\"jobId\":" + repost + ",\"cvDocumentId\":" + cvId + ",\"confirmSimilar\":true}")
                    .getResponse().getStatus()).isEqualTo(200);

            // Another user cannot see this application
            String other = register("eve@example.org");
            assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/applications/" + appId)
                    .header("Authorization", "Bearer " + other)).andReturn().getResponse().getStatus()).isEqualTo(404);
        }
    }

    @Test
    void captchaFormsGoToManualMode() throws Exception {
        try (MockSite site = new MockSite()) {
            site.html("/jobs/1", """
                    <html><body><form action="/x" method="post"><label for="e">E-Mail</label><input id="e" name="email">
                    <label for="c">CV</label><input id="c" type="file" name="cv"><div class="g-recaptcha" data-sitekey="abc"></div>
                    <button type="submit">Absenden</button></form></body></html>""");
            Company c = companies.insert("Captcha AG", site.base(), null, "Ulm", "IT", "M", AtsType.GENERIC, null, "ACTIVE", "ADMIN", 12);
            long jobId = jobs.upsert(new JobRepository.Upsert(c.id(), "1", site.base() + "/jobs/1", null, "Praktikum IT", null, "",
                    List.of(), false, true, List.of("PRAKTIKUM"), List.of("IT_GENERAL"), List.of(), "UNKNOWN", "UNKNOWN", "DE", null, null,
                    "fp-captcha")).id();
            String token = register("c@example.org");
            long cvId = uploadCv(token, CV_TEXT);
            long appId = Sql.JSON.readTree(postJson(token, "/api/applications", "{\"jobId\":" + jobId + ",\"cvDocumentId\":" + cvId + "}")
                    .getResponse().getContentAsString()).get("id").asLong();
            runner.prepare(appId);
            Application a = apps.findById(appId).orElseThrow();
            assertThat(a.status()).isEqualTo("MANUAL_REQUIRED");
            assertThat(a.fillReport().captcha()).isTrue();
            // The user applies themselves and marks it as submitted
            assertThat(postJson(token, "/api/applications/" + appId + "/mark-submitted", "{}").getResponse().getStatus()).isEqualTo(200);
            assertThat(apps.findById(appId).orElseThrow().status()).isEqualTo("SUBMITTED");
        }
    }

    private static long submissions(MockSite site) {
        return site.requests.stream().filter(r -> r.method().equals("POST") && r.path().equals("/apply/1/submit")).count();
    }

    private Application waitFor(long id, String... statuses) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            Application a = apps.findById(id).orElseThrow();
            if (List.of(statuses).contains(a.status())) return a;
            Thread.sleep(500);
        }
        return apps.findById(id).orElseThrow();
    }
}
