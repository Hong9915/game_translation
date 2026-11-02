package com.gametrans.review;

import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectRepository;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원문이 글자까지 똑같은 행은 같은 번역이어야 한다는 규칙. 예외가 없는 규칙이라 단어장에 등록하지 않고 저장할 때 바로 돈다.
 * 범위는 그 게임의 모든 파일. 완료 행은 건드리지 않는다.
 */
@Service
public class DuplicateSyncService {
    private final SegmentRepository segments;
    private final ProjectRepository projects;
    private final BulkFixWriter writer;

    public DuplicateSyncService(SegmentRepository segments, ProjectRepository projects, BulkFixWriter writer) {
        this.segments = segments;
        this.projects = projects;
        this.writer = writer;
    }

    /** saved와 원문이 같은 다른 행을 text로 맞춘다. 실제로 바뀐 행 수를 돌려준다. */
    @Transactional
    public int syncFrom(Segment saved, String text, Long reviewerId) { return syncFrom(saved, text, reviewerId, pid -> null); }

    /** activityFor: 따라 바뀌는 행의 리비전을 매달 변경 내역(사람 저장의 EDIT). */
    @Transactional
    public int syncFrom(Segment saved, String text, Long reviewerId, Function<Long, Long> activityFor) {
        String src = saved.getSrcText();
        if (src == null || src.isBlank() || text == null || text.isBlank()) return 0;
        Long gameId = projects.findById(saved.getProjectId()).map(Project::getGame).map(g -> g.getId()).orElse(null);
        if (gameId == null) return 0;
        List<Segment> same = segments.findSameSourceInGame(gameId, saved.isFinalReview(), src, saved.getId());
        return writer.write(same, s -> text, reviewerId, activityFor);
    }
}
