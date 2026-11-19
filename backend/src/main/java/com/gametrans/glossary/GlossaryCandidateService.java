package com.gametrans.glossary;

import com.gametrans.common.NotFoundException;
import com.gametrans.glossary.GlossaryEntry.Kind;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectRepository;
import com.gametrans.review.ReviewEvents;
import com.gametrans.reviewer.Reviewer;
import com.gametrans.reviewer.ReviewerRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 단어장 후보(승인 대기): 기록·합치기·목록·승인·버림. 승인은 단어장 항목만 만들고 행은 고치지 않는다. */
@Service
public class GlossaryCandidateService {
    private final GlossaryCandidateRepository repo;
    private final GlossaryEntryRepository entries;
    private final GlossaryService glossary;
    private final ReviewerRepository reviewers;
    private final ProjectRepository projects;
    private final ReviewEvents events;

    public GlossaryCandidateService(GlossaryCandidateRepository repo, GlossaryEntryRepository entries, GlossaryService glossary,
                                    ReviewerRepository reviewers, ProjectRepository projects, ReviewEvents events) {
        this.repo = repo;
        this.entries = entries;
        this.glossary = glossary;
        this.reviewers = reviewers;
        this.projects = projects;
        this.events = events;
    }

    /** 바꾸기 결정 하나를 후보로 남긴다. 호출한 쪽 트랜잭션에 합류한다. */
    @Transactional
    public void record(Long gameId, String src, String ko, String oldKo, int rows, String reason, String source, Long proposalId, Long reviewerId) {
        if (rows <= 0 || gameId == null || src == null || ko == null) return;
        src = src.trim();
        ko = ko.trim();
        if (src.isEmpty() || ko.isEmpty()) return;
        Optional<GlossaryEntry> existing = entries.findByGameIdAndKindAndFind(gameId, Kind.SRC, src);
        if (existing.isPresent() && ko.equals(existing.get().getKo())) return;
        String r = reason == null || reason.isBlank() ? null : reason;
        List<GlossaryCandidate> same = repo.findByGameIdAndSrcAndKoAndStatus(gameId, src, ko, "PENDING");
        GlossaryCandidate c;
        if (same.isEmpty()) {
            c = new GlossaryCandidate();
            c.setGameId(gameId); c.setSrc(src); c.setKo(ko); c.setOldKo(oldKo); c.setRows(rows);
            c.setReason(r); c.setSource(source); c.setProposalId(proposalId);
        } else {
            c = same.get(0);
            c.setRows(c.getRows() + rows);
            if (r != null) c.setReason(r);
            if (oldKo != null) c.setOldKo(oldKo);
            c.setSource(source);
            c.setProposalId(proposalId);
        }
        c.setCreatedBy(reviewerId);
        c.setCreatedAt(Instant.now());
        c = repo.save(c);
        notify(c);
    }

    @Transactional(readOnly = true)
    public List<GlossaryDtos.CandidateDto> pending(Long gameId) {
        List<GlossaryCandidate> list = repo.findByGameIdAndStatusOrderByCreatedAtDescIdDesc(gameId, "PENDING");
        return list.stream().map(this::dto).toList();
    }

    @Transactional
    public GlossaryDtos.ApproveCandidateResult approve(Long id, Long reviewerId) {
        GlossaryCandidate c = lockPending(id);
        glossary.adoptDecision(c.getGameId(), c.getSrc(), c.getKo(), reviewerId);
        decide(c, "APPROVED", reviewerId);
        List<Long> closed = new ArrayList<>();
        for (GlossaryCandidate o : repo.findByGameIdAndSrcAndStatus(c.getGameId(), c.getSrc(), "PENDING")) {
            if (o.getId().equals(c.getId())) continue;
            decide(o, "DISMISSED", reviewerId);
            closed.add(o.getId());
        }
        return new GlossaryDtos.ApproveCandidateResult(currentEntry(c), dto(c), closed);
    }

    @Transactional
    public GlossaryDtos.CandidateDto dismiss(Long id, Long reviewerId) {
        GlossaryCandidate c = lockPending(id);
        decide(c, "DISMISSED", reviewerId);
        return dto(c);
    }

    private GlossaryCandidate lockPending(Long id) {
        GlossaryCandidate c = repo.findForUpdate(id).orElseThrow(() -> new NotFoundException("후보를 찾을 수 없습니다: " + id));
        if (!"PENDING".equals(c.getStatus())) throw new GlossaryCandidateDecidedException("이미 처리된 후보입니다.", dto(c));
        return c;
    }

    private void decide(GlossaryCandidate c, String status, Long reviewerId) {
        c.setStatus(status);
        c.setDecidedBy(reviewerId);
        c.setDecidedAt(Instant.now());
        repo.save(c);
        notify(c);
    }

    private void notify(GlossaryCandidate c) {
        List<Long> projectIds = projects.findByGameIdOrderByUploadedAtDesc(c.getGameId()).stream().map(Project::getId).toList();
        events.glossaryCandidate(c.getGameId(), projectIds,
                new ReviewEvents.CandidateEvent(ReviewEvents.GLOSSARY_CANDIDATE_EVENT, c.getGameId(), c.getId(), c.getStatus()));
    }

    private GlossaryDtos.EntryDto currentEntry(GlossaryCandidate c) {
        GlossaryEntry e = entries.findByGameIdAndKindAndFind(c.getGameId(), Kind.SRC, c.getSrc()).orElseThrow();
        return new GlossaryDtos.EntryDto(e.getId(), e.getGameId(), e.getKind().name(), e.getMatchMode().name(),
                e.getFind(), e.getKo(), e.getNote(), e.getOldKo(), false, e.getUpdatedAt());
    }

    private GlossaryDtos.CandidateDto dto(GlossaryCandidate c) {
        String conflict = entries.findByGameIdAndKindAndFind(c.getGameId(), Kind.SRC, c.getSrc())
                .map(GlossaryEntry::getKo).filter(k -> k != null && !k.equals(c.getKo())).orElse(null);
        String name = c.getCreatedBy() == null ? null : reviewers.findById(c.getCreatedBy()).map(Reviewer::getName).orElse(null);
        return new GlossaryDtos.CandidateDto(c.getId(), c.getGameId(), c.getSrc(), c.getKo(), c.getOldKo(), c.getRows(), c.getReason(),
                c.getSource(), c.getStatus(), c.getCreatedBy(), name, c.getCreatedAt(), conflict);
    }
}
