package de.bwjobs.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.bwjobs.common.Sql;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Real IMAP import against a GreenMail test mail server holding LinkedIn, StepStone and Indeed alert e-mails. */
class EmailImportIT extends IntegrationTestBase {

    @RegisterExtension
    static GreenMailExtension mail = new GreenMailExtension(ServerSetupTest.IMAP);

    static void deliver(GreenMailUser user, String from, String subject, String fixture) throws Exception {
        String html;
        try (var in = EmailImportIT.class.getResourceAsStream("/fixtures/alerts/" + fixture)) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress(from));
        m.setRecipient(Message.RecipientType.TO, new InternetAddress(user.getEmail()));
        m.setSubject(subject, "UTF-8");
        m.setContent(html, "text/html; charset=utf-8");
        m.saveChanges();
        user.deliver(m);
    }

    @Test
    void importsAlertEmailsOnceAndOnlyForThatUser() throws Exception {
        GreenMailUser box = mail.setUser("lea@example.org", "lea@example.org", "app-password-123");
        deliver(box, "jobalerts-noreply@linkedin.com", "Werkstudent Informatik: 3 neue Jobs", "linkedin.html");
        deliver(box, "jobagent@stepstone.de", "Neue Jobs für Ihren Jobagenten", "stepstone.html");
        deliver(box, "alert@indeed.com", "Neue Jobs: working student software", "indeed.html");
        deliver(box, "newsletter@shop.example", "50% Rabatt", "linkedin.html"); // not an alert sender → ignored

        String lea = register("lea@example.org");
        String settings = "{\"host\":\"127.0.0.1\",\"port\":" + ServerSetupTest.IMAP.getPort()
                + ",\"username\":\"lea@example.org\",\"password\":\"app-password-123\",\"folder\":\"INBOX\"}";

        // Wrong password is reported, not stored as working
        JsonNode bad = Sql.JSON.readTree(postJson(lea, "/api/email-import/test", settings.replace("app-password-123", "wrong"))
                .getResponse().getContentAsString());
        assertThat(bad.get("ok").asBoolean()).isFalse();

        JsonNode ok = Sql.JSON.readTree(postJson(lea, "/api/email-import/test", settings).getResponse().getContentAsString());
        assertThat(ok.get("ok").asBoolean()).as(ok.toString()).isTrue();
        assertThat(ok.get("alertMessages").asInt()).isEqualTo(3);

        assertThat(putJson(lea, "/api/email-import", settings).getResponse().getStatus()).isEqualTo(200);
        JsonNode view = getJson(lea, "/api/email-import");
        assertThat(view.get("connected").asBoolean()).isTrue();
        assertThat(view.toString()).doesNotContain("app-password-123");
        assertThat(jdbc.sql("select password_enc from email_import_settings").query(String.class).single()).doesNotContain("app-password");

        MvcResult run = postJson(lea, "/api/email-import/run", "{}");
        assertThat(run.getResponse().getStatus()).as(run.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode r = Sql.JSON.readTree(run.getResponse().getContentAsString());
        assertThat(r.get("alertMessages").asInt()).isEqualTo(3);
        assertThat(r.get("jobsFound").asInt()).isEqualTo(7);
        assertThat(r.get("accepted").asInt()).isEqualTo(6); // the senior LinkedIn job is not a student job

        JsonNode jobs = getJson(lea, "/api/jobs?locationMode=ALL");
        assertThat(jobs.get("total").asInt()).isEqualTo(6);
        assertThat(jobs.toString()).contains("LINKEDIN").contains("STEPSTONE").contains("INDEED").contains("Robert Bosch GmbH");

        // Second run: nothing new
        JsonNode again = Sql.JSON.readTree(postJson(lea, "/api/email-import/run", "{}").getResponse().getContentAsString());
        assertThat(again.get("alertMessages").asInt()).isZero();

        // A new alert arrives later → only that one is read
        deliver(box, "jobalerts-noreply@linkedin.com", "Neue Jobs", "linkedin.html");
        JsonNode third = Sql.JSON.readTree(postJson(lea, "/api/email-import/run", "{}").getResponse().getContentAsString());
        assertThat(third.get("alertMessages").asInt()).isEqualTo(1);
        assertThat(third.get("newJobs").asInt()).isZero(); // same jobs → merged

        // Private to Lea
        String max = register("max@example.org");
        assertThat(getJson(max, "/api/jobs?locationMode=ALL").get("total").asInt()).isZero();
    }
}
