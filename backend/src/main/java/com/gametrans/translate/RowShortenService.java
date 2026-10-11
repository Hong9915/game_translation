package com.gametrans.translate;

import com.gametrans.common.NotFoundException;
import com.gametrans.game.Game;
import com.gametrans.game.GameService;
import com.gametrans.glossary.GlossaryPrompt;
import com.gametrans.llm.LlmClient;
import com.gametrans.llm.LlmClient.ShortenOptionsRequest;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.review.ReviewService;
import com.gametrans.review.TermMissService;
import com.gametrans.text.EmptyMarkers;
import com.gametrans.text.LengthCheck;
import com.gametrans.text.PlaceholderCheck;
import com.gametrans.translate.TranslateDtos.RowShortenRequest;
import com.gametrans.translate.TranslateDtos.RowShortenResult;
import com.gametrans.translate.TranslateDtos.ShortenCandidate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 검수 그리드 우클릭 "짧게 줄이기". 지금 Fix(없으면 기존 한국어)를 GPT로 줄인 후보 몇 개를 돌려주기만 하고 저장하지 않는다 —
 * 사람이 후보를 고르면 일반 저장으로 들어가 이력·같은 원문 전파·용어 검사가 사람 수정과 똑같이 적용된다.
 * GPT가 글자 수를 잘 못 맞추므로 글자 수는 서버가 다시 세어 보여 주고, 목표를 넘은 후보도 버리지 않는다(화면이 표시한다).
 */
@Service
public class RowShortenService {
    private final SegmentRepository segments;
    private final GameService games;
    private final QuickTranslateService quick;
    private final RevisionService revisions;
    private final TermMissService termMiss;
    private final ReviewService review;
    private final LlmClient llm;

    /** 지금 GPT에 가 있는 세그먼트. 같은 행을 연달아 눌러 GPT를 두 번 부르지 않게 한다. */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public RowShortenService(SegmentRepository segments, GameService games, QuickTranslateService quick, RevisionService revisions,
                             TermMissService termMiss, ReviewService review, LlmClient llm) {
        this.segments = segments;
        this.games = games;
        this.quick = quick;
        this.revisions = revisions;
        this.termMiss = termMiss;
        this.review = review;
        this.llm = llm;
    }

    public RowShortenResult shorten(Long segmentId, RowShortenRequest req) {
        Segment s = segments.findById(segmentId).orElseThrow(() -> new NotFoundException("세그먼트를 찾을 수 없습니다: " + segmentId));
        if (s.isSkipped()) throw new IllegalArgumentException("제외된 행은 줄이지 않습니다.");
        String ko = revisions.current(segmentId).map(TranslationRevision::getText).orElse(null);
        if (EmptyMarkers.isEmpty(ko)) ko = s.getExistingKoText();
        if (EmptyMarkers.isEmpty(ko)) throw new IllegalArgumentException("줄일 한국어가 없습니다.");
        ko = ko.trim();
        int target = target(s.getSrcText(), req);

        if (!running.add(segmentId)) throw new IllegalStateException("이 행은 이미 줄이는 중입니다. 끝난 뒤에 다시 시도하세요.");
        try {
            Game game = games.get(termMiss.gameIdOf(s.getProjectId()));
            QuickTranslateService.Terms terms = quick.termsFor(game, s.getSrcText());
            String en = EmptyMarkers.isEmpty(s.getEnText()) ? null : s.getEnText().trim();
            List<String> options = llm.shortenOptions(new ShortenOptionsRequest(s.getSrcText(), en, ko, target, terms.withNotes(),
                    GlossaryPrompt.instructions(null, terms.notes())));
            List<ShortenCandidate> candidates = candidates(ko, options);
            if (candidates.isEmpty()) throw new IllegalStateException("줄인 후보를 만들지 못했습니다. 다시 시도하세요.");
            return new RowShortenResult(ko, LengthCheck.count(ko), target, candidates);
        } finally {
            running.remove(segmentId);
        }
    }

    /** maxChars가 있으면 그대로, 없으면 원문 글자 수 × 비율. 원문이 자리표시자뿐이어도 1자 아래로는 내리지 않는다. */
    private int target(String src, RowShortenRequest req) {
        if (req != null && req.maxChars() != null && req.maxChars() > 0) return req.maxChars();
        double ratio = req != null && req.ratio() != null && req.ratio() > 0 ? req.ratio() : review.defaultRatio();
        return Math.max(1, (int) Math.floor(LengthCheck.count(src) * ratio));
    }

    /**
     * 빈 값·중복·자리표시자를 바꾼 것·글자 수가 줄지 않은 것(띄어쓰기만 뺀 것 등)은 버린다.
     * 자리표시자는 원문이 아니라 줄이는 대상(ko)과 맞춘다.
     */
    static List<ShortenCandidate> candidates(String ko, List<String> options) {
        int len = LengthCheck.count(ko);
        Set<String> seen = new LinkedHashSet<>();
        for (String o : options) {
            if (o == null) continue;
            String t = o.trim();
            if (t.isEmpty() || LengthCheck.count(t) >= len || !PlaceholderCheck.matches(ko, t)) continue;
            seen.add(t);
        }
        List<ShortenCandidate> out = new ArrayList<>();
        for (String t : seen) out.add(new ShortenCandidate(t, LengthCheck.count(t)));
        return out;
    }
}
