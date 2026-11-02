package de.bwjobs.it;

import com.fasterxml.jackson.databind.JsonNode;
import de.bwjobs.common.Sql;
import de.bwjobs.support.DatabaseAvailable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(DatabaseAvailable.class)
abstract class IntegrationTestBase {
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("truncate table email_import_seen, email_import_settings, source_run_log, job_visibility, ai_usage, scan_run_log, scan_task, application, match_score, document, user_profile, app_user, job_posting, company restart identity cascade").update();
    }

    protected String register(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"password123\",\"displayName\":\"Test User\"}")).andReturn();
        return Sql.JSON.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    protected JsonNode getJson(String token, String path) throws Exception {
        MvcResult r = mvc.perform(get(path).header("Authorization", "Bearer " + token)).andReturn();
        return Sql.JSON.readTree(r.getResponse().getContentAsString());
    }

    protected MvcResult postJson(String token, String path, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    protected MvcResult putJson(String token, String path, String body) throws Exception {
        return mvc.perform(put(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    protected long uploadCv(String token, String text) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "Lebenslauf.txt", "text/plain", text.getBytes());
        MvcResult r = mvc.perform(multipart("/api/documents").file(file).param("kind", "CV").header("Authorization", "Bearer " + token)).andReturn();
        return Sql.JSON.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    protected void setProfile(String token, String json) throws Exception {
        JsonNode current = getJson(token, "/api/profile");
        var merged = (com.fasterxml.jackson.databind.node.ObjectNode) current;
        merged.setAll((com.fasterxml.jackson.databind.node.ObjectNode) Sql.JSON.readTree(json));
        putJson(token, "/api/profile", merged.toString());
    }

    protected static final String CV_TEXT = """
            Max Mustermann
            max@example.org · +49 170 1234567 · Stuttgart
            Ausbildung: Informatik (B.Sc.), Universität Stuttgart, 5. Semester
            Kenntnisse: Java, Spring Boot, Python, SQL, Docker, Git, React
            Sprachen: Deutsch (Muttersprache), Englisch (C1)
            Projekte: REST-API mit Spring Boot und PostgreSQL; Machine-Learning-Projekt mit PyTorch
            """;
}
