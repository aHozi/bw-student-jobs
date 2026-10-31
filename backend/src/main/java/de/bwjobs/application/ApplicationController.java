package de.bwjobs.application;

import de.bwjobs.common.ApiException;
import de.bwjobs.common.CurrentUser;
import de.bwjobs.document.DocumentStorage;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {
    private final ApplicationService service;
    private final ApplicationRepository repo;
    private final DocumentStorage storage;

    public ApplicationController(ApplicationService service, ApplicationRepository repo, DocumentStorage storage) {
        this.service = service;
        this.repo = repo;
        this.storage = storage;
    }

    @GetMapping
    public List<ApplicationRepository.Row> list() {
        return repo.listByUser(CurrentUser.id());
    }

    @GetMapping("/{id}")
    public Application get(@PathVariable long id) {
        return service.owned(CurrentUser.id(), id);
    }

    @PostMapping
    public Application create(@RequestBody ApplicationService.CreateRequest req) {
        return service.create(CurrentUser.id(), req);
    }

    @PutMapping("/{id}/documents")
    public Application documents(@PathVariable long id, @RequestBody ApplicationService.CreateRequest req) {
        return service.changeDocuments(CurrentUser.id(), id, req);
    }

    @PostMapping("/{id}/prepare")
    public Application prepare(@PathVariable long id) {
        return service.prepare(CurrentUser.id(), id);
    }

    @PostMapping("/{id}/approve")
    public Application approve(@PathVariable long id, @RequestBody ApplicationService.ApproveRequest req) {
        return service.approveAndSubmit(CurrentUser.id(), id, req);
    }

    @PostMapping("/{id}/mark-submitted")
    public Application markSubmitted(@PathVariable long id) {
        return service.markSubmitted(CurrentUser.id(), id);
    }

    @PatchMapping("/{id}")
    public Application track(@PathVariable long id, @RequestBody ApplicationService.TrackingUpdate u) {
        return service.updateTracking(CurrentUser.id(), id, u);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(CurrentUser.id(), id);
    }

    @GetMapping("/{id}/screenshot")
    public ResponseEntity<byte[]> screenshot(@PathVariable long id, @RequestParam(defaultValue = "review") String which) {
        Application a = service.owned(CurrentUser.id(), id);
        String key = "confirmation".equals(which) ? a.confirmationKey() : a.screenshotKey();
        if (key == null) throw ApiException.notFound("Screenshot");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(storage.get(key));
    }
}
