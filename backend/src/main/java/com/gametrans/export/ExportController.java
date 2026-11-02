package com.gametrans.export;

import com.gametrans.export.ExportDtos.ExportFile;
import com.gametrans.export.ExportDtos.ExportOptions;
import com.gametrans.export.ExportDtos.ExportPreview;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{id}/export")
public class ExportController {
    private final ExportService service;

    public ExportController(ExportService service) { this.service = service; }

    @GetMapping("/preview")
    public ExportPreview preview(@PathVariable Long id, @RequestParam(required = false) String sheet,
                                 @RequestParam(required = false) Double ratio, @RequestParam(defaultValue = "false") boolean single) {
        return service.preview(id, sheet, ratio, single);
    }

    @GetMapping("/versions")
    public java.util.List<ExportDtos.ExportVersionDto> versions(@PathVariable Long id) { return service.versions(id); }

    @GetMapping("/versions/{versionId}")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @PathVariable Long versionId) { return file(service.download(id, versionId)); }

    @GetMapping("/original")
    public ResponseEntity<byte[]> original(@PathVariable Long id) { return file(service.original(id)); }

    @PostMapping
    public ResponseEntity<byte[]> export(@PathVariable Long id, @RequestBody ExportOptions opts) {
        return file(service.export(id, opts));
    }

    private static ResponseEntity<byte[]> file(ExportFile f) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(f.fileName().endsWith(".csv") ? MediaType.parseMediaType("text/csv;charset=UTF-8")
                        : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(f.bytes());
    }
}
