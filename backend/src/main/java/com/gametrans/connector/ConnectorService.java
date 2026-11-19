package com.gametrans.connector;

import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityDtos.UndoRequest;
import com.gametrans.activity.ActivityDtos.UndoResult;
import com.gametrans.activity.ActivityRepository;
import com.gametrans.activity.ActivityService;
import com.gametrans.common.NotFoundException;
import com.gametrans.common.UnauthorizedException;
import com.gametrans.connector.ConnectorDtos.AccountDto;
import com.gametrans.connector.ConnectorDtos.NoteItem;
import com.gametrans.connector.ConnectorDtos.NoteResult;
import com.gametrans.connector.ConnectorDtos.NotesRequest;
import com.gametrans.connector.ConnectorDtos.SheetDto;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.review.ReviewEvents;
import com.gametrans.review.ReviewMarkService.Applied;
import com.gametrans.reviewer.ReviewerService;
import com.gametrans.workspace.SheetScopes;
import com.gametrans.reviewer.ReviewerService.ReviewerRef;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConnectorService {
    static final String WRONG = "아이디 또는 비밀번호가 틀렸습니다.";

    static final int MAX_NOTES = 500;
    static final int MAX_UNDO = 50;
    /** REVERT의 revertOf 사슬을 따라갈 최대 깊이. 되돌리기·다시 실행을 이보다 많이 거듭한 항목은 받지 않는다. */
    static final int MAX_REVERT_CHAIN = 20;

    private final JdbcTemplate jdbc;
    private final SheetScopes scopes;
    private final SegmentRepository segments;
    private final ReviewerService reviewers;
    private final ReviewEvents events;
    private final ActivityRepository activityRepo;
    private final ActivityService activities;

    public ConnectorService(JdbcTemplate jdbc, SheetScopes scopes, SegmentRepository segments, ReviewerService reviewers, ReviewEvents events,
                            ActivityRepository activityRepo, ActivityService activities) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.segments = segments;
        this.reviewers = reviewers;
        this.events = events;
        this.activityRepo = activityRepo;
        this.activities = activities;
    }

    private static AccountDto map(ResultSet rs, int n) throws SQLException {
        long rid = rs.getLong("reviewer_id");
        ReviewerRef ref = rs.wasNull() ? null : new ReviewerRef(rid, rs.getString("name"), rs.getString("color"));
        return new AccountDto(rs.getLong("id"), rs.getBoolean("disabled"), ref);
    }

    /** 비밀번호 불일치·막힘·검수자 없음은 어느 쪽인지 알리지 않고 같은 401로 거절한다. */
    public AccountDto login(String loginId, String password) {
        if (loginId == null || password == null) throw new UnauthorizedException(WRONG);
        List<AccountDto> found = jdbc.query(
                "select a.id, a.disabled, a.reviewer_id, r.name, r.color from connector_account a "
                        + "left join reviewer r on r.id = a.reviewer_id "
                        + "where a.login_id = ? and a.password_hash = crypt(?, a.password_hash)",
                ConnectorService::map, loginId, password);
        if (found.isEmpty() || found.get(0).disabled() || found.get(0).reviewer() == null) throw new UnauthorizedException(WRONG);
        return found.get(0);
    }

    /** 게임의 시트 전부(게임 순서)와 그 시트가 속한 파일 이름. */
    public List<SheetDto> sheets(Long gameId) {
        Map<Long, String> names = new HashMap<>();
        return scopes.gameSheets(gameId).stream()
                .map(r -> new SheetDto(r.configId(), r.projectId(),
                        names.computeIfAbsent(r.projectId(), id -> scopes.project(id).getFileName()), r.sheet(), r.finalReview()))
                .toList();
    }

    /**
     * 비고 붙이기·확인 필요 켜기. 확정 행에도 쓴다. 변경 내역(activity)은 남기지 않고, 다른 화면에는
     * 같은 SSE "marks"로 알린다(ReviewMarkService.update와 같다).
     */
    @Transactional
    public List<NoteResult> notes(NotesRequest req, Long reviewerId) {
        List<NoteItem> items = req == null ? null : req.items();
        if (items == null || items.isEmpty() || items.size() > MAX_NOTES)
            throw new IllegalArgumentException("한 번에 1~500행에 비고를 달 수 있습니다.");
        Long by = reviewers.requireForConnector(reviewerId).getId();
        List<NoteResult> out = new ArrayList<>();
        for (NoteItem it : items) {
            if (it.segmentId() == null) throw new IllegalArgumentException("segmentId가 없습니다.");
            Segment seg = segments.findById(it.segmentId())
                    .orElseThrow(() -> new NotFoundException("세그먼트를 찾을 수 없습니다: " + it.segmentId()));
            String before = seg.getNote() == null ? "" : seg.getNote();
            boolean noteChanged = it.note() != null;
            String after = before;
            if (noteChanged) {
                String text = it.note().trim();
                boolean replace = "REPLACE".equalsIgnoreCase(it.mode());
                after = replace || before.isBlank() ? text : text.isEmpty() ? before : before + "\n" + text;
                segments.updateNote(seg.getId(), after.isBlank() ? null : after);
            }
            Applied applied = new Applied(null, null, null, it.needsCheck(), null, null);
            if (it.needsCheck() != null)
                segments.updateMarks(List.of(seg.getId()), null, null, null, it.needsCheck(), null, null);
            boolean needsCheck = segments.findById(seg.getId()).orElseThrow().isNeedsCheck();
            events.marks(seg.getProjectId(), by, List.of(seg.getId()), applied, after.isBlank() ? null : after, noteChanged);
            out.add(new NoteResult(seg.getId(), before, after, needsCheck));
        }
        return out;
    }

    /**
     * 커넥터 되돌리기: 부른 검수자가 커넥터로 직접 고친 것만 받는다(웹 /api/activity/undo는 그대로 아무 id나 받는다).
     * 받는 id = 그 검수자의 CONNECTOR, 또는 그 검수자의 REVERT로 revertOf 사슬(최대 20단)이 그 검수자의 CONNECTOR에 닿는 것(되돌림·다시 실행).
     * 하나라도 아니면 400, 모르는 id면 404 — 모두 확인한 뒤에만 ActivityService.undo에 넘기므로 그때는 아무것도 되돌리지 않는다.
     * 순번 id라 잘못 넣거나 끼워 넣은 id로 남의 작업이나 확정 행까지 고치는 JOSA_FIX를 되돌리는 일을 막는다.
     */
    @Transactional
    public UndoResult undo(UndoRequest req, Long reviewerId) {
        Long by = reviewers.requireForConnector(reviewerId).getId();
        List<Long> ids = req == null ? null : req.activityIds();
        if (ids == null || ids.isEmpty() || ids.size() > MAX_UNDO)
            throw new IllegalArgumentException("한 번에 1~" + MAX_UNDO + "개 변경 내역을 되돌릴 수 있습니다.");
        if (ids.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("activityIds에 빈 값이 있습니다.");
        for (Long id : ids) {
            Activity a = activityRepo.findById(id).orElseThrow(() -> new NotFoundException("변경 내역을 찾을 수 없습니다: " + id));
            if (!ownConnectorWork(a, by))
                throw new IllegalArgumentException("되돌릴 수 없는 변경 내역입니다: " + id + " — 커넥터로 직접 고친 것만 되돌릴 수 있습니다.");
        }
        return activities.undo(ids, by);
    }

    /** 사슬의 모든 항목이 그 검수자의 것이고, REVERT를 revertOf로 따라가 그 검수자의 CONNECTOR에 닿으면 참. */
    private boolean ownConnectorWork(Activity a, Long reviewerId) {
        Activity cur = a;
        for (int depth = 0; depth <= MAX_REVERT_CHAIN; depth++) {
            if (!reviewerId.equals(cur.getReviewerId())) return false;
            if (cur.getKind() == Activity.Kind.CONNECTOR) return true;
            if (cur.getKind() != Activity.Kind.REVERT || cur.getDetail() == null
                    || !(cur.getDetail().get("revertOf") instanceof Number of)) return false;
            Activity next = activityRepo.findById(of.longValue()).orElse(null);
            if (next == null) return false;
            cur = next;
        }
        return false;
    }

    public AccountDto account(Long id) {
        List<AccountDto> found = jdbc.query(
                "select a.id, a.disabled, a.reviewer_id, r.name, r.color from connector_account a "
                        + "left join reviewer r on r.id = a.reviewer_id where a.id = ?",
                ConnectorService::map, id);
        if (found.isEmpty()) throw new NotFoundException("계정을 찾을 수 없습니다.");
        return found.get(0);
    }
}
