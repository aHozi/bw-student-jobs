package de.bwjobs.document;

import de.bwjobs.common.Sql;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class DocumentRepository {
    private final JdbcClient jdbc;

    private static final RowMapper<Document> MAPPER = (rs, i) -> new Document(
            rs.getLong("id"), rs.getLong("user_id"), rs.getString("kind"), rs.getString("title"), rs.getString("filename"),
            rs.getString("content_type"), rs.getString("storage_key"), rs.getLong("size_bytes"), rs.getBoolean("original"),
            Sql.longOrNull(rs, "derived_from_id"), Sql.longOrNull(rs, "job_id"), rs.getString("extracted_text"),
            rs.getString("content_json"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"));

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Document insert(long userId, String kind, String title, String filename, String contentType, String storageKey,
                           long size, boolean original, Long derivedFrom, Long jobId, String text, Object contentJson) {
        long id = jdbc.sql("""
                insert into document(user_id, kind, title, filename, content_type, storage_key, size_bytes, original,
                  derived_from_id, job_id, extracted_text, content_json)
                values (?,?,?,?,?,?,?,?,?,?,?,?) returning id""")
                .params(userId, kind, title, filename, contentType, storageKey, size, original, derivedFrom, jobId, text,
                        contentJson == null ? null : Sql.jsonb(contentJson))
                .query(Long.class).single();
        return findById(id).orElseThrow();
    }

    public Optional<Document> findById(long id) {
        return jdbc.sql("select * from document where id = ?").param(id).query(MAPPER).optional();
    }

    public Optional<Document> findOwned(long userId, long id) {
        return jdbc.sql("select * from document where id = ? and user_id = ?").params(id, userId).query(MAPPER).optional();
    }

    public List<Document> listByUser(long userId) {
        return jdbc.sql("select * from document where user_id = ? order by original desc, created_at desc").param(userId)
                .query(MAPPER).list();
    }

    public void updateGenerated(long id, Object contentJson, long size, String text) {
        jdbc.sql("update document set content_json = ?, size_bytes = ?, extracted_text = ?, updated_at = now() where id = ?")
                .params(Sql.jsonb(contentJson), size, text, id).update();
    }

    public void rename(long id, String title) {
        jdbc.sql("update document set title = ?, updated_at = now() where id = ?").params(title, id).update();
    }

    public void delete(long id) {
        jdbc.sql("delete from document where id = ?").param(id).update();
    }
}
