package de.bwjobs.profile;

import de.bwjobs.common.Sql;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserRepository {
    private final JdbcClient jdbc;

    private static final RowMapper<AppUser> MAPPER = (rs, i) -> new AppUser(
            rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
            rs.getString("display_name"), rs.getString("role"), Sql.instant(rs, "created_at"));

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByEmail(String email) {
        return jdbc.sql("select * from app_user where lower(email) = lower(?)").param(email).query(MAPPER).optional();
    }

    public Optional<AppUser> findById(long id) {
        return jdbc.sql("select * from app_user where id = ?").param(id).query(MAPPER).optional();
    }

    public long countAdmins() {
        return jdbc.sql("select count(*) from app_user where role = 'ADMIN'").query(Long.class).single();
    }

    public AppUser insert(String email, String hash, String displayName, String role) {
        long id = jdbc.sql("insert into app_user(email, password_hash, display_name, role) values (?,?,?,?) returning id")
                .params(email.trim().toLowerCase(), hash, displayName, role).query(Long.class).single();
        return findById(id).orElseThrow();
    }

    public void delete(long id) {
        jdbc.sql("delete from app_user where id = ?").param(id).update();
    }
}
