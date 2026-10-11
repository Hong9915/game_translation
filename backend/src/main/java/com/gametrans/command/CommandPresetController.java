package com.gametrans.command;

import com.gametrans.command.CommandPresetService.CreateRequest;
import com.gametrans.command.CommandPresetService.PresetDto;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 저장 명령(2026-10-08): 명령으로 고치기 창의 버튼. */
@RestController
@RequestMapping("/api/review/{projectId}/command-presets")
public class CommandPresetController {
    private final CommandPresetService service;

    public CommandPresetController(CommandPresetService service) { this.service = service; }

    @GetMapping
    public List<PresetDto> list(@PathVariable Long projectId) { return service.list(projectId); }

    @PostMapping
    public PresetDto create(@PathVariable Long projectId, @RequestBody CreateRequest body,
                            @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.create(projectId, body, reviewerId);
    }

    @DeleteMapping("/{presetId}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long presetId) {
        service.delete(projectId, presetId);
        return ResponseEntity.noContent().build();
    }
}
