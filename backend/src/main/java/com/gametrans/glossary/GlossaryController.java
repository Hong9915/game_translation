package com.gametrans.glossary;

import com.gametrans.glossary.GlossaryDtos.ApplyResult;
import com.gametrans.glossary.GlossaryDtos.CountResult;
import com.gametrans.glossary.GlossaryDtos.EntryDto;
import com.gametrans.glossary.GlossaryDtos.EntryRequest;
import com.gametrans.glossary.GlossaryDtos.Recommendation;
import com.gametrans.glossary.GlossaryDtos.SaveResult;
import com.gametrans.glossary.GlossaryService.Scope;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 단어장 CRUD·공유 승격·일괄 적용 API (설계 §3). */
@RestController
@RequestMapping("/api")
public class GlossaryController {
    private final GlossaryService service;

    public GlossaryController(GlossaryService service) { this.service = service; }

    @GetMapping("/games/{gameId}/glossary")
    public List<EntryDto> list(@PathVariable Long gameId, @RequestParam(required = false) Scope scope) {
        return service.list(gameId, scope == null ? Scope.ALL : scope);
    }

    @PostMapping("/games/{gameId}/glossary")
    public SaveResult upsert(@PathVariable Long gameId, @RequestBody EntryRequest req,
                              @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return guardDuplicate(() -> service.upsert(gameId, req, reviewerId));
    }

    @PutMapping("/glossary/{id}")
    public SaveResult update(@PathVariable Long id, @RequestBody EntryRequest req,
                              @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return guardDuplicate(() -> service.update(id, req, reviewerId));
    }

    @DeleteMapping("/glossary/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                        @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        service.delete(id, reviewerId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/glossary/{id}/share")
    public EntryDto share(@PathVariable Long id,
                           @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return guardDuplicate(() -> service.share(id, reviewerId));
    }

    @GetMapping("/games/{gameId}/glossary/apply/count")
    public CountResult count(@PathVariable Long gameId, @RequestParam String kind, @RequestParam String matchMode,
                              @RequestParam String find, @RequestParam(required = false) String ko,
                              @RequestParam(required = false) String oldKo) {
        return new CountResult(service.count(gameId, kind, matchMode, find, ko, oldKo));
    }

    /** SRC+PARTIAL 항목의 기존 표기 후보(설계 §2.1). ko는 예시 치환 결과(after)에만 쓰인다. */
    @GetMapping("/games/{gameId}/glossary/suggest")
    public GlossarySuggester.Result suggest(@PathVariable Long gameId, @RequestParam String find,
                                            @RequestParam(required = false) String ko) {
        return service.suggest(gameId, find, ko);
    }

    /** 단어장에 아직 없는, 여러 문장에 되풀이되는 원문 구절 전부와 그 지금 번역. 추천 창이 한 번에 받아 표로 보여 준다. */
    @GetMapping("/games/{gameId}/glossary/recommend")
    public List<Recommendation> recommend(@PathVariable Long gameId) {
        return service.recommend(gameId);
    }

    /** 같은 원문 구절이 다른 한국어로 옮겨진 곳(설계 2026-10-07). 추천 창의 표기 갈림 탭이 한 번 받는다. */
    @GetMapping("/games/{gameId}/glossary/variants")
    public List<GlossaryDtos.Variant> variants(@PathVariable Long gameId) {
        return service.variants(gameId);
    }

    /** 띄어쓰기 한꺼번에 통일: 띄어쓰기만 다른 꼴을 여러 구절에서 한 번에 고친다. 단어장엔 남기지 않는다. */
    @PostMapping("/games/{gameId}/glossary/unify-spacing")
    public ApplyResult unifySpacing(@PathVariable Long gameId, @RequestBody GlossaryDtos.SpacingRequest req,
                                    @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.unifySpacing(gameId, req, reviewerId);
    }

    /** 표기 통일: 고른 표기들을 행에서 한 번 고치고 단어장 항목 하나를 남긴다(설계 2026-10-07 §2). */
    @PostMapping("/games/{gameId}/glossary/unify")
    public SaveResult unify(@PathVariable Long gameId, @RequestBody GlossaryDtos.UnifyRequest req,
                            @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return guardDuplicate(() -> service.unify(gameId, req, reviewerId));
    }

    @PostMapping("/games/{gameId}/glossary/apply")
    public ApplyResult applyAll(@PathVariable Long gameId,
                                 @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return new ApplyResult(service.applyAll(gameId, reviewerId));
    }

    /**
     * (Ruling 28) 더블클릭이나 두 사람이 동시에 같은 (kind, find)를 저장하면 부분 유니크 인덱스가 두 번째
     * 쓰기를 막는다. upsert·update는 저장 직전에 겹침을 미리 확인하지만, 그 확인과 실제 저장 사이의 레이스는
     * 막을 수 없다 — update의 경우 실제 UPDATE가 서비스 메서드 본문이 아니라 트랜잭션 커밋 시점의 플러시에서
     * 나갈 수도 있어서, 위반이 서비스 메서드가 이미 반환된 뒤(트랜잭션 프록시의 커밋 단계)에 드러날 수 있다.
     * 그 순간은 서비스 메서드 본문 안의 try/catch로는 잡을 수 없으므로(이미 호출 프레임을 벗어났다),
     * 트랜잭션 경계 바깥인 여기서 컨트롤러 호출 전체를 감싸 잡는다(ReviewerService와 같은 관례 — 예외를
     * 바꿔서 500 대신 400으로 답한다).
     */
    private <T> T guardDuplicate(Supplier<T> op) {
        try {
            return op.get();
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("방금 같은 항목이 저장됐습니다. 목록을 새로 고쳐 주세요.", e);
        }
    }
}
