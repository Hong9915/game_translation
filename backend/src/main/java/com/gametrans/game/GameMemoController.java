package com.gametrans.game;

import com.gametrans.game.GameMemoService.MemoDto;
import com.gametrans.game.GameMemoService.SaveMemoRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games/{id}/memo")
public class GameMemoController {
    private final GameMemoService service;

    public GameMemoController(GameMemoService service) { this.service = service; }

    @GetMapping
    public MemoDto get(@PathVariable Long id) { return service.get(id); }

    @PutMapping
    public MemoDto save(@PathVariable Long id, @RequestBody SaveMemoRequest req,
                        @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.save(id, req, reviewerId);
    }
}
