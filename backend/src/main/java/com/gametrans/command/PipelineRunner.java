package com.gametrans.command;

import com.gametrans.common.NotFoundException;
import com.gametrans.josa.JosaClient;
import com.gametrans.josa.JosaHint;
import com.gametrans.josa.JosaLive;
import com.gametrans.normalize.SegmentRepository;
import com.gametrans.project.ProjectRepository;
import com.gametrans.text.PlaceholderCheck;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 작업 목록을 DB와 Kiwi 사이드카에 잇는다(2026-10-08): 시트 행 읽기, 게임 id, Sidecar 구현, 태그 짝 검사. */
@Service
public class PipelineRunner {
    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);
    public static final String SIDECAR_DOWN = "띄어쓰기·조사 검사기에 연결하지 못했습니다. 잠시 뒤 다시 해 주세요.";

    /** 원문에만 있는 토큰과 번역에만 있는 토큰(개수까지 본다). */
    public record TagMismatch(SheetRow row, List<String> srcOnly, List<String> koOnly) {}

    private final SegmentRepository segments;
    private final ProjectRepository projects;
    private final JosaLive live;
    private final JosaClient josa;

    public PipelineRunner(SegmentRepository segments, ProjectRepository projects, JosaLive live, JosaClient josa) {
        this.segments = segments;
        this.projects = projects;
        this.live = live;
        this.josa = josa;
    }

    public List<SheetRow> rows(Long projectId, String sheet) {
        return segments.findCommandRowsInSheet(projectId, sheet).stream()
                .map(r -> new SheetRow(r.getId(), r.getRowNo(), r.getSrc(), r.getKo(), r.isConfirmed(), r.getJosaChecked(), r.getJosaHint(), r.isJosaIgnored()))
                .toList();
    }

    public Long gameId(Long projectId) {
        return projects.findById(projectId).map(p -> p.getGame().getId())
                .orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId));
    }

    public Pipeline.Sidecar sidecar(Long gameId) {
        return new Pipeline.Sidecar() {
            @Override public List<String> tagSpace(List<String> texts) {
                if (!josa.enabled()) throw new IllegalStateException(SIDECAR_DOWN);
                try { return josa.tagSpace(texts); }
                catch (RuntimeException e) { log.warn("태그 띄어쓰기 실패: {}", e.getMessage()); throw new IllegalStateException(SIDECAR_DOWN); }
            }
            @Override public List<List<JosaHint>> josa(List<String> texts) {
                if (!live.enabled()) throw new IllegalStateException(SIDECAR_DOWN);
                try { return live.check(gameId, texts); }
                catch (RuntimeException e) { log.warn("조사 검사 실패: {}", e.getMessage()); throw new IllegalStateException(SIDECAR_DOWN); }
            }
        };
    }

    public List<TagMismatch> tagMismatches(List<SheetRow> rows) {
        List<TagMismatch> out = new ArrayList<>();
        for (SheetRow r : rows) {
            if (PlaceholderCheck.matches(r.src(), r.ko())) continue;
            List<String> src = PlaceholderCheck.tokens(r.src()), ko = PlaceholderCheck.tokens(r.ko());
            out.add(new TagMismatch(r, minus(src, ko), minus(ko, src)));
        }
        return out;
    }

    private static List<String> minus(List<String> a, List<String> b) {
        List<String> rest = new ArrayList<>(b), out = new ArrayList<>();
        for (String t : a) if (!rest.remove(t)) out.add(t);
        return out;
    }
}
