package com.gametrans.project;

import com.gametrans.project.ProjectDtos.ProjectDto;
import com.gametrans.project.ProjectDtos.ProjectPatch;
import java.util.List;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectService service;

    public ProjectController(ProjectService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ProjectDto> upload(@RequestParam("file") MultipartFile file,
                                             @RequestParam(value = "gameId", required = false) Long gameId,
                                             @RequestParam(value = "gameName", required = false) String gameName,
                                             @RequestParam(value = "reviewOnly", required = false) Boolean reviewOnly) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectDto.of(service.upload(file, gameId, gameName, Boolean.TRUE.equals(reviewOnly))));
    }

    @GetMapping
    public List<ProjectDto> list(@RequestParam(value = "gameId", required = false) Long gameId) {
        return service.list(gameId).stream().map(ProjectDto::of).toList();
    }

    @GetMapping("/{id}")
    public ProjectDto get(@PathVariable Long id) { return ProjectDto.of(service.get(id)); }

    @PatchMapping("/{id}")
    public ProjectDto patch(@PathVariable Long id, @RequestBody ProjectPatch patch) { return ProjectDto.of(service.patch(id, patch)); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
