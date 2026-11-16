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
    private final com.gametrans.normalize.NormalizeConfigRepository configs;
    private final ProjectRepository projects;

    public ProjectController(ProjectService service, com.gametrans.normalize.NormalizeConfigRepository configs, ProjectRepository projects) {
        this.service = service;
        this.configs = configs;
        this.projects = projects;
    }

    /** 최종 리뷰 버전으로 정규화한 시트가 있는 파일 id(목록 한 번에 설정을 한 번만 읽는다). */
    private java.util.Set<Long> finalReviewIds(java.util.Collection<Long> projectIds) {
        if (projectIds.isEmpty()) return java.util.Set.of();
        return configs.findByProjectIdIn(projectIds).stream().filter(com.gametrans.normalize.NormalizeConfig::isFinalReview)
                .map(com.gametrans.normalize.NormalizeConfig::getProjectId).collect(java.util.stream.Collectors.toSet());
    }

    /** 파일 id → [검수 행, 확정 행]. 목록 한 번에 한 쿼리. */
    private java.util.Map<Long, long[]> progress(java.util.Collection<Long> projectIds) {
        java.util.Map<Long, long[]> out = new java.util.HashMap<>();
        if (projectIds.isEmpty()) return out;
        for (Object[] r : projects.reviewProgressByProject(projectIds))
            out.put(((Number) r[0]).longValue(), new long[] {((Number) r[1]).longValue(), ((Number) r[2]).longValue()});
        return out;
    }

    private ProjectDto dto(Project p) {
        return ProjectDto.of(p, !finalReviewIds(List.of(p.getId())).isEmpty(), progress(List.of(p.getId())).get(p.getId()));
    }

    @PostMapping
    public ResponseEntity<ProjectDto> upload(@RequestParam("file") MultipartFile file,
                                             @RequestParam(value = "gameId", required = false) Long gameId,
                                             @RequestParam(value = "gameName", required = false) String gameName,
                                             @RequestParam(value = "reviewOnly", required = false) Boolean reviewOnly) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectDto.of(service.upload(file, gameId, gameName, Boolean.TRUE.equals(reviewOnly)), false, null));
    }

    @GetMapping
    public List<ProjectDto> list(@RequestParam(value = "gameId", required = false) Long gameId) {
        List<Project> ps = service.list(gameId);
        List<Long> ids = ps.stream().map(Project::getId).toList();
        java.util.Set<Long> fin = finalReviewIds(ids);
        java.util.Map<Long, long[]> prog = progress(ids);
        return ps.stream().map(p -> ProjectDto.of(p, fin.contains(p.getId()), prog.get(p.getId()))).toList();
    }

    @GetMapping("/{id}")
    public ProjectDto get(@PathVariable Long id) { return dto(service.get(id)); }

    @PatchMapping("/{id}")
    public ProjectDto patch(@PathVariable Long id, @RequestBody ProjectPatch patch) { return dto(service.patch(id, patch)); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
