package com.gametrans.command;

import com.gametrans.common.NotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 저장 명령(설계 2026-10-08 §4). 프로젝트의 게임에 묶인다. 저장할 때 작업 목록을 컴파일해 본다. */
@Service
public class CommandPresetService {
    static final int MAX_NAME = 40;

    public record PresetDto(Long id, String name, List<OpSpec> ops) {}
    public record CreateRequest(String name, List<OpSpec> ops) {}

    private final CommandPresetRepository presets;
    private final PipelineRunner runner;

    public CommandPresetService(CommandPresetRepository presets, PipelineRunner runner) {
        this.presets = presets;
        this.runner = runner;
    }

    @Transactional(readOnly = true)
    public List<PresetDto> list(Long projectId) {
        return presets.findByGameIdOrderByNameAsc(runner.gameId(projectId)).stream().map(CommandPresetService::dto).toList();
    }

    @Transactional
    public PresetDto create(Long projectId, CreateRequest req, Long reviewerId) {
        String name = req.name() == null ? "" : req.name().strip();
        if (name.isEmpty()) throw new IllegalArgumentException("이름을 적어 주세요.");
        if (name.length() > MAX_NAME) throw new IllegalArgumentException("이름이 너무 깁니다.");
        Pipeline.compile(req.ops());
        Long gameId = runner.gameId(projectId);
        if (presets.existsByGameIdAndName(gameId, name)) throw new IllegalArgumentException("같은 이름의 명령이 있습니다: " + name);
        CommandPreset p = new CommandPreset();
        p.setGameId(gameId);
        p.setName(name);
        p.setOps(List.copyOf(req.ops()));
        p.setCreatedBy(reviewerId);
        return dto(presets.save(p));
    }

    @Transactional
    public void delete(Long projectId, Long presetId) {
        CommandPreset p = presets.findByIdAndGameId(presetId, runner.gameId(projectId))
                .orElseThrow(() -> new NotFoundException("저장 명령을 찾을 수 없습니다: " + presetId));
        presets.delete(p);
    }

    private static PresetDto dto(CommandPreset p) { return new PresetDto(p.getId(), p.getName(), p.getOps()); }
}
