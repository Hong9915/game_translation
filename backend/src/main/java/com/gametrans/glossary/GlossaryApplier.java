package com.gametrans.glossary;

import com.gametrans.glossary.GlossaryEntry.Kind;
import com.gametrans.glossary.GlossaryEntry.MatchMode;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.review.BulkFixWriter;
import com.gametrans.text.Josa;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단어장 항목 하나를 그 게임의 모든 파일에 적용한다. 미리보기 건수(count)도 같은 계산을 쓴다.
 * SRC+PARTIAL은 old_ko가 있을 때만 "원문에 find, 한국어에 old_ko" 행에서 old_ko → ko 치환한다(설계 2026-09-29).
 * 지금 값은 리비전 이력을 다시 긁지 않고(과거 620MiB 사고와 같은 모양이라) 트리거가 유지하는
 * latest_ko(읽기 전용)로만 판단한다.
 */
@Component
public class GlossaryApplier {
    private final SegmentRepository segments;
    private final BulkFixWriter writer;

    public GlossaryApplier(SegmentRepository segments, BulkFixWriter writer) {
        this.segments = segments;
        this.writer = writer;
    }

    @Transactional
    public int apply(Long gameId, GlossaryEntry e, Long reviewerId) { return apply(gameId, e, reviewerId, pid -> null); }

    /** activityFor: 프로젝트 id → 바뀐 행의 리비전을 매달 변경 내역(GLOSSARY_APPLY, 프로젝트마다 하나). */
    @Transactional
    public int apply(Long gameId, GlossaryEntry e, Long reviewerId, Function<Long, Long> activityFor) {
        if (e.getKo() == null || e.getKo().isBlank()) return 0;           // 메모 전용 항목
        String oldKo = trimmedOrNull(e.getOldKo());
        if (e.getKind() == Kind.SRC && e.getMatchMode() == MatchMode.PARTIAL && oldKo == null) return 0;  // 기존 표기 없으면 자동 치환 불가
        List<Segment> rows = candidates(gameId, e.getKind(), e.getMatchMode(), e.getFind(), oldKo);
        return writer.write(rows, s -> next(s, s.getLatestKo(), e, oldKo), reviewerId, activityFor);
    }

    /** 실제로 바뀔 행 수. 아무것도 쓰지 않는다. apply와 같은 후보 쿼리·같은 판정을 써서 서로 어긋나지 않는다. */
    public int count(Long gameId, Kind kind, MatchMode mode, String find, String ko, String oldKo) {
        String old = trimmedOrNull(oldKo);
        if (ko == null || ko.isBlank() || (kind == Kind.SRC && mode == MatchMode.PARTIAL && old == null)) return 0;
        GlossaryEntry e = new GlossaryEntry();
        e.setKind(kind); e.setMatchMode(mode); e.setFind(find); e.setKo(ko); e.setOldKo(old);
        int n = 0;
        for (Segment s : candidates(gameId, kind, mode, find, old)) {
            String cur = s.getLatestKo();
            String next = next(s, cur, e, old);
            if (next == null) continue;
            // BulkFixWriter는 trim한 값을 latestKo와 비교해 바뀐 행만 쓴다(위 Important 지적). count도
            // 같은 기준으로 세야 apply와 어긋나지 않는다 — 안 그러면 끝에 공백만 붙는 경우 count>apply가 된다.
            String trimmed = next.trim();
            if (!trimmed.isEmpty() && !trimmed.equals(cur)) n++;
        }
        return n;
    }

    private static String trimmedOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** SQL로 후보만 좁힌다(SegmentRepository.findEditableMatching) — 최종 판정은 next()가 자바에서 한다. */
    private List<Segment> candidates(Long gameId, Kind kind, MatchMode mode, String find, String oldKo) {
        if (find == null || find.isBlank()) return List.of();
        return segments.findEditableMatching(gameId, kind.name(), mode.name(), find, oldKo == null ? "" : oldKo);
    }

    /** 이 행의 새 Fix. 안 걸리면 null. */
    private static String next(Segment s, String cur, GlossaryEntry e, String oldKo) {
        String find = e.getFind();
        if (find == null || find.isBlank()) return null;
        if (e.getKind() == Kind.SRC && e.getMatchMode() == MatchMode.WHOLE) {
            // 원문이 통째로 같으면 Fix를 통째로 바꾼다
            return find.equals(s.getSrcText()) ? e.getKo() : null;
        }
        if (cur == null) return null;
        if (e.getKind() == Kind.SRC) {
            // SRC+PARTIAL: 원문에 find, 한국어에 old_ko가 있는 행에서 old_ko → ko (설계 §1.3)
            if (oldKo == null || s.getSrcText() == null || !s.getSrcText().contains(find) || !cur.contains(oldKo)) return null;
            return replacePartial(cur, oldKo, e.getKo());
        }
        if (e.getMatchMode() == MatchMode.WHOLE) return find.equals(cur.trim()) ? e.getKo() : null;
        if (!cur.contains(find)) return null;
        return replacePartial(cur, find, e.getKo());
    }

    /**
     * KO+PARTIAL 치환. ko 안에 find가 이미 들어 있으면(재적용 경로 — 예: find "심판" / ko "심판관")
     * cur.replace(find, ko)를 그냥 쓰면 다시 적용할 때마다 겹쳐 늘어난다("심판관관"). 그래서 cur를
     * ko로 먼저 쪼개고(리터럴 분리, 빈 조각도 유지) 남은 조각 안에서만 find를 ko로 바꾼 뒤 ko로 다시
     * 이어 붙인다 — 이미 ko가 된 자리는 다시 건드리지 않아 몇 번을 다시 적용해도 결과가 같다(멱등).
     * find가 ko에 없으면 그냥 replace로 충분하다.
     * 치환 자리 바로 뒤 조사는 Josa.replaceAdjusting이 새 표기 받침에 맞춘다(설계 2026-10-07 §4). 조각이 치환 자리로
     * 끝나면 그 뒤는 ko(또는 문장 끝)라 조사가 올 수 없으므로 조각마다 맞춰도 빠지는 경우가 없다.
     */
    static String replacePartial(String cur, String find, String ko) {
        if (!ko.contains(find)) return Josa.replaceAdjusting(cur, find, ko);
        String[] pieces = cur.split(Pattern.quote(ko), -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pieces.length; i++) {
            if (i > 0) sb.append(ko);
            sb.append(Josa.replaceAdjusting(pieces[i], find, ko));
        }
        return sb.toString();
    }
}
