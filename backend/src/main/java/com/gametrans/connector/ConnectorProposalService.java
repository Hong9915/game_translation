package com.gametrans.connector;

import com.gametrans.common.NotFoundException;
import com.gametrans.connector.ConnectorDtos.ProposalDetail;
import com.gametrans.connector.ConnectorDtos.ProposalResult;
import com.gametrans.connector.ConnectorDtos.ProposalItem;
import com.gametrans.connector.ConnectorDtos.ProposalRow;
import com.gametrans.connector.ConnectorDtos.ProposalSummary;
import com.gametrans.connector.ConnectorDtos.ProposeRequest;
import com.gametrans.connector.ConnectorDtos.ProposeResult;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectRepository;
import com.gametrans.review.PresenceService;
import com.gametrans.review.ReviewEvents;
import com.gametrans.review.ReviewEvents.ProposalEvent;
import com.gametrans.reviewer.ReviewerService;
import com.gametrans.translate.PolishService;
import com.gametrans.translate.SegmentBatch;
import com.gametrans.translate.TranslateDtos.SaveBatchRequest;
import com.gametrans.translate.TranslateDtos.SaveBatchResult;
import com.gametrans.translate.TranslateDtos.SaveItem;
import com.gametrans.translate.TranslateDtos.Skip;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Claude 제안: 저장·목록·상세. 넣기·넣지 않음은 이 위에 따로 얹는다. */
@Service
public class ConnectorProposalService {
    static final Duration TTL = Duration.ofHours(24);
    static final int MAX_ROWS = 500;
    static final int MAX_TITLE = 200;

    private final ConnectorProposalRepository repo;
    private final ReviewerService reviewers;
    private final SegmentBatch batch;
    private final ProjectRepository projects;
    private final PresenceService presence;
    private final ReviewEvents events;
    private final JdbcTemplate jdbc;
    private final PolishService polish;
    private final com.gametrans.glossary.GlossaryCandidateService candidates;

    public ConnectorProposalService(ConnectorProposalRepository repo, ReviewerService reviewers, SegmentBatch batch, ProjectRepository projects,
                                    PresenceService presence, ReviewEvents events, JdbcTemplate jdbc, PolishService polish,
                                    com.gametrans.glossary.GlossaryCandidateService candidates) {
        this.repo = repo;
        this.reviewers = reviewers;
        this.batch = batch;
        this.projects = projects;
        this.presence = presence;
        this.events = events;
        this.jdbc = jdbc;
        this.polish = polish;
        this.candidates = candidates;
    }

    @Transactional
    public ProposeResult propose(ProposeRequest req, Long reviewerId) {
        return create(reviewerId, req == null ? null : req.title(), req == null ? null : req.items(), null, null, null);
    }

    /** 제안 저장 본체. term 셋은 용어 바꾸기일 때만(없으면 null). */
    @Transactional
    public ProposeResult create(Long reviewerId, String reqTitle, List<ProposalItem> items, String termSrc, String termKo, String termOld) {
        Long me = reviewers.requireForConnector(reviewerId).getId();
        if (items == null || items.isEmpty() || items.size() > MAX_ROWS)
            throw new IllegalArgumentException("한 번에 1~" + MAX_ROWS + "행을 제안할 수 있습니다.");
        Set<Long> seen = new HashSet<>();
        for (ProposalItem it : items) {
            if (it == null || it.segmentId() == null) throw new IllegalArgumentException("segmentId가 없습니다.");
            if (!seen.add(it.segmentId())) throw new IllegalArgumentException("같은 행이 두 번 들어 있습니다: " + it.segmentId());
        }
        for (ProposalItem it : items)
            if (it.text() == null || it.text().isBlank()) throw new IllegalArgumentException("빈 글은 제안할 수 없습니다: " + it.segmentId());
        List<Long> ids = items.stream().map(ProposalItem::segmentId).toList();
        SegmentBatch.Loaded loaded = batch.load(ids, MAX_ROWS, "제안할");
        Long gameId = loaded.game().getId();

        String title = reqTitle == null ? null : reqTitle.trim();
        if (title != null && title.isEmpty()) title = null;
        if (title != null && title.length() > MAX_TITLE) title = title.substring(0, MAX_TITLE);

        ConnectorProposal p = new ConnectorProposal();
        p.setReviewerId(me);
        p.setGameId(gameId);
        p.setTitle(title);
        Map<Long, Now> nowOfAuto = nowOf(items.stream().filter(it -> Boolean.TRUE.equals(it.autoBase())).map(ProposalItem::segmentId).toList());
        p.setItems(items.stream().map(it -> {
            String base = it.base();
            if (Boolean.TRUE.equals(it.autoBase())) { Now n = nowOfAuto.get(it.segmentId()); base = n == null ? null : n.fix(); }
            return new ProposalItem(it.segmentId(), it.text().trim(), base, it.reason(), null, it.srcMissing());
        }).collect(Collectors.toCollection(ArrayList::new)));
        p.setTermSrc(termSrc);
        p.setTermKo(termKo);
        p.setTermOld(termOld);
        p = repo.save(p);

        List<Skip> locked = new ArrayList<>();
        for (Long id : ids) {
            String why = SegmentBatch.lockedReason(loaded.byId().get(id));
            if (why != null) locked.add(new Skip(id, why));
        }
        List<Long> projectIds = projects.findByGameIdOrderByUploadedAtDesc(gameId).stream().map(Project::getId).toList();
        boolean present = presence.isPresent(PresenceService.gameRoom(gameId), me)
                || projectIds.stream().anyMatch(pid -> presence.isPresent(pid, me));
        events.proposal(gameId, projectIds, new ProposalEvent("connector-proposal", p.getId(), me, gameId, "PENDING"));
        return new ProposeResult(p.getId(), present, locked);
    }

    /** 처리 안 된, 만료 안 된 제안을 새것부터. */
    @Transactional(readOnly = true)
    public List<ProposalSummary> pending(Long reviewerId, Long gameId) {
        return repo.findByReviewerIdAndGameIdAndStatusAndCreatedAtAfterOrderByCreatedAtDescIdDesc(
                        reviewerId, gameId, "PENDING", Instant.now().minus(TTL)).stream()
                .map(p -> new ProposalSummary(p.getId(), p.getTitle(), p.getItems().size(), p.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProposalDetail detail(Long id) {
        ConnectorProposal p = repo.findById(id).orElseThrow(() -> new NotFoundException("제안을 찾을 수 없습니다: " + id));
        return new ProposalDetail(p.getId(), p.getReviewerId(), p.getGameId(), p.getTitle(), statusOf(p), p.getCreatedAt(), p.getDecidedAt(),
                rows(p.getItems()), p.getResult(), p.getTermSrc());
    }

    /** 체크한 행만 커넥터 저장으로 넣는다. saveBatch가 던지면 트랜잭션째 돌아가 제안은 PENDING으로 남는다. */
    @Transactional
    public ProposalDetail apply(Long id, List<Long> segmentIds, Long reviewerId) {
        ConnectorProposal p = lockPending(id, reviewerId);
        if (segmentIds == null || segmentIds.isEmpty()) throw new IllegalArgumentException("저장할 행을 하나 이상 체크해 주세요.");
        Set<Long> checked = new LinkedHashSet<>(segmentIds);
        Set<Long> inProposal = p.getItems().stream().map(ProposalItem::segmentId).collect(Collectors.toSet());
        for (Long sid : checked)
            if (sid == null || !inProposal.contains(sid)) throw new IllegalArgumentException("제안에 없는 행입니다: " + sid);

        List<ProposalItem> chosen = p.getItems().stream().filter(it -> checked.contains(it.segmentId())).toList();
        Set<Long> existing = existingIds(chosen.stream().map(ProposalItem::segmentId).toList());
        List<Skip> skippedRows = new ArrayList<>();
        List<SaveItem> toSave = new ArrayList<>();
        for (ProposalItem it : chosen) {
            if (existing.contains(it.segmentId())) toSave.add(new SaveItem(it.segmentId(), it.text(), it.base()));
            else skippedRows.add(new Skip(it.segmentId(), "행이 없어짐"));
        }
        // (saveBatch가 같은 원문 행에 글을 퍼뜨리기 전에 본다) 체크 안 한 행: 잠긴 것(그 사이 바뀜·확정·제외·행이 없어짐)은 사람이 뺀 게 아니므로 skippedRows로, 나머지만 excluded로
        List<ProposalItem> unchecked = p.getItems().stream().filter(it -> !checked.contains(it.segmentId())).toList();
        Map<Long, Now> nowOfUnchecked = nowOf(unchecked.stream().map(ProposalItem::segmentId).toList());
        List<Long> excluded = new ArrayList<>();
        for (ProposalItem it : unchecked) {
            String why = lockOf(it, nowOfUnchecked.get(it.segmentId()));
            if (why != null) skippedRows.add(new Skip(it.segmentId(), why));
            else excluded.add(it.segmentId());
        }
        SaveBatchResult saved = toSave.isEmpty() ? null : polish.saveBatch(new SaveBatchRequest(toSave, "CONNECTOR"), p.getReviewerId());
        if (saved != null) skippedRows.addAll(saved.skippedRows());
        p.setResult(new ProposalResult(saved == null ? 0 : saved.saved(), saved == null ? 0 : saved.unchanged(), saved == null ? 0 : saved.propagated(),
                excluded, skippedRows, saved == null ? 0 : saved.finalMarked(), saved == null ? List.of() : saved.activityIds()));
        if (p.getTermSrc() != null && saved != null && saved.saved() > 0)
            candidates.record(p.getGameId(), p.getTermSrc(), p.getTermKo(), p.getTermOld(), saved.saved(),
                    p.getItems().isEmpty() ? null : p.getItems().get(0).reason(), "CONNECTOR", p.getId(), p.getReviewerId());
        return decide(p, "APPLIED");
    }

    @Transactional
    public ProposalDetail dismiss(Long id, Long reviewerId) {
        ConnectorProposal p = lockPending(id, reviewerId);
        p.setResult(null);
        return decide(p, "DISMISSED");
    }

    /** 잠그고 검수자·상태를 본다. 다른 검수자는 400, 이미 처리됐거나 만료면 지금 상태를 실어 409. */
    private ConnectorProposal lockPending(Long id, Long reviewerId) {
        ConnectorProposal p = repo.findForUpdate(id).orElseThrow(() -> new NotFoundException("제안을 찾을 수 없습니다: " + id));
        Long me = reviewers.requireForConnector(reviewerId).getId();
        if (!Objects.equals(me, p.getReviewerId())) throw new IllegalArgumentException("이 제안은 다른 검수자에게 온 것입니다.");
        String st = statusOf(p);
        if ("EXPIRED".equals(st)) throw new ProposalDecidedException("만료된 제안입니다(만든 지 24시간이 지났습니다).", detail(id));
        if (!"PENDING".equals(st)) throw new ProposalDecidedException("이미 처리된 제안입니다.", detail(id));
        return p;
    }

    private ProposalDetail decide(ConnectorProposal p, String status) {
        p.setStatus(status);
        p.setDecidedAt(Instant.now());
        repo.save(p);
        List<Long> projectIds = projects.findByGameIdOrderByUploadedAtDesc(p.getGameId()).stream().map(Project::getId).toList();
        events.proposal(p.getGameId(), projectIds, new ProposalEvent("connector-proposal-decided", p.getId(), p.getReviewerId(), p.getGameId(), status));
        return detail(p.getId());
    }

    private Set<Long> existingIds(List<Long> ids) {
        Set<Long> out = new HashSet<>();
        if (ids.isEmpty()) return out;
        String in = ids.stream().map(i -> "?").collect(Collectors.joining(","));
        jdbc.query("select id from segment where id in (" + in + ")", (ResultSet rs) -> { out.add(rs.getLong("id")); }, ids.toArray());
        return out;
    }

    static String statusOf(ConnectorProposal p) {
        if ("PENDING".equals(p.getStatus()) && p.getCreatedAt().isBefore(Instant.now().minus(TTL))) return "EXPIRED";
        return p.getStatus();
    }

    private record Now(Long projectId, String fileName, String sheet, Integer rowNo, String indexLabel, String src, String fix,
                       boolean confirmed, boolean skipped, boolean finalReview) {}

    /** 한 번의 SQL로 지금 값을 읽는다. JPA 엔티티는 같은 트랜잭션 안에서 낡은 latest_ko를 들 수 있다. */
    private Map<Long, Now> nowOf(List<Long> ids) {
        Map<Long, Now> now = new java.util.HashMap<>();
        if (ids.isEmpty()) return now;
        String in = ids.stream().map(i -> "?").collect(Collectors.joining(","));
        jdbc.query("select s.id, s.project_id, p.file_name, s.sheet_name, s.row_no, s.index_label, s.src_text, s.latest_ko, "
                        + "s.confirmed, s.skipped, s.final_review from segment s join project p on p.id = s.project_id where s.id in (" + in + ")",
                (ResultSet rs) -> { now.put(rs.getLong("id"), read(rs)); }, ids.toArray());
        return now;
    }

    /** 이 행을 지금 넣을 수 없는 이유(없으면 null). 창의 잠금과 apply의 건너뜀이 같은 규칙을 쓴다. */
    private static String lockOf(ProposalItem it, Now n) {
        if (n == null) return "행이 없어짐";
        if (n.confirmed()) return "확정";
        if (n.skipped()) return "제외";
        if (Objects.equals(it.text(), n.fix())) return null;
        if (!Objects.equals(n.fix(), it.base())) return "그 사이 바뀜";
        return null;
    }

    private List<ProposalRow> rows(List<ProposalItem> items) {
        Map<Long, Now> now = nowOf(items.stream().map(ProposalItem::segmentId).toList());
        List<ProposalRow> out = new ArrayList<>();
        for (ProposalItem it : items) {
            Now n = now.get(it.segmentId());
            if (n == null) {
                out.add(new ProposalRow(it.segmentId(), null, null, null, null, null, null, null, it.base(), it.text(), it.reason(), false, "행이 없어짐", Boolean.TRUE.equals(it.srcMissing())));
                continue;
            }
            String lock = lockOf(it, n);
            out.add(new ProposalRow(it.segmentId(), n.projectId(), n.fileName(), n.sheet(), n.rowNo(), n.indexLabel(), n.src(), n.fix(),
                    it.base(), it.text(), it.reason(), n.finalReview(), lock, Boolean.TRUE.equals(it.srcMissing())));
        }
        return out;
    }

    private static Now read(ResultSet rs) throws SQLException {
        return new Now(rs.getLong("project_id"), rs.getString("file_name"), rs.getString("sheet_name"), rs.getInt("row_no"),
                rs.getString("index_label"), rs.getString("src_text"), rs.getString("latest_ko"),
                rs.getBoolean("confirmed"), rs.getBoolean("skipped"), rs.getBoolean("final_review"));
    }
}
