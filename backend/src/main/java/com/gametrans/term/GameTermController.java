package com.gametrans.term;

import com.gametrans.job.JobDtos.JobDto;
import com.gametrans.term.TermDtos.GameTermReplace;
import com.gametrans.term.TermDtos.GameTermReplaceResult;
import com.gametrans.term.TermDtos.GameTermUpdate;
import com.gametrans.term.TermDtos.GameTermUpdateResult;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games/{id}/terms")
public class GameTermController {
    private final GameTermService service;

    public GameTermController(GameTermService service) { this.service = service; }

    @PatchMapping("/{termId}")
    public GameTermUpdateResult update(@PathVariable Long id, @PathVariable Long termId, @RequestBody GameTermUpdate req) {
        return service.update(id, termId, req.ko());
    }

    @PostMapping("/replace")
    public GameTermReplaceResult replace(@PathVariable Long id, @RequestBody GameTermReplace req) {
        return service.replace(id, req.src(), req.from(), req.to());
    }

    /** 숫자만 다른 변형 용어를 기본 단어 하나로 접는 작업을 시작한다. */
    @PostMapping("/collapse")
    public JobDto collapse(@PathVariable Long id) { return service.startCollapse(id); }
}
