package com.gametrans.review;

import com.gametrans.job.JobEventBus;
import com.gametrans.review.ReviewDtos.SegmentRow;
import com.gametrans.reviewer.ReviewerService.ReviewerRef;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 공동 검토의 실시간 이벤트. 새 SSE 연결을 만들지 않고 프로젝트 작업 스트림에 "review" 이벤트로 실어 보낸다.
 * 트랜잭션 안에서 불리면 커밋된 뒤에 보낸다 — 받은 쪽이 바로 조회해도 새 값을 봐야 한다.
 */
@Component
public class ReviewEvents {
    public static final String EVENT = "review";
    /** Claude 제안 알림 전용 이름. 옛 프런트(review만 구독)가 payload 모양을 몰라 죽지 않도록 따로 보낸다. */
    public static final String PROPOSAL_EVENT = "proposal";

    /** 단어장 후보(승인 대기) 알림 전용 이름 — review 이벤트에 새 type을 싣지 않는다(옛 탭 보호). */
    public static final String GLOSSARY_CANDIDATE_EVENT = "glossary-candidate";

    /** by: 행동한 검토자 id. 익명이거나 GPT 작업이면 null. */
    public record SegmentUpdated(String type, Long by, SegmentRow row) {}
    public record Assigned(String type, Long by, List<Long> segmentIds, ReviewerRef assignee) {}
    /** 검수 체크·비고가 바뀐 행. null인 체크는 안 바뀐 것. noteChanged면 note(null이면 지움)를 반영한다. */
    public record Marks(String type, Long by, List<Long> segmentIds, Boolean review1, Boolean review2, Boolean review3, Boolean needsCheck,
                        Boolean confirmed, String note, boolean noteChanged, Boolean finalMark) {}
    /** 일괄 체크가 많아 행 목록을 싣지 않을 때: 그 시트의 목록·요약을 다시 읽으라는 신호. */
    public record MarksBulk(String type, Long by, String sheet) {}
    /** 여러 행이 한꺼번에 바뀌어 행 단위로 알리기 어려울 때(용어 수정, 검토자 정리): 목록을 다시 읽으라는 신호. */
    public record Refresh(String type, Long by) {}
    /** 단어장 항목이 바뀌었다: 열린 단어장 패널이 목록을 다시 읽으라는 신호. 행이 안 바뀌어도 보낸다. */
    public record GlossaryChanged(String type, Long by) {}
    /** Claude 제안이 만들어지거나(connector-proposal) 처리됐다(connector-proposal-decided): 창·칩이 목록을 다시 읽으라는 신호. */
    public record ProposalEvent(String type, Long proposalId, Long reviewerId, Long gameId, String status) {}
    /** 후보가 생기거나 처리됐다: 후보 목록·배지를 다시 읽으라는 신호. type은 항상 "glossary-candidate", status는 후보의 새 상태. */
    public record CandidateEvent(String type, Long gameId, Long candidateId, String status) {}
    public record PresenceEntry(Long id, String name, String color, String sheet, Long editingSegmentId) {}
    public record PresenceSnapshot(String type, List<PresenceEntry> reviewers) {}

    private final JobEventBus bus;
    private final com.gametrans.project.ProjectRepository projects;
    /** 프로젝트 → 게임. 프로젝트의 게임은 바뀌지 않으므로(옮기기는 드묾) 한 번 찾은 값을 둔다. 지운 프로젝트는 이벤트도 없다. */
    private final java.util.Map<Long, Long> gameOf = new java.util.concurrent.ConcurrentHashMap<>();

    public ReviewEvents(JobEventBus bus, com.gametrans.project.ProjectRepository projects) {
        this.bus = bus;
        this.projects = projects;
    }

    public void segmentUpdated(Long projectId, Long by, SegmentRow row) { send(projectId, new SegmentUpdated("segment-updated", by, row)); }

    public void assigned(Long projectId, Long by, List<Long> segmentIds, ReviewerRef assignee) { send(projectId, new Assigned("assigned", by, segmentIds, assignee)); }

    public void marks(Long projectId, Long by, List<Long> segmentIds, ReviewMarkService.Applied a, String note, boolean noteChanged) {
        send(projectId, new Marks("marks", by, segmentIds, a.review1(), a.review2(), a.review3(), a.needsCheck(), a.confirmed(), note, noteChanged, a.finalMark()));
    }

    public void marksBulk(Long projectId, Long by, String sheet) { send(projectId, new MarksBulk("marks-bulk", by, sheet)); }

    public void refresh(Long projectId, Long by) { send(projectId, new Refresh("refresh", by)); }

    public void glossaryChanged(Long projectId, Long by) { send(projectId, new GlossaryChanged("glossary", by)); }

    /** 게임 스트림(구독자가 있든 없든)과 게임의 각 파일 스트림에 보낸다. 트랜잭션 안이면 커밋 뒤에. */
    public void proposal(Long gameId, java.util.Collection<Long> projectIds, ProposalEvent payload) {
        afterCommit(() -> {
            bus.publishGameEvent(gameId, PROPOSAL_EVENT, payload);
            for (Long pid : projectIds) bus.publishProjectEvent(pid, PROPOSAL_EVENT, payload);
        });
    }

    public void glossaryCandidate(Long gameId, java.util.Collection<Long> projectIds, CandidateEvent payload) {
        afterCommit(() -> {
            bus.publishGameEvent(gameId, GLOSSARY_CANDIDATE_EVENT, payload);
            for (Long pid : projectIds) bus.publishProjectEvent(pid, GLOSSARY_CANDIDATE_EVENT, payload);
        });
    }

    /** room이 음수면 게임 화면 방(PresenceService.gameRoom) — 게임 스트림으로만 보낸다. */
    public void presence(Long room, List<PresenceEntry> reviewers) {
        PresenceSnapshot payload = new PresenceSnapshot("presence", reviewers);
        if (room < 0) bus.publishGameEvent(-room, EVENT, payload);
        else bus.publishProjectEvent(room, EVENT, payload);
    }

    /** 프로젝트 스트림과, 그 게임을 보는 게임 화면 스트림에 같이 보낸다. */
    private void send(Long projectId, Object payload) {
        Long gameId = bus.hasGameSubscribers() ? gameId(projectId) : null;
        Runnable publish = () -> {
            bus.publishProjectEvent(projectId, EVENT, payload);
            if (gameId != null) bus.publishGameEvent(gameId, EVENT, payload);
        };
        afterCommit(publish);
    }

    private static void afterCommit(Runnable publish) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else {
            publish.run();
        }
    }

    private Long gameId(Long projectId) {
        Long cached = gameOf.get(projectId);
        if (cached != null) return cached;
        Long found = projects.findById(projectId).map(p -> p.getGame().getId()).orElse(null);
        if (found != null) gameOf.put(projectId, found);
        return found;
    }
}
