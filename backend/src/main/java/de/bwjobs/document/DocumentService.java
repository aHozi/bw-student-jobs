package de.bwjobs.document;

import de.bwjobs.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DocumentService {
    public static final Set<String> KINDS = Set.of("CV", "COVER_LETTER", "TRANSCRIPT", "CERTIFICATE", "OTHER");
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Map<String, String> ALLOWED = Map.of(
            "application/pdf", "pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx",
            "application/msword", "doc",
            "application/x-tika-ooxml", "docx",
            "text/plain", "txt",
            "image/png", "png",
            "image/jpeg", "jpg");

    private final DocumentRepository docs;
    private final DocumentStorage storage;
    private final TextExtractor extractor;

    public DocumentService(DocumentRepository docs, DocumentStorage storage, TextExtractor extractor) {
        this.docs = docs;
        this.storage = storage;
        this.extractor = extractor;
    }

    public Document upload(long userId, MultipartFile file, String kind, String title) {
        if (!KINDS.contains(kind)) throw ApiException.badRequest("Unknown document kind: " + kind);
        if (file == null || file.isEmpty()) throw ApiException.badRequest("File is empty");
        if (file.getSize() > MAX_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "File is too large (max 10 MB)");
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Cannot read file");
        }
        String filename = sanitize(file.getOriginalFilename());
        String mime = extractor.detect(data, filename);
        if (!ALLOWED.containsKey(mime)) {
            throw ApiException.badRequest("Unsupported file type " + mime + ". Allowed: PDF, DOCX, DOC, TXT, PNG, JPG");
        }
        String text = mime.startsWith("image/") ? null : extractor.extract(data);
        if ("CV".equals(kind) && (text == null || text.isBlank())) {
            throw ApiException.badRequest("Could not read any text from this CV. Please upload a text-based PDF or DOCX (not a scan).");
        }
        String key = storage.put(data);
        return docs.insert(userId, kind, title == null || title.isBlank() ? filename : title.trim(), filename, mime, key,
                data.length, true, null, null, text, null);
    }

    public Document saveGenerated(long userId, String kind, String title, String filename, byte[] pdf, Object content,
                                  Long derivedFrom, Long jobId, String text) {
        String key = storage.put(pdf);
        return docs.insert(userId, kind, title, filename, "application/pdf", key, pdf.length, false, derivedFrom, jobId, text, content);
    }

    public void replaceGenerated(Document d, byte[] pdf, Object content, String text) {
        if (d.original()) throw ApiException.badRequest("Original uploads cannot be edited. Generate a tailored copy instead.");
        storage.overwrite(d.storageKey(), pdf);
        docs.updateGenerated(d.id(), content, pdf.length, text);
    }

    public Document get(long userId, long id) {
        return docs.findOwned(userId, id).orElseThrow(() -> ApiException.notFound("Document"));
    }

    public byte[] content(Document d) {
        return storage.get(d.storageKey());
    }

    public List<Document> list(long userId) {
        return docs.listByUser(userId);
    }

    public void delete(long userId, long id) {
        Document d = get(userId, id);
        docs.delete(d.id());
        storage.delete(d.storageKey());
    }

    /** Removes every stored file of a user (account deletion). */
    public void deleteAll(long userId) {
        for (Document d : docs.listByUser(userId)) storage.delete(d.storageKey());
    }

    static String sanitize(String name) {
        if (name == null || name.isBlank()) return "document";
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        return base.replaceAll("[^A-Za-z0-9._ äöüÄÖÜß-]", "_");
    }
}
