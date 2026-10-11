package com.gametrans.review;

import com.gametrans.review.ReviewEvents.PresenceEntry;
import com.gametrans.reviewer.Reviewer;
import com.gametrans.reviewer.ReviewerService;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{id}/presence")
public class PresenceController {
    public record PresenceUpdate(String sheet, Long editingSegmentId) {}
    public record PresenceResponse(List<PresenceEntry> reviewers) {}

    private final PresenceService presence;
    private final ReviewerService reviewers;

    public PresenceController(PresenceService presence, ReviewerService reviewers) {
        this.presence = presence;
        this.reviewers = reviewers;
    }

    /** heartbeat. 익명(헤더 없음)은 목록에 넣지 않고 지금 누가 있는지만 돌려준다. */
    @PostMapping
    public PresenceResponse touch(@PathVariable Long id, @RequestBody(required = false) PresenceUpdate body,
                                  @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        Reviewer who = reviewers.resolve(reviewerId);
        if (who == null) return new PresenceResponse(presence.snapshot(id));
        return new PresenceResponse(presence.touch(id, who, body == null ? null : body.sheet(), body == null ? null : body.editingSegmentId(), Instant.now()));
    }

    @DeleteMapping
    public ResponseEntity<Void> leave(@PathVariable Long id, @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        if (reviewerId != null) presence.leave(id, reviewerId);
        return ResponseEntity.noContent().build();
    }
}
