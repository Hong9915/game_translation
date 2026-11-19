package com.gametrans.connector;

import com.gametrans.activity.ActivityDtos.UndoRequest;
import com.gametrans.activity.ActivityDtos.UndoResult;
import com.gametrans.connector.ConnectorDtos.AccountDto;
import com.gametrans.connector.ConnectorDtos.LoginRequest;
import com.gametrans.connector.ConnectorDtos.NoteResult;
import com.gametrans.connector.ConnectorDtos.NotesRequest;
import com.gametrans.connector.ConnectorDtos.SheetDto;
import com.gametrans.connector.ConnectorDtos.ProposalDetail;
import com.gametrans.connector.ConnectorDtos.ProposalSummary;
import com.gametrans.connector.ConnectorDtos.ProposeRequest;
import com.gametrans.connector.ConnectorDtos.ProposeResult;
import com.gametrans.connector.ConnectorDtos.ApplyRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Docker 망 안의 MCP 서버가 부르는 API(계정 확인·시트 목록·비고·되돌리기). */
@RestController
@RequestMapping("/api/connector")
public class ConnectorController {
    private final ConnectorService service;
    private final ConnectorProposalService proposals;
    private final ConnectorReplaceService replacer;

    public ConnectorController(ConnectorService service, ConnectorProposalService proposals, ConnectorReplaceService replacer) {
        this.service = service;
        this.proposals = proposals;
        this.replacer = replacer;
    }

    @PostMapping("/login")
    public AccountDto login(@RequestBody LoginRequest req) { return service.login(req.loginId(), req.password()); }

    @GetMapping("/games/{gameId}/sheets")
    public List<SheetDto> sheets(@PathVariable Long gameId) { return service.sheets(gameId); }

    @PostMapping("/notes")
    public List<NoteResult> notes(@RequestBody NotesRequest req, @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.notes(req, reviewerId);
    }

    /** 부른 검수자가 커넥터로 고친 것(과 그 되돌림·다시 실행)만 되돌린다. 결과는 /api/activity/undo와 같다. */
    @PostMapping("/undo")
    public UndoResult undo(@RequestBody(required = false) UndoRequest req, @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return service.undo(req, reviewerId);
    }

    /** Claude가 고칠 안을 저장해 둔다. 웹 검수 화면이 체크 창으로 보여 준다. */
    @PostMapping("/proposals")
    public ProposeResult propose(@RequestBody(required = false) ProposeRequest req, @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return proposals.propose(req, reviewerId);
    }

    /** 서버가 행을 찾고 바꾼 글을 만들어 제안으로 저장한다(저장은 체크 창에서). */
    @PostMapping("/proposals/replace")
    public ConnectorDtos.ReplaceResult replace(@RequestBody(required = false) ConnectorDtos.ReplaceRequest req,
                                               @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return replacer.propose(req, reviewerId);
    }

    @GetMapping("/proposals")
    public List<ProposalSummary> pendingProposals(@RequestParam Long reviewerId, @RequestParam Long gameId) { return proposals.pending(reviewerId, gameId); }

    @GetMapping("/proposals/{id}")
    public ProposalDetail proposal(@PathVariable Long id) { return proposals.detail(id); }

    /** 체크한 행만 커넥터 저장으로 넣는다. 이미 처리됐거나 만료면 409(message·proposal). */
    @PostMapping("/proposals/{id}/apply")
    public ProposalDetail applyProposal(@PathVariable Long id, @RequestBody(required = false) ApplyRequest req,
                                        @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return proposals.apply(id, req == null ? null : req.segmentIds(), reviewerId);
    }

    @PostMapping("/proposals/{id}/dismiss")
    public ProposalDetail dismissProposal(@PathVariable Long id, @RequestHeader(value = "X-Reviewer-Id", required = false) Long reviewerId) {
        return proposals.dismiss(id, reviewerId);
    }

    @ExceptionHandler(ProposalDecidedException.class)
    public ResponseEntity<Map<String, Object>> decided(ProposalDecidedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage(), "proposal", e.getProposal()));
    }

    @GetMapping("/accounts/{id}")
    public AccountDto account(@PathVariable Long id) { return service.account(id); }
}
