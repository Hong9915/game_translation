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

    /** by: 행동한 검토자 id. 익명이거나 GPT 작업이면 null. */
    public record SegmentUpdated(String type, Long by, SegmentRow row) {}
    public record Assigned(String type, Long by, List<Long> segmentIds, ReviewerRef assignee) {}
    /** 검수 체크·비고가 바뀐 행. null인 체크는 안 바뀐 것. noteChanged면 note(null이면 지움)를 반영한다. */
    public record Marks(String type, Long by, List<Long> segmentIds, Boolean review1, Boolean review2, Boolean needsCheck,
                        Boolean confirmed, String note, boolean noteChanged) {}
    /** 일괄 체크가 많아 행 목록을 싣지 않을 때: 그 시트의 목록·요약을 다시 읽으라는 신호. */
    public record MarksBulk(String type, Long by, String sheet) {}
    /** 여러 행이 한꺼번에 바뀌어 행 단위로 알리기 어려울 때(용어 수정, 검토자 정리): 목록을 다시 읽으라는 신호. */
    public record Refresh(String type, Long by) {}
    /** 단어장 항목이 바뀌었다: 열린 단어장 패널이 목록을 다시 읽으라는 신호. 행이 안 바뀌어도 보낸다. */
    public record GlossaryChanged(String type, Long by) {}
    public record PresenceEntry(Long id, String name, String color, String sheet, Long editingSegmentId) {}
    public record PresenceSnapshot(String type, List<PresenceEntry> reviewers) {}

    private final JobEventBus bus;

    public ReviewEvents(JobEventBus bus) { this.bus = bus; }

    public void segmentUpdated(Long projectId, Long by, SegmentRow row) { send(projectId, new SegmentUpdated("segment-updated", by, row)); }

    public void assigned(Long projectId, Long by, List<Long> segmentIds, ReviewerRef assignee) { send(projectId, new Assigned("assigned", by, segmentIds, assignee)); }

    public void marks(Long projectId, Long by, List<Long> segmentIds, ReviewMarkService.Applied a, String note, boolean noteChanged) {
        send(projectId, new Marks("marks", by, segmentIds, a.review1(), a.review2(), a.needsCheck(), a.confirmed(), note, noteChanged));
    }

    public void marksBulk(Long projectId, Long by, String sheet) { send(projectId, new MarksBulk("marks-bulk", by, sheet)); }

    public void refresh(Long projectId, Long by) { send(projectId, new Refresh("refresh", by)); }

    public void glossaryChanged(Long projectId, Long by) { send(projectId, new GlossaryChanged("glossary", by)); }

    public void presence(Long projectId, List<PresenceEntry> reviewers) { send(projectId, new PresenceSnapshot("presence", reviewers)); }

    private void send(Long projectId, Object payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { bus.publishProjectEvent(projectId, EVENT, payload); }
            });
        } else {
            bus.publishProjectEvent(projectId, EVENT, payload);
        }
    }
}
