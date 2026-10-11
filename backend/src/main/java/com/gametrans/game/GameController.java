package com.gametrans.game;

import com.gametrans.game.GameDtos.GameDto;
import com.gametrans.game.GameDtos.GameRequest;
import com.gametrans.game.GameDtos.GameTermDto;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games")
public class GameController {
    private final GameService service;

    public GameController(GameService service) { this.service = service; }

    @GetMapping
    public List<GameDto> list() { return service.list(); }

    @GetMapping("/{id}")
    public GameDto get(@PathVariable Long id) { return service.summary(service.get(id)); }

    @GetMapping("/{id}/terms")
    public List<GameTermDto> terms(@PathVariable Long id) { return service.terms(id); }

    @PostMapping
    public ResponseEntity<GameDto> create(@RequestBody GameRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.summary(service.create(req.name())));
    }

    @PatchMapping("/{id}")
    public GameDto rename(@PathVariable Long id, @RequestBody GameRequest req) { return service.summary(service.rename(id, req.name())); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
