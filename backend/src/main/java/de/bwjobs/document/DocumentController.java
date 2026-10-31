package de.bwjobs.document;

import de.bwjobs.common.CurrentUser;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentService service;
    private final DocumentRepository repo;

    public DocumentController(DocumentService service, DocumentRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    @GetMapping
    public List<Document> list() {
        return service.list(CurrentUser.id());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Document upload(@RequestParam("file") MultipartFile file,
                           @RequestParam(defaultValue = "CV") String kind,
                           @RequestParam(required = false) String title) {
        return service.upload(CurrentUser.id(), file, kind, title);
    }

    @GetMapping("/{id}")
    public Document get(@PathVariable long id) {
        return service.get(CurrentUser.id(), id);
    }

    @GetMapping("/{id}/text")
    public Map<String, String> text(@PathVariable long id) {
        Document d = service.get(CurrentUser.id(), id);
        return Map.of("text", d.extractedText() == null ? "" : d.extractedText());
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id, @RequestParam(defaultValue = "false") boolean inline) {
        Document d = service.get(CurrentUser.id(), id);
        ContentDisposition cd = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(d.filename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType(d.contentType()))
                .body(service.content(d));
    }

    public record RenameRequest(String title) {}

    @PatchMapping("/{id}")
    public Document rename(@PathVariable long id, @RequestBody RenameRequest req) {
        Document d = service.get(CurrentUser.id(), id);
        if (req.title() != null && !req.title().isBlank()) repo.rename(d.id(), req.title().trim());
        return service.get(CurrentUser.id(), id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(CurrentUser.id(), id);
    }
}
