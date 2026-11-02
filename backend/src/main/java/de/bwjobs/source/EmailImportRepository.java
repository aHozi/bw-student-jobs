package de.bwjobs.source;

import de.bwjobs.common.Sql;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class EmailImportRepository {
    private final JdbcClient jdbc;

    public record Settings(long userId, String host, int port, String username, String passwordEnc, String folder,
                           boolean enabled, Long uidValidity, Long lastUid, Instant lastSyncAt, String lastError) {}

    private static final RowMapper<Settings> MAPPER = (rs, i) -> new Settings(rs.getLong("user_id"), rs.getString("host"),
            rs.getInt("port"), rs.getString("username"), rs.getString("password_enc"), rs.getString("folder"),
            rs.getBoolean("enabled"), Sql.longOrNull(rs, "uid_validity"), Sql.longOrNull(rs, "last_uid"),
            Sql.instant(rs, "last_sync_at"), rs.getString("last_error"));

    public EmailImportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Settings> get(long userId) {
        return jdbc.sql("select * from email_import_settings where user_id = ?").param(userId).query(MAPPER).optional();
    }

    public List<Settings> enabled() {
        return jdbc.sql("select * from email_import_settings where enabled").query(MAPPER).list();
    }

    /** Saves connection settings; a changed account or folder restarts the sync window. */
    public void save(long userId, String host, int port, String username, String passwordEnc, String folder, boolean enabled) {
        jdbc.sql("""
                insert into email_import_settings(user_id, host, port, username, password_enc, folder, enabled)
                values (?,?,?,?,?,?,?)
                on conflict (user_id) do update set
                  uid_validity = case when email_import_settings.host = excluded.host and email_import_settings.username = excluded.username
                                       and email_import_settings.folder = excluded.folder then email_import_settings.uid_validity end,
                  last_uid = case when email_import_settings.host = excluded.host and email_import_settings.username = excluded.username
                                   and email_import_settings.folder = excluded.folder then email_import_settings.last_uid end,
                  host = excluded.host, port = excluded.port, username = excluded.username, password_enc = excluded.password_enc,
                  folder = excluded.folder, enabled = excluded.enabled, last_error = null, updated_at = now()""")
                .params(userId, host, port, username, passwordEnc, folder, enabled).update();
    }

    public void updateSync(long userId, Long uidValidity, Long lastUid, String error) {
        jdbc.sql("""
                update email_import_settings set uid_validity = coalesce(?, uid_validity), last_uid = coalesce(?, last_uid),
                  last_sync_at = now(), last_error = ? where user_id = ?""")
                .params(uidValidity, lastUid, error, userId).update();
    }

    public boolean isSeen(long userId, String messageId) {
        return jdbc.sql("select exists(select 1 from email_import_seen where user_id = ? and message_id = ?)")
                .params(userId, messageId).query(Boolean.class).single();
    }

    /** Returns true the first time a message is seen for this user. */
    public boolean markSeen(long userId, String messageId) {
        return jdbc.sql("insert into email_import_seen(user_id, message_id) values (?, ?) on conflict do nothing")
                .params(userId, messageId).update() == 1;
    }

    public void delete(long userId) {
        jdbc.sql("delete from email_import_settings where user_id = ?").param(userId).update();
        jdbc.sql("delete from email_import_seen where user_id = ?").param(userId).update();
    }
}
