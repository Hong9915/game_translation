package com.gametrans.glossary;

import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityService;
import com.gametrans.common.NotFoundException;
import com.gametrans.game.GameRepository;
import com.gametrans.glossary.GlossaryEntry.Kind;
import com.gametrans.glossary.GlossaryEntry.MatchMode;
import com.gametrans.josa.JosaClient;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.Project;
import com.gametrans.project.ProjectRepository;
import com.gametrans.review.ReviewEvents;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단어장 CRUD, 공유 승격, 유효 단어장 계산과 게임 전체 재적용.
 * 실제 Fix 치환은 {@link GlossaryApplier}가 한다 — 여기는 항목 자체의 관리만 맡는다.
 */
@Service
public class GlossaryService {
    private static final Logger log = LoggerFactory.getLogger(GlossaryService.class);

    public enum Scope { GAME, SHARED, ALL }

    private final GlossaryEntryRepository repo;
    private final GlossaryApplier applier;
    private final EffectiveGlossary effective;
    private final ProjectRepository projects;
    private final ReviewEvents events;
    private final GameRepository games;
    private final SegmentRepository segments;
    private final ActivityService activities;
    private final CandidateMiner miner;
    private final JosaClient josa;

    @PersistenceContext
    private EntityManager em;

    public GlossaryService(GlossaryEntryRepository repo, GlossaryApplier applier, EffectiveGlossary effective,
                           ProjectRepository projects, ReviewEvents events, GameRepository games, SegmentRepository segments,
                           ActivityService activities, CandidateMiner miner, JosaClient josa) {
        this.josa = josa;
        this.activities = activities;
        this.miner = miner;
        this.repo = repo;
        this.applier = applier;
        this.effective = effective;
        this.projects = projects;
        this.events = events;
        this.games = games;
        this.segments = segments;
    }

    public List<GlossaryDtos.EntryDto> list(Long gameId, Scope scope) {
        List<GlossaryEntry> entries = switch (scope) {
            case GAME -> repo.findByGameId(gameId);
            case SHARED -> repo.findByGameIdIsNull();
            case ALL -> effective(gameId);
        };
        return entries.stream().map(GlossaryService::dto).toList();
    }

    /** 이 게임에서 실제로 쓰이는 단어장. (kind, find)가 겹치면 게임 전용이 공유를 이긴다. 계산은 EffectiveGlossary가 한다. */
    public List<GlossaryEntry> effective(Long gameId) { return effective.of(gameId); }

    /** 게임 전용으로 upsert하고 바로 적용한다. */
    @Transactional
    public GlossaryDtos.SaveResult upsert(Long gameId, GlossaryDtos.EntryRequest req, Long reviewerId) {
        String find = trimmedFind(req.find());
        Kind kind = parseKind(req.kind());
        MatchMode mode = parseMode(req.matchMode());
        GlossaryEntry e = repo.findByGameIdAndKindAndFind(gameId, kind, find).orElseGet(GlossaryEntry::new);
        e.setGameId(gameId); e.setKind(kind); e.setMatchMode(mode); e.setFind(find);
        e.setKo(blankToNull(req.ko())); e.setNote(blankToNull(req.note()));
        e.setOldKo(oldKoFor(kind, mode, req.oldKo(), e.getKo()));
        if (e.getCreatedBy() == null) e.setCreatedBy(reviewerId);
        e.setUpdatedAt(Instant.now());
        boolean existed = e.getId() != null;   // (kind, find)가 같은 항목을 덮어쓰면 변경 내역엔 수정으로 남긴다
        GlossaryEntry saved = repo.save(e);
        logEdit(gameId, existed ? "UPDATE" : "ADD", saved, reviewerId);
        notifyChanged(gameId, reviewerId);
        return new GlossaryDtos.SaveResult(dto(saved), applier.apply(gameId, saved, reviewerId, applyFor(reviewerId)));
    }

    /**
     * 항목을 고친다. 게임 전용이면 필드를 바꾸고 그 자리에서 그 게임에 적용한다(applied에 반영).
     * 공유 항목이면 저장만 한다 — 적용할 게임 하나로 정해지지 않으므로 applied는 항상 0이고,
     * 각 게임은 "단어장 전체 다시 적용"을 누를 때 새 값을 받아 간다.
     * (kind, find)를 바꿔서 같은 범위(게임 전용이면 그 게임, 공유면 공유 단어집) 안의 다른 항목과
     * 겹치면 DB 유니크 인덱스 예외를 기다리지 않고 미리 막는다(Ruling 26).
     */
    @Transactional
    public GlossaryDtos.SaveResult update(Long id, GlossaryDtos.EntryRequest req, Long reviewerId) {
        GlossaryEntry e = repo.findById(id).orElseThrow(() -> new NotFoundException("없는 단어장 항목입니다: " + id));
        String find = trimmedFind(req.find());
        Kind kind = parseKind(req.kind());
        MatchMode mode = parseMode(req.matchMode());

        if (kind != e.getKind() || !find.equals(e.getFind())) {
            Optional<GlossaryEntry> clash = e.getGameId() == null
                    ? repo.findByGameIdIsNullAndKindAndFind(kind, find)
                    : repo.findByGameIdAndKindAndFind(e.getGameId(), kind, find);
            if (clash.isPresent() && !clash.get().getId().equals(e.getId()))
                throw new IllegalArgumentException("이미 같은 조건(" + kind + ", " + find + ")의 단어장 항목이 있습니다.");
        }

        e.setKind(kind); e.setMatchMode(mode); e.setFind(find);
        e.setKo(blankToNull(req.ko())); e.setNote(blankToNull(req.note()));
        e.setOldKo(oldKoFor(kind, mode, req.oldKo(), e.getKo()));
        e.setUpdatedAt(Instant.now());
        GlossaryEntry saved = repo.save(e);
        logEdit(e.getGameId(), "UPDATE", saved, reviewerId);
        notifyChanged(e.getGameId(), reviewerId);

        int applied = e.getGameId() == null ? 0 : applier.apply(e.getGameId(), saved, reviewerId, applyFor(reviewerId));
        return new GlossaryDtos.SaveResult(dto(saved), applied);
    }

    @Transactional
    public void delete(Long id, Long reviewerId) {
        GlossaryEntry e = repo.findById(id).orElseThrow(() -> new NotFoundException("없는 단어장 항목입니다: " + id));
        repo.delete(e);
        logEdit(e.getGameId(), "DELETE", e, reviewerId);
        notifyChanged(e.getGameId(), reviewerId);
    }

    /** 공유 단어집으로 올린다. 게임 전용 항목은 그대로 둔다. */
    @Transactional
    public GlossaryDtos.EntryDto share(Long id, Long reviewerId) {
        GlossaryEntry src = repo.findById(id).orElseThrow(() -> new NotFoundException("없는 단어장 항목입니다: " + id));
        GlossaryEntry shared = repo.findByGameIdIsNullAndKindAndFind(src.getKind(), src.getFind()).orElseGet(GlossaryEntry::new);
        shared.setGameId(null); shared.setKind(src.getKind()); shared.setMatchMode(src.getMatchMode());
        shared.setFind(src.getFind()); shared.setKo(src.getKo()); shared.setNote(src.getNote()); shared.setOldKo(src.getOldKo());
        shared.setCreatedBy(src.getCreatedBy()); shared.setUpdatedAt(Instant.now());
        GlossaryEntry saved = repo.save(shared);
        // 원본이 게임 전용일 때만 그 게임에 남긴다(공유 항목을 다시 share하면 gameId가 null이라 logEdit이 건너뛴다).
        logEdit(src.getGameId(), "SHARE", src, reviewerId);
        notifyChanged(null, reviewerId);
        return dto(saved);
    }

    /**
     * 저장 전 대상 건수 미리보기. 아무것도 쓰지 않는다. find는 upsert와 똑같이 다듬는다 — 안 그러면
     * 앞뒤 공백이 있는 미리보기 값이 실제 저장 때 적용될 값(다듬은 값)과 다른 건수를 보여 주고,
     * 빈 문자열은 0건이 아니라 400이어야 한다(upsert가 거절하는 것과 같은 이유).
     */
    public int count(Long gameId, String kind, String matchMode, String find, String ko, String oldKo) {
        return applier.count(gameId, parseKind(kind), parseMode(matchMode), trimmedFind(find), ko, oldKo);
    }

    /**
     * SRC+PARTIAL 항목의 기존 표기 후보(설계 §2). 게임의 (원문, 한국어) 목록을 한 번 읽어 GlossarySuggester에 넘긴다.
     * find는 2글자 이상이어야 한다 — 한 글자는 너무 많은 행에 걸려 후보가 뜻이 없다.
     */
    @Transactional(readOnly = true)
    public GlossarySuggester.Result suggest(Long gameId, String find, String ko) {
        String f = find == null ? "" : find.trim();
        if (f.length() < 2) throw new IllegalArgumentException("찾을 문자열은 두 글자 이상이어야 합니다.");
        if (!games.existsById(gameId)) throw new NotFoundException("게임을 찾을 수 없습니다: " + gameId);
        return GlossarySuggester.suggest(rowsOf(gameId), f, ko == null || ko.isBlank() ? null : ko.trim());
    }

    /**
     * 단어장에 넣을 만한 원문 구절 추천(상한 없이 전부). 기존 표기 후보(suggest)와 같은 행(번역이 있는 행)을 읽어
     * {@link GlossaryRecommender}에 넘기고, 이 게임의 유효 단어장에 이미 원문으로 등록된 구절은 뺀다.
     * 저장하지 않고 부를 때마다 다시 센다.
     */
    @Transactional(readOnly = true)
    public List<GlossaryDtos.Recommendation> recommend(Long gameId) {
        if (!games.existsById(gameId)) throw new NotFoundException("게임을 찾을 수 없습니다: " + gameId);
        return GlossaryRecommender.recommend(miner, rowsOf(gameId), srcFinds(gameId));
    }

    /**
     * 표기 갈림(설계 2026-10-07 §1). 추천과 같은 행을 읽어 {@link GlossaryVariants}에 넘기고, 유효 단어장에 원문으로 있는 구절은 뺀다.
     * 저장하지 않고 부를 때마다 다시 센다.
     */
    @Transactional(readOnly = true)
    public List<GlossaryDtos.Variant> variants(Long gameId) {
        if (!games.existsById(gameId)) throw new NotFoundException("게임을 찾을 수 없습니다: " + gameId);
        return withStandardSpacing(GlossaryVariants.find(miner, rowsOf(gameId), srcFinds(gameId)));
    }

    /**
     * SPACING 카드에 표준 꼴을 붙인다: 공백을 지운 주 표기를 조사 검사 컨테이너(Kiwi)의 띄어쓰기 교정에 넣어, 결과가 그 카드에 실제로 쓰인
     * 꼴(주 표기·띄어쓰기 꼴) 가운데 하나면 그것을 표준으로 둔다("일일" → "일 일"처럼 아무도 안 쓰는 꼴이면 null). 참고용이라 Kiwi가
     * 꺼져 있거나 실패하면 표준 없이 목록을 그대로 돌려준다.
     */
    private List<GlossaryDtos.Variant> withStandardSpacing(List<GlossaryDtos.Variant> variants) {
        List<GlossaryDtos.Variant> spacing = variants.stream().filter(v -> "SPACING".equals(v.kind())).toList();
        if (spacing.isEmpty() || !josa.enabled()) return variants;
        List<String> spaced;
        try {
            spaced = josa.space(spacing.stream().map(v -> v.main().ko().replaceAll("\\s+", "")).toList());
        } catch (RuntimeException e) {
            log.warn("띄어쓰기 표준 꼴을 받지 못했다: {}", e.getMessage());
            return variants;
        }
        Map<String, String> standard = new HashMap<>();
        for (int i = 0; i < spacing.size(); i++) {
            GlossaryDtos.Variant v = spacing.get(i);
            String s = spaced.get(i) == null ? "" : spaced.get(i).strip();
            boolean inUse = v.main().ko().equals(s) || v.alts().stream().anyMatch(a -> a.ko().equals(s));
            if (inUse) standard.put(v.find(), s);
        }
        return variants.stream().map(v -> standard.containsKey(v.find()) ? v.withStandard(standard.get(v.find())) : v).toList();
    }

    /** 이 게임에서 번역이 있는 (원문, 한국어) 행. 기존 표기 후보·추천·표기 갈림·단어장 요청이 같은 행을 본다. */
    List<GlossarySuggester.Row> rowsOf(Long gameId) {
        return segments.findSrcKoInGame(gameId).stream().map(r -> new GlossarySuggester.Row(r.getSrc(), r.getKo())).toList();
    }

    private Set<String> srcFinds(Long gameId) {
        return effective(gameId).stream().filter(e -> e.getKind() == Kind.SRC).map(GlossaryEntry::getFind).collect(Collectors.toSet());
    }

    /**
     * 표기 통일(설계 2026-10-07 §2). replace의 표기마다 "원문에 find, 한국어에 그 표기"인 행에서 그 표기 → ko로 한 번 고치고
     * (저장하지 않은 임시 SRC+PARTIAL 항목으로 GlossaryApplier를 부른다 — 확정 행은 후보 쿼리가 거른다), 단어장 항목 하나를 남긴다.
     * keep이 비면 번역 항목(find → ko), 아니면 메모 항목 — 번역 항목을 남기면 용어 불일치가 남긴 표기의 행을 전부 잡는다.
     * 항목에 old_ko를 남기지 않는 이유는 설계 "결정된 것" 참고. applyAll과 같은 이유로 표기마다 flush/clear한다(Ruling 11).
     */
    @Transactional
    public GlossaryDtos.SaveResult unify(Long gameId, GlossaryDtos.UnifyRequest req, Long reviewerId) {
        String find = trimmedFind(req.find());
        String ko = req.ko() == null ? "" : req.ko().trim();
        if (ko.isEmpty()) throw new IllegalArgumentException("맞출 표기를 입력하세요.");
        List<String> replace = cleaned(req.replace());
        if (replace.isEmpty()) throw new IllegalArgumentException("바꿀 표기를 하나 이상 고르세요.");
        if (replace.contains(ko)) throw new IllegalArgumentException("맞출 표기와 바꿀 표기가 같습니다: " + ko);
        if (!games.existsById(gameId)) throw new NotFoundException("게임을 찾을 수 없습니다: " + gameId);
        if (repo.findByGameIdAndKindAndFind(gameId, Kind.SRC, find).isPresent())
            throw new IllegalArgumentException("이미 단어장에 있는 원문입니다: " + find);
        List<String> keep = cleaned(req.keep()).stream().filter(k -> !k.equals(ko) && !replace.contains(k)).toList();

        int applied = rewrite(gameId, find, ko, replace, reviewerId, applyFor(reviewerId));

        GlossaryEntry e = new GlossaryEntry();
        e.setGameId(gameId); e.setKind(Kind.SRC); e.setMatchMode(MatchMode.PARTIAL); e.setFind(find);
        if (keep.isEmpty()) e.setKo(ko);
        else e.setNote(ko + withRo(ko) + " 통일, " + String.join("/", keep) + "도 씀");
        e.setCreatedBy(reviewerId);
        e.setUpdatedAt(Instant.now());
        GlossaryEntry saved = repo.save(e);
        logEdit(gameId, "ADD", saved, reviewerId);
        notifyChanged(gameId, reviewerId);
        return new GlossaryDtos.SaveResult(dto(saved), applied);
    }

    /**
     * 단어장 요청 승인 한 건(설계 2026-10-30). unify처럼 replace의 표기를 한 번 ko로 고치고 단어장 항목을 남기되,
     * 같은 원문 항목이 이미 있으면 거절하지 않고 덮어쓴다. 원문이 1글자면 WHOLE — 칸 전체가 find인 행만 ko로 바꾸고
     * replace는 무시한다(1글자는 부분 치환하면 엉뚱한 낱말까지 건드린다). PARTIAL은 old_ko가 없어 apply가 0이라 부르지 않는다.
     * rewrite가 em.clear()를 하므로 항목 조회·저장은 그 뒤에 한다.
     */
    @Transactional
    public GlossaryDtos.SaveResult adopt(Long gameId, String find, String ko, List<String> replace, List<String> keep, Long reviewerId) {
        String f = trimmedFind(find);
        String k = ko == null ? "" : ko.trim();
        if (k.isEmpty()) throw new IllegalArgumentException("최종 표기를 입력하세요.");
        if (!games.existsById(gameId)) throw new NotFoundException("게임을 찾을 수 없습니다: " + gameId);
        boolean whole = f.codePointCount(0, f.length()) == 1;
        List<String> rep = whole ? List.of() : cleaned(replace).stream().filter(r -> !r.equals(k)).toList();
        List<String> kept = cleaned(keep).stream().filter(x -> !x.equals(k) && !rep.contains(x)).toList();

        int applied = rep.isEmpty() ? 0 : rewrite(gameId, f, k, rep, reviewerId, applyFor(reviewerId));

        GlossaryEntry e = repo.findByGameIdAndKindAndFind(gameId, Kind.SRC, f).orElseGet(GlossaryEntry::new);
        boolean existed = e.getId() != null;
        e.setGameId(gameId); e.setKind(Kind.SRC); e.setMatchMode(whole ? MatchMode.WHOLE : MatchMode.PARTIAL); e.setFind(f);
        e.setKo(kept.isEmpty() ? k : null);
        e.setNote(kept.isEmpty() ? null : k + withRo(k) + " 통일, " + String.join("/", kept) + "도 씀");
        e.setOldKo(null);
        if (e.getCreatedBy() == null) e.setCreatedBy(reviewerId);
        e.setUpdatedAt(Instant.now());
        GlossaryEntry saved = repo.save(e);
        logEdit(gameId, existed ? "UPDATE" : "ADD", saved, reviewerId);
        notifyChanged(gameId, reviewerId);
        if (whole) applied += applier.apply(gameId, saved, reviewerId, applyFor(reviewerId));
        return new GlossaryDtos.SaveResult(dto(saved), applied);
    }

    /**
     * 띄어쓰기 한꺼번에 통일(행만 고친다 — 단어장엔 남기지 않는다). 항목마다 통일과 같은 방식으로 원문에 find가 든 행에서 replace → ko.
     * 띄어쓰기만 다른 꼴만 받는다 — 다른 낱말로 바꾸는 일은 카드마다 통일 창에서 사람이 본다. 바뀐 행은 프로젝트마다 적용 한 건으로 묶여
     * 한 번에 되돌릴 수 있다. 전부 한 트랜잭션이라 하나라도 잘못이면 아무것도 안 바뀐다.
     */
    @Transactional
    public GlossaryDtos.ApplyResult unifySpacing(Long gameId, GlossaryDtos.SpacingRequest req, Long reviewerId) {
        List<GlossaryDtos.SpacingItem> items = req.items() == null ? List.of() : req.items();
        if (items.isEmpty()) throw new IllegalArgumentException("고칠 항목이 없습니다.");
        record Clean(String find, String ko, List<String> replace) {}
        List<Clean> clean = new java.util.ArrayList<>();
        for (GlossaryDtos.SpacingItem it : items) {
            String find = trimmedFind(it.find());
            String ko = it.ko() == null ? "" : it.ko().trim();
            if (ko.isEmpty()) throw new IllegalArgumentException("맞출 표기를 입력하세요.");
            List<String> replace = cleaned(it.replace()).stream().filter(r -> !r.equals(ko)).toList();
            if (replace.isEmpty()) throw new IllegalArgumentException("바꿀 표기를 하나 이상 고르세요.");
            for (String r : replace)
                if (!r.replaceAll("\\s+", "").equals(ko.replaceAll("\\s+", "")))
                    throw new IllegalArgumentException("띄어쓰기만 다른 표기만 한꺼번에 고칠 수 있습니다: " + r + " → " + ko);
            clean.add(new Clean(find, ko, replace));
        }
        if (!games.existsById(gameId)) throw new NotFoundException("게임을 찾을 수 없습니다: " + gameId);
        Function<Long, Long> activityFor = applyFor(reviewerId);
        int applied = 0;
        for (Clean c : clean) applied += rewrite(gameId, c.find(), c.ko(), c.replace(), reviewerId, activityFor);
        return new GlossaryDtos.ApplyResult(applied);
    }

    /**
     * 원문에 find가 든 행에서 replace의 표기를 ko로 한 번 고친다(저장하지 않은 임시 SRC+PARTIAL 항목으로 GlossaryApplier를 부른다 —
     * 확정 행은 후보 쿼리가 거른다). 긴 표기부터 — 짧은 표기가 긴 표기의 앞부분이면("하루"/"하루에") 먼저 바꿔 버려 긴 표기를 못 찾게
     * 된다. 길이가 같으면 받은 순서대로. applyAll과 같은 이유로 표기마다 flush/clear한다(Ruling 11).
     */
    private int rewrite(Long gameId, String find, String ko, List<String> replace, Long reviewerId, Function<Long, Long> activityFor) {
        int applied = 0;
        for (String old : replace.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            GlossaryEntry once = new GlossaryEntry();
            once.setGameId(gameId); once.setKind(Kind.SRC); once.setMatchMode(MatchMode.PARTIAL);
            once.setFind(find); once.setKo(ko); once.setOldKo(old);
            applied += applier.apply(gameId, once, reviewerId, activityFor);
            em.flush();
            em.clear();
        }
        return applied;
    }

    /** 받침 있는 말 뒤엔 "으로", 받침이 없거나 ㄹ 받침이면 "로". 한글로 끝나지 않으면 "로". */
    static String withRo(String word) {
        String w = word.strip();
        if (w.isEmpty()) return "로";
        char c = w.charAt(w.length() - 1);
        if (c < '가' || c > '힣') return "로";
        int jong = (c - '가') % 28;
        return jong == 0 || jong == 8 ? "로" : "으로";
    }

    private static List<String> cleaned(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().toList();
    }

    /**
     * 유효 단어장을 전부 다시 적용한다. 새 파일을 올린 뒤 한 번 누른다.
     * Ruling 11: 항목 A를 적용하면 트리거가 DB의 latest_ko를 바로 갱신하지만, A의 후보 쿼리가 이미
     * 영속성 컨텍스트에 올려 둔 Segment는 (updatable=false라) 그 값을 다시 읽어 오지 않는다. 그 상태로
     * 다음 항목 B의 후보 쿼리를 돌리면, SQL 조건은 최신 DB 값으로 걸러도 결과로 돌아오는 건 A가 올려 둔
     * 그 stale 인스턴스라 B의 판정(next())이 옛 값을 본다. 항목마다 flush로 쓰기를 확정하고 clear로
     * 영속성 컨텍스트를 비워야 다음 항목이 방금 바뀐 값을 제대로 본다.
     * 주의: 그 em.clear()는 이 트랜잭션의 managed 엔티티를 전부 떼어 낸다. 그래서 이 메서드는 자기 트랜잭션에서만
     * 불러야 한다(지금 호출하는 곳은 컨트롤러 하나). 다른 트랜잭션 안에서 부르면 호출한 쪽이 아직 flush하지 않은
     * 엔티티 변경이 조용히 사라지고, 호출한 쪽이 들고 있던 엔티티도 detached가 되어 이후 변경이 저장되지 않는다.
     */
    @Transactional
    public int applyAll(Long gameId, Long reviewerId) {
        // 프로젝트마다 GLOSSARY_APPLY 하나. 항목마다 게임 전체 행을 돌기 때문에 모든 항목이 같은 지연 생성 함수를 공유한다.
        Function<Long, Long> activityFor = applyFor(reviewerId);
        int n = 0;
        for (GlossaryEntry e : effective(gameId)) {
            n += applier.apply(gameId, e, reviewerId, activityFor);
            em.flush();
            em.clear();
        }
        return n;
    }

    /** 단어장 편집 한 건을 게임 단위 변경 내역으로 남긴다. 공유 단어집(gameId null)은 어느 게임 피드에도 안 넣는다(설계 §1). */
    private void logEdit(Long gameId, String action, GlossaryEntry e, Long reviewerId) {
        if (gameId == null) return;
        // ko·oldKo는 null일 수 있어 Map.of를 못 쓴다.
        Map<String, Object> d = new HashMap<>();
        d.put("action", action); d.put("kind", e.getKind().name()); d.put("find", e.getFind()); d.put("ko", e.getKo()); d.put("oldKo", e.getOldKo());
        activities.startForGame(gameId, Activity.Kind.GLOSSARY_EDIT, reviewerId, d);
    }

    /** 적용으로 바뀐 행이 있는 프로젝트마다 GLOSSARY_APPLY 하나를 지연 생성한다. */
    private Function<Long, Long> applyFor(Long reviewerId) {
        return activities.lazyPerProject(Activity.Kind.GLOSSARY_APPLY, reviewerId, Map.of());
    }

    /**
     * 열린 단어장 패널이 목록을 다시 읽게 한다. 게임 전용 항목이면 그 게임의 파일에만, 공유 항목(gameId null)이면
     * 모든 게임에 걸리므로 모든 파일에 보낸다. 이벤트는 커밋 뒤에 나간다(ReviewEvents).
     */
    private void notifyChanged(Long gameId, Long reviewerId) {
        List<Project> targets = gameId == null ? projects.findAll() : projects.findByGameIdOrderByUploadedAtDesc(gameId);
        for (Project p : targets) events.glossaryChanged(p.getId(), reviewerId);
    }

    private static GlossaryDtos.EntryDto dto(GlossaryEntry e) {
        return new GlossaryDtos.EntryDto(e.getId(), e.getGameId(), e.getKind().name(), e.getMatchMode().name(),
                e.getFind(), e.getKo(), e.getNote(), e.getOldKo(), e.getGameId() == null, e.getUpdatedAt());
    }

    private static String trimmedFind(String find) {
        String f = find == null ? "" : find.trim();
        if (f.isEmpty()) throw new IllegalArgumentException("찾을 문자열을 입력하세요.");
        return f;
    }

    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s; }

    /**
     * old_ko는 SRC+PARTIAL에서만 뜻이 있다(설계 §1.2). 다른 조합은 무시하고 null. 다듬어서 비면 null.
     * ko와 같으면 바꿀 게 없는 항목이라 거절한다.
     */
    private static String oldKoFor(Kind kind, MatchMode mode, String oldKo, String ko) {
        if (kind != Kind.SRC || mode != MatchMode.PARTIAL) return null;
        String o = oldKo == null ? null : oldKo.trim();
        if (o == null || o.isEmpty()) return null;
        if (ko != null && o.equals(ko.trim())) throw new IllegalArgumentException("기존 표기와 바꿀 표기가 같습니다.");
        return o;
    }

    private static Kind parseKind(String s) {
        try {
            return Kind.valueOf(s);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("kind 값이 올바르지 않습니다: " + s);
        }
    }

    private static MatchMode parseMode(String s) {
        try {
            return MatchMode.valueOf(s);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("matchMode 값이 올바르지 않습니다: " + s);
        }
    }
}
