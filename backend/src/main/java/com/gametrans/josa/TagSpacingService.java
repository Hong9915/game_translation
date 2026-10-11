package com.gametrans.josa;

import com.gametrans.activity.Activity;
import com.gametrans.activity.ActivityService;
import com.gametrans.normalize.Segment;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.review.BulkFixWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 태그 경계 띄어쓰기(2026-10-08). 시트에서 태그가 든 행을 조사 검사 컨테이너(Kiwi)에 보내, 태그가 있던 경계에 공백이 더해지는 행을
 * 후보로 보이고 고른 행만 고친다. 확정·용어·제외 행은 보지도 고치지도 않는다.
 */
@Service
public class TagSpacingService {
    private static final Logger log = LoggerFactory.getLogger(TagSpacingService.class);
    /** 컨테이너에 한 번에 넣는 행 수(읽기 시간 10초 안). */
    static final int BATCH = 200;

    /** 후보 한 행: 지금 번역(before)과 태그 경계에 공백을 더한 꼴(after). */
    public record Row(Long segmentId, Integer rowNo, String src, String before, String after) {}
    /** 고칠 행 하나. before = 목록을 받을 때의 번역 — 그 사이 바뀐 행은 건너뛴다. after는 before와 공백만 달라야 한다. */
    public record Item(Long segmentId, String before, String after) {}
    public record FixRequest(List<Item> items) {}
    public record FixResult(int applied) {}

    private final SegmentRepository segments;
    private final JosaClient josa;
    private final BulkFixWriter writer;
    private final ActivityService activities;

    public TagSpacingService(SegmentRepository segments, JosaClient josa, BulkFixWriter writer, ActivityService activities) {
        this.segments = segments;
        this.josa = josa;
        this.writer = writer;
        this.activities = activities;
    }

    /** 이 목록이 할 일의 전부라 Kiwi가 꺼져 있거나 실패하면 409로 알린다. */
    @Transactional(readOnly = true)
    public List<Row> candidates(Long projectId, String sheet) {
        if (!josa.enabled()) throw new IllegalStateException("띄어쓰기 검사기가 꺼져 있습니다.");
        List<SegmentRepository.TaggedRow> rows = segments.findEditableTaggedInSheet(projectId, sheet);
        List<Row> out = new ArrayList<>();
        for (int from = 0; from < rows.size(); from += BATCH) {
            List<SegmentRepository.TaggedRow> batch = rows.subList(from, Math.min(rows.size(), from + BATCH));
            List<String> spaced;
            try {
                spaced = josa.tagSpace(batch.stream().map(SegmentRepository.TaggedRow::getKo).toList());
            } catch (RuntimeException e) {
                log.warn("태그 띄어쓰기를 받지 못했다: {}", e.getMessage());
                throw new IllegalStateException("띄어쓰기 검사기에 연결하지 못했습니다. 잠시 뒤 다시 열어 주세요.");
            }
            for (int i = 0; i < batch.size(); i++) {
                SegmentRepository.TaggedRow r = batch.get(i);
                String after = spaced.get(i);
                if (after != null && !after.equals(r.getKo()) && onlySpacingDiffers(r.getKo(), after))
                    out.add(new Row(r.getId(), r.getRowNo(), r.getSrc(), r.getKo(), after));
            }
        }
        return out;
    }

    /**
     * 고른 행을 고친다. 목록을 받은 뒤 번역이 바뀐 행(지금 값 ≠ before)과 확정·제외·다른 프로젝트 행은 건너뛴다.
     * 바뀐 행은 변경 내역 "태그 띄어쓰기" 한 건으로 남는다.
     */
    @Transactional
    public FixResult fix(Long projectId, FixRequest req, Long reviewerId) {
        List<Item> items = req.items() == null ? List.of() : req.items().stream()
                .filter(it -> it != null && it.segmentId() != null && it.before() != null && it.after() != null).toList();
        if (items.isEmpty()) throw new IllegalArgumentException("고칠 행이 없습니다.");
        for (Item it : items)
            if (!onlySpacingDiffers(it.before(), it.after()))
                throw new IllegalArgumentException("띄어쓰기만 다른 꼴만 고칠 수 있습니다: " + it.before());
        Map<Long, Item> byId = new LinkedHashMap<>();
        for (Item it : items) byId.putIfAbsent(it.segmentId(), it);
        List<Segment> rows = segments.findEditableInProject(projectId, byId.keySet());
        int applied = writer.write(rows, s -> {
            Item it = byId.get(s.getId());
            return it.before().equals(s.getLatestKo()) ? it.after() : null;
        }, reviewerId, activities.lazyPerProject(Activity.Kind.TAG_SPACING, reviewerId, Map.of()));
        return new FixResult(applied);
    }

    private static boolean onlySpacingDiffers(String a, String b) {
        return a.replaceAll("\\s+", "").equals(b.replaceAll("\\s+", ""));
    }
}
