package de.bwjobs.profile;

import de.bwjobs.common.CurrentUser;
import de.bwjobs.common.Sql;
import de.bwjobs.document.DocumentService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ProfileController {
    private final ProfileRepository profiles;
    private final UserRepository users;
    private final DocumentService documents;
    private final JdbcClient jdbc;

    public ProfileController(ProfileRepository profiles, UserRepository users, DocumentService documents, JdbcClient jdbc) {
        this.profiles = profiles;
        this.users = users;
        this.documents = documents;
        this.jdbc = jdbc;
    }

    @GetMapping("/profile")
    public UserProfile get() {
        return profiles.get(CurrentUser.id());
    }

    @PutMapping("/profile")
    public UserProfile update(@RequestBody UserProfile in) {
        long uid = CurrentUser.id();
        UserProfile p = new UserProfile(uid, in.fullName(), in.email(), in.phone(), in.street(), in.postalCode(), in.city(),
                in.linkedinUrl(), in.githubUrl(), in.websiteUrl(), in.university(), in.studyProgram(), in.degree(), in.semester(),
                in.availableFrom(), in.hoursPerWeek(), in.skills(), in.languages(), in.summary(),
                in.preferences() == null ? UserPreferences.defaults() : in.preferences().withDefaults(), in.aiConsent());
        profiles.update(p);
        return profiles.get(uid);
    }

    /** GDPR: export all personal data as JSON (documents listed with metadata). */
    @GetMapping("/account/export")
    public ResponseEntity<String> export() {
        long uid = CurrentUser.id();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", users.findById(uid).map(u -> Map.of("email", u.email(), "displayName", String.valueOf(u.displayName()),
                "createdAt", u.createdAt().toString())).orElse(Map.of()));
        out.put("profile", profiles.get(uid));
        out.put("documents", documents.list(uid));
        out.put("applications", jdbc.sql("""
                select a.id, a.status, a.mode, a.created_at, a.submitted_at, a.notes, j.title, c.name as company
                from application a join job_posting j on j.id = a.job_id join company c on c.id = a.company_id where a.user_id = ?""")
                .param(uid).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (String col : new String[]{"id", "status", "mode", "created_at", "submitted_at", "notes", "title", "company"}) {
                        Object v = rs.getObject(col);
                        m.put(col, v == null ? null : v.toString());
                    }
                    return m;
                }).list());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("my-data.json", StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Sql.toJson(out));
    }

    /** GDPR: delete the account, all files and all related rows (cascade). */
    @DeleteMapping("/account")
    @Transactional
    public void deleteAccount() {
        long uid = CurrentUser.id();
        documents.deleteAll(uid);
        jdbc.sql("delete from application where user_id = ?").param(uid).update();
        users.delete(uid);
    }
}
