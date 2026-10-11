package com.gametrans.term;

import com.gametrans.job.JobDtos.JobDto;
import com.gametrans.term.TermDtos.TermDto;
import com.gametrans.term.TermDtos.TermUpdate;
import com.gametrans.term.TermDtos.TermsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TermController {
    private final TermService service;

    public TermController(TermService service) { this.service = service; }

    @PostMapping("/projects/{id}/terms/extract")
    public JobDto extract(@PathVariable Long id) { return service.startExtract(id); }

    @PostMapping("/projects/{id}/terms/suggest")
    public JobDto suggest(@PathVariable Long id) { return service.startSuggest(id); }

    @GetMapping("/projects/{id}/terms")
    public TermsResponse list(@PathVariable Long id,
                              @RequestParam(defaultValue = "false") boolean unconfirmed,
                              @RequestParam(required = false) String q,
                              @RequestParam(required = false) Term.Type type,
                              @RequestParam(required = false) Integer page,
                              @RequestParam(required = false) Integer size) {
        return service.list(id, unconfirmed, q, type, com.gametrans.config.Paging.page(page), com.gametrans.config.Paging.size(size));
    }

    @PostMapping("/projects/{id}/terms")
    public org.springframework.http.ResponseEntity<TermDto> create(@PathVariable Long id, @RequestBody com.gametrans.term.TermDtos.TermCreate body) {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(service.create(id, body));
    }

    @PatchMapping("/terms/{termId}")
    public TermDto update(@PathVariable Long termId, @RequestBody TermUpdate update) { return service.update(termId, update); }

    @PostMapping("/projects/{id}/terms/confirm-all")
    public TermsResponse confirmAll(@PathVariable Long id) { return service.confirmAll(id); }

    @PostMapping("/projects/{id}/terms/confirm-filled")
    public TermsResponse confirmFilled(@PathVariable Long id) { return service.confirmFilled(id); }
}
