package com.gametrans.reviewer;

import com.gametrans.reviewer.ReviewerService.ReviewerRef;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reviewers")
public class ReviewerController {
    public record ReviewerCreate(String name) {}

    private final ReviewerService service;

    public ReviewerController(ReviewerService service) { this.service = service; }

    @PostMapping
    public ReviewerRef upsert(@RequestBody ReviewerCreate body) { return ReviewerService.ref(service.upsert(body.name())); }

    @GetMapping
    public List<ReviewerRef> list() { return service.all().stream().map(ReviewerService::ref).toList(); }

    @PatchMapping("/{id}")
    public ReviewerRef rename(@PathVariable Long id, @RequestBody ReviewerCreate body) { return service.rename(id, body.name()); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @RequestParam(required = false) Long transferTo) {
        service.delete(id, transferTo);
        return ResponseEntity.noContent().build();
    }
}
