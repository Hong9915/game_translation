package com.gametrans.workspace;

import com.gametrans.common.NotFoundException;
import com.gametrans.normalize.NormalizeConfig;
import com.gametrans.normalize.NormalizeConfigRepository;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectRepository;
import com.gametrans.workspace.SheetScope.SheetRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 요청 파라미터를 SheetScope로 바꾼다. 다른 게임의 시트·모르는 시트는 여기서 400으로 막는다. */
@Service
public class SheetScopes {
    private final ProjectRepository projects;
    private final NormalizeConfigRepository configs;
    private final com.gametrans.normalize.SegmentRepository segments;

    public SheetScopes(ProjectRepository projects, NormalizeConfigRepository configs, com.gametrans.normalize.SegmentRepository segments) {
        this.projects = projects;
        this.configs = configs;
        this.segments = segments;
    }

    /** 파일 화면: 프로젝트 하나의 시트 하나. 설정이 없는 시트여도(세그먼트만 남은 경우) 그대로 범위가 된다. */
    public SheetScope single(Long projectId, String sheet) {
        if (sheet == null || sheet.isBlank()) throw new IllegalArgumentException("시트를 지정하세요.");
        Project p = project(projectId);
        java.util.Optional<NormalizeConfig> cfg = configs.findByProjectIdAndSheetName(projectId, sheet);
        Long configId = cfg.map(NormalizeConfig::getId).orElse(null);
        // 설정 없이 행만 남은 시트는 행의 레인을 본다
        boolean fin = cfg.map(NormalizeConfig::isFinalReview)
                .orElseGet(() -> segments.existsByProjectIdAndSheetNameAndFinalReviewTrue(projectId, sheet));
        return new SheetScope(p.getGame().getId(), List.of(new SheetRef(configId, projectId, sheet, fin)), fin);
    }

    /** 파일 하나 전체(설정 순). 파일 화면의 시트 없는 요약 등에 쓴다. */
    public List<SheetRef> projectSheets(Long projectId) {
        return configs.findByProjectIdOrderByIdAsc(projectId).stream().map(c -> new SheetRef(c.getId(), projectId, c.getSheetName(), c.isFinalReview())).toList();
    }

    /** 게임의 설정된 시트 전부, 게임 순서(파일 업로드 순 → 설정 순). */
    public List<SheetRef> gameSheets(Long gameId) {
        List<Project> ps = new ArrayList<>(projects.findByGameIdOrderByUploadedAtDesc(gameId));
        ps.sort(Comparator.comparing(Project::getUploadedAt).thenComparing(Project::getId));
        List<SheetRef> out = new ArrayList<>();
        for (Project p : ps) out.addAll(projectSheets(p.getId()));
        return out;
    }

    /** 게임 화면: 고른 설정 id들(비면 기본 레인 전부). 두 레인이 섞이면 400. 결과 순서는 고른 순서가 아니라 게임 순서다. */
    public SheetScope ofGame(Long gameId, List<Long> configIds) {
        List<SheetRef> all = gameSheets(gameId);
        if (all.isEmpty()) throw new IllegalArgumentException("이 게임에는 설정된 시트가 없습니다.");
        if (configIds == null || configIds.isEmpty()) {
            // 고른 시트가 없으면 기본 레인: 최종 리뷰 시트가 하나라도 있으면 최종 리뷰(설계 2026-10-17)
            boolean fin = all.stream().anyMatch(SheetRef::finalReview);
            return new SheetScope(gameId, all.stream().filter(r -> r.finalReview() == fin).toList(), fin);
        }
        Set<Long> want = new HashSet<>(configIds);
        List<SheetRef> picked = all.stream().filter(r -> want.contains(r.configId())).toList();
        if (picked.size() != want.size()) throw new IllegalArgumentException("이 게임의 시트가 아닙니다.");
        if (picked.stream().map(SheetRef::finalReview).distinct().count() > 1)
            throw new IllegalArgumentException("최종 리뷰 시트와 일반 시트는 함께 볼 수 없습니다.");
        return new SheetScope(gameId, picked, picked.get(0).finalReview());
    }

    /** 쿼리 파라미터 "12,15,18"을 id 목록으로. 비었으면 빈 목록. */
    public static List<Long> parseIds(String sheets) {
        if (sheets == null || sheets.isBlank()) return List.of();
        List<Long> out = new ArrayList<>();
        for (String s : sheets.split(",")) {
            if (s.isBlank()) continue;
            try { out.add(Long.parseLong(s.trim())); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("시트 번호가 잘못됐습니다: " + s); }
        }
        return out;
    }

    public Project project(Long projectId) {
        return projects.findById(projectId).orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId));
    }
}
