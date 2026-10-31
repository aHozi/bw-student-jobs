package de.bwjobs.profile;

import com.fasterxml.jackson.core.type.TypeReference;
import de.bwjobs.common.Sql;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ProfileRepository {
    private final JdbcClient jdbc;

    private static final RowMapper<UserProfile> MAPPER = (rs, i) -> new UserProfile(
            rs.getLong("user_id"), rs.getString("full_name"), rs.getString("email"), rs.getString("phone"),
            rs.getString("street"), rs.getString("postal_code"), rs.getString("city"),
            rs.getString("linkedin_url"), rs.getString("github_url"), rs.getString("website_url"),
            rs.getString("university"), rs.getString("study_program"), rs.getString("degree"),
            Sql.intOrNull(rs, "semester"), rs.getString("available_from"), Sql.intOrNull(rs, "hours_per_week"),
            Sql.textArray(rs, "skills"),
            Sql.readJson(rs.getString("languages"), new TypeReference<List<LanguageSkill>>() {}),
            rs.getString("summary"),
            Optional.ofNullable(Sql.readJson(rs.getString("preferences"), UserPreferences.class))
                    .orElse(UserPreferences.defaults()).withDefaults(),
            rs.getBoolean("ai_consent"));

    public ProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void createEmpty(long userId, String email, String name) {
        jdbc.sql("insert into user_profile(user_id, email, full_name, preferences) values (?,?,?,?) on conflict do nothing")
                .params(userId, email, name, Sql.jsonb(UserPreferences.defaults())).update();
    }

    public UserProfile get(long userId) {
        return jdbc.sql("select * from user_profile where user_id = ?").param(userId).query(MAPPER).single();
    }

    public void update(UserProfile p) {
        jdbc.sql("""
                update user_profile set full_name=?, email=?, phone=?, street=?, postal_code=?, city=?,
                  linkedin_url=?, github_url=?, website_url=?, university=?, study_program=?, degree=?,
                  semester=?, available_from=?, hours_per_week=?, skills=?::text[], languages=?, summary=?,
                  preferences=?, ai_consent=?, updated_at=now()
                where user_id=?""")
                .params(p.fullName(), p.email(), p.phone(), p.street(), p.postalCode(), p.city(),
                        p.linkedinUrl(), p.githubUrl(), p.websiteUrl(), p.university(), p.studyProgram(), p.degree(),
                        p.semester(), p.availableFrom(), p.hoursPerWeek(), Sql.arrayLiteral(p.skills() == null ? List.of() : p.skills()),
                        Sql.jsonb(p.languages() == null ? List.of() : p.languages()), p.summary(),
                        Sql.jsonb(p.preferences() == null ? UserPreferences.defaults() : p.preferences().withDefaults()),
                        p.aiConsent(), p.userId())
                .update();
    }
}
