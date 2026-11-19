package com.gametrans.reviewer;

import com.gametrans.common.NotFoundException;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.review.ReviewEvents;
import com.gametrans.translate.TranslationRevisionRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewerService {
    /** 담당 표시 색. 등록 순서대로 돌려 쓴다. */
    static final List<String> PALETTE = List.of("#1f4b99", "#0c8599", "#d6336c", "#6b3fa0", "#c4571a", "#1e7a5a", "#5c6bc0", "#a61e4d");

    public record ReviewerRef(Long id, String name, String color) {}

    public static ReviewerRef ref(Reviewer r) { return r == null ? null : new ReviewerRef(r.getId(), r.getName(), r.getColor()); }

    private final ReviewerRepository reviewers;
    private final SegmentRepository segments;
    private final TranslationRevisionRepository revisions;
    private final ReviewEvents events;

    public ReviewerService(ReviewerRepository reviewers, SegmentRepository segments, TranslationRevisionRepository revisions, ReviewEvents events) {
        this.reviewers = reviewers;
        this.segments = segments;
        this.revisions = revisions;
        this.events = events;
    }

    /** 이름을 추가하면 바로 등록된다. 같은 이름이 있으면 그 검토자를 돌려준다. */
    public Reviewer upsert(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("이름이 비어 있습니다.");
        String trimmed = name.trim();
        return reviewers.findByName(trimmed).orElseGet(() -> {
            Reviewer r = new Reviewer();
            r.setName(trimmed);
            r.setColor(PALETTE.get((int) (reviewers.count() % PALETTE.size())));
            try {
                return reviewers.save(r);
            } catch (DataIntegrityViolationException e) {
                // 두 사람이 동시에 같은 이름을 추가한 경우
                return reviewers.findByName(trimmed).orElseThrow(() -> e);
            }
        });
    }

    /** 헤더의 검토자 id. 없으면 익명(null), 모르는 id면 400. */
    public Reviewer resolve(Long id) {
        if (id == null) return null;
        return reviewers.findById(id).orElseThrow(() -> new IllegalArgumentException("모르는 검토자입니다: " + id));
    }

    /** 커넥터 쓰기에 검수자가 없을 때(헤더 없음, 또는 MCP의 계정 확인 캐시 60초 안에 지워진 검수자). */
    public static final String CONNECTOR_REVIEWER_MISSING = "검수자를 찾을 수 없습니다. Claude에서 커넥터를 다시 연결해 주세요.";

    /** 커넥터 쓰기용: resolve와 달리 헤더가 없어도 익명으로 넘기지 않는다 — 없거나 모르는 id면 400. */
    public Reviewer requireForConnector(Long id) {
        if (id == null) throw new IllegalArgumentException(CONNECTOR_REVIEWER_MISSING);
        return reviewers.findById(id).orElseThrow(() -> new IllegalArgumentException(CONNECTOR_REVIEWER_MISSING));
    }

    public boolean exists(Long id) { return id != null && reviewers.existsById(id); }

    public List<Reviewer> all() { return reviewers.findAllByOrderByIdAsc(); }

    public Map<Long, Reviewer> byId() {
        Map<Long, Reviewer> out = new LinkedHashMap<>();
        for (Reviewer r : all()) out.put(r.getId(), r);
        return out;
    }

    /**
     * 오타로 등록된 검토자 이름을 바꾼다. 다른 검토자가 이미 쓰는 이름이면 거절한다.
     * name UNIQUE 제약이 있어, 사전 확인(findByName) 뒤 다른 요청이 먼저 같은 이름을 차지하면
     * updateName이 DataIntegrityViolationException으로 막힌다 — 이때도 500이 아니라 같은 400으로 답해야 한다.
     * (그 순간 트랜잭션은 이미 롤백 대상이라, DB를 더 건드리지 않고 예외만 바꿔 바로 던진다.)
     */
    @Transactional
    public ReviewerRef rename(Long id, String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("이름이 비어 있습니다.");
        String trimmed = name.trim();
        Reviewer target = reviewers.findById(id).orElseThrow(() -> new NotFoundException("모르는 검토자입니다: " + id));
        reviewers.findByName(trimmed).ifPresent(other -> {
            if (!other.getId().equals(id)) throw new IllegalArgumentException("이미 있는 이름입니다: " + trimmed);
        });
        List<Long> affected = segments.projectIdsTouchedByReviewer(id);
        try {
            reviewers.updateName(id, trimmed);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("이미 있는 이름입니다: " + trimmed, e);
        }
        refreshProjects(affected);
        return new ReviewerRef(target.getId(), trimmed, target.getColor());
    }

    /**
     * 검토자를 지운다. 담당·완료 표시와 수정 기록에 남은 id는 transferTo로 옮기거나(없으면 NULL) 비운 뒤 지운다 —
     * FK가 걸린 칸이 남아 있으면 지울 수 없다.
     */
    @Transactional
    public void delete(Long id, Long transferTo) {
        // 존재 확인이 먼저다 — id 자체가 모르는 검토자면 transferTo가 우연히 같은 값이어도(자기 이관처럼 보여도) 404다.
        if (!reviewers.existsById(id)) throw new NotFoundException("모르는 검토자입니다: " + id);
        if (transferTo != null && transferTo.equals(id)) throw new IllegalArgumentException("자기 자신에게 옮길 수 없습니다.");
        if (transferTo != null && !reviewers.existsById(transferTo)) throw new NotFoundException("모르는 검토자입니다: " + transferTo);
        // FK가 옮겨지기 전에 모아야 한다 — 옮기고 나면 이 검토자의 흔적이 없어 어느 프로젝트였는지 알 수 없다.
        List<Long> affected = segments.projectIdsTouchedByReviewer(id);
        segments.moveAssignee(id, transferTo);
        segments.moveDoneBy(id, transferTo);
        revisions.moveReviewer(id, transferTo);
        reviewers.deleteById(id);
        refreshProjects(affected);
    }

    /**
     * 이름이 바뀌거나 사라진 검토자가 보이던 프로젝트 화면들에게 목록을 다시 읽으라고 알린다.
     * 담당·완료·수정 기록이 걸린 행이 한꺼번에 많이 바뀌어 행 단위로 알리기는 어렵다.
     * (ReviewEvents는 트랜잭션 안에서는 커밋 뒤로 미뤄 보내므로 여기서 바로 불러도 된다.)
     */
    private void refreshProjects(List<Long> projectIds) {
        for (Long projectId : projectIds) events.refresh(projectId, null);
    }
}
