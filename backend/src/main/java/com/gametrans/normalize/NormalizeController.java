package com.gametrans.normalize;

import com.gametrans.common.NotFoundException;
import com.gametrans.normalize.NormalizeDtos.NormalizeRequest;
import com.gametrans.normalize.NormalizeDtos.NormalizeResponse;
import com.gametrans.normalize.NormalizeDtos.SheetsResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{id}")
public class NormalizeController {
    private final NormalizeService service;

    public NormalizeController(NormalizeService service) { this.service = service; }

    @GetMapping("/sheets")
    public SheetsResponse sheets(@PathVariable Long id, @RequestParam(defaultValue = "1") int headerRow) {
        return service.sheets(id, headerRow);
    }

    @PutMapping("/normalize")
    public NormalizeResponse normalize(@PathVariable Long id, @Valid @RequestBody NormalizeRequest req) {
        return service.normalize(id, req);
    }

    @GetMapping("/normalize")
    public NormalizeResponse current(@PathVariable Long id,
                                     @RequestParam(required = false) String sheet,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        return service.current(id, sheet, com.gametrans.config.Paging.page(page), com.gametrans.config.Paging.size(size))
                .orElseThrow(() -> new NotFoundException("아직 정규화하지 않았습니다."));
    }

    /** 시트를 프로젝트에서 뺀다. 시트 이름에 `%`·`#`·공백이 들어갈 수 있어 경로가 아니라 요청 파라미터로 받는다. */
    @DeleteMapping("/normalize")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeSheet(@PathVariable Long id, @RequestParam String sheet) {
        service.removeSheet(id, sheet);
    }
}
