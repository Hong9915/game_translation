package com.gametrans.translate;

import com.gametrans.common.NotFoundException;
import com.gametrans.game.Game;
import com.gametrans.game.GameService;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectRepository;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 검수 그리드에서 고른 여러 행을 읽고 검사한다(여러 행 번역·다듬기·일괄 넣기 공통).
 * 1~max행이어야 하고(아니면 400), 없는 행은 404, 여러 게임에 걸치면 400.
 */
@Component
class SegmentBatch {
    private final SegmentRepository segments;
    private final ProjectRepository projects;
    private final GameService games;

    SegmentBatch(SegmentRepository segments, ProjectRepository projects, GameService games) {
        this.segments = segments;
        this.projects = projects;
        this.games = games;
    }

    /** ids: 중복·null을 뺀 요청 순서. */
    record Loaded(List<Long> ids, Map<Long, Segment> byId, Map<Long, Project> projectOf, Game game) {}

    /** verb: 메시지에 들어갈 말("번역할", "다듬을", "넣을"). */
    Loaded load(List<Long> requested, int max, String verb) {
        List<Long> ids = requested == null ? List.of() : requested.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty() || ids.size() > max) throw new IllegalArgumentException("한 번에 1~" + max + "행을 " + verb + " 수 있습니다.");
        Map<Long, Segment> byId = new HashMap<>();
        for (Segment s : segments.findAllById(ids)) byId.put(s.getId(), s);
        for (Long id : ids) if (!byId.containsKey(id)) throw new NotFoundException("세그먼트를 찾을 수 없습니다: " + id);
        Map<Long, Project> projectOf = new HashMap<>();
        Set<Long> gameIds = new LinkedHashSet<>();
        for (Segment s : byId.values()) {
            Project p = projectOf.computeIfAbsent(s.getProjectId(),
                    pid -> projects.findById(pid).orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + pid)));
            gameIds.add(p.getGame().getId());
        }
        if (gameIds.size() > 1) throw new IllegalArgumentException("한 게임의 행만 함께 " + verb + " 수 있습니다.");
        return new Loaded(ids, byId, projectOf, games.get(gameIds.iterator().next()));
    }

    /** 확정·제외 행은 일괄 작업에서 뺀다. 뺄 이유, 없으면 null. */
    static String lockedReason(Segment s) {
        if (s.isConfirmed()) return "확정";
        if (s.isSkipped()) return "제외";
        return null;
    }
}
