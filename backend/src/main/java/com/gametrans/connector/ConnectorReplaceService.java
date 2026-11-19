package com.gametrans.connector;

import com.gametrans.command.PipelineRunner;
import com.gametrans.command.SheetRow;
import com.gametrans.connector.ConnectorDtos.ProposalItem;
import com.gametrans.connector.ConnectorDtos.ProposeResult;
import com.gametrans.connector.ConnectorDtos.ReplaceRequest;
import com.gametrans.connector.ConnectorDtos.ReplaceResult;
import com.gametrans.connector.ConnectorDtos.ReplaceSample;
import com.gametrans.glossary.GlossaryEntry;
import com.gametrans.glossary.GlossaryEntryRepository;
import com.gametrans.project.Project;
import com.gametrans.text.Josa;
import com.gametrans.workspace.SheetScope;
import com.gametrans.workspace.SheetScope.SheetRef;
import com.gametrans.workspace.SheetScopes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 찾아 바꾸기 제안: 서버가 행을 찾고 조사를 맞춘 글을 만들어 제안으로 저장한다. 행은 절대 직접 저장하지 않는다. */
@Service
public class ConnectorReplaceService {
    static final int MAX_EXCEPT = 500;
    static final int MAX_SAMPLES = 3;
    static final int SAMPLE_CHARS = 120;

    private final SheetScopes scopes;
    private final PipelineRunner runner;
    private final ConnectorProposalService proposals;
    private final GlossaryEntryRepository entries;

    public ConnectorReplaceService(SheetScopes scopes, PipelineRunner runner, ConnectorProposalService proposals, GlossaryEntryRepository entries) {
        this.entries = entries;
        this.scopes = scopes;
        this.runner = runner;
        this.proposals = proposals;
    }

    static void checkCount(int proposed) {
        if (proposed > ConnectorProposalService.MAX_ROWS)
            throw new IllegalArgumentException(proposed + "행이 걸렸습니다. projectId·sheet로 좁히거나 exceptRowIds를 쓰세요");
    }

    @Transactional
    public ReplaceResult propose(ReplaceRequest req, Long reviewerId) {
        if (req == null) throw new IllegalArgumentException("요청이 비었습니다.");
        String find = req.find();
        String replace = req.replace() == null ? "" : req.replace();
        String src = req.src() == null || req.src().isBlank() ? null : req.src();
        if (find == null || find.isEmpty()) throw new IllegalArgumentException("찾을 말이 필요합니다");
        if (find.equals(replace)) throw new IllegalArgumentException("바꿀 말이 찾을 말과 같습니다");
        if (src != null && replace.isBlank()) throw new IllegalArgumentException("용어 바꾸기에는 바꿀 말이 필요합니다");
        List<Long> except = req.exceptRowIds() == null ? List.of() : req.exceptRowIds();
        if (except.size() > MAX_EXCEPT) throw new IllegalArgumentException("exceptRowIds는 " + MAX_EXCEPT + "개까지입니다");
        Set<Long> exceptSet = new HashSet<>(except);

        SheetScope scope = scopeOf(req);
        List<SheetRow> hit = new ArrayList<>();
        int locked = 0;
        for (SheetRow row : runner.rows(scope).rows()) {
            if (row.ko() == null || !row.ko().contains(find) || exceptSet.contains(row.id())) continue;
            if (row.confirmed()) { locked++; continue; }
            hit.add(row);
        }
        int matched = hit.size() + locked;
        if (hit.isEmpty()) throw new IllegalArgumentException("찾을 말이 있는 행이 없습니다");
        checkCount(hit.size());

        int srcMissing = 0;
        if (src != null) {
            for (SheetRow r : hit) if (r.src() == null || !r.src().contains(src)) srcMissing++;
            int has = hit.size() - srcMissing;
            if (has * 2 < hit.size())
                throw new IllegalArgumentException("원문 용어 '" + src + "'가 걸린 행 " + hit.size() + "개 중 " + has + "개의 원문에만 있습니다 — 원문을 다시 확인하세요");
        }

        List<ProposalItem> items = new ArrayList<>();
        List<ReplaceSample> samples = new ArrayList<>();
        for (SheetRow r : hit) {
            String text = Josa.replaceAdjusting(r.ko(), find, replace);
            boolean missing = src != null && (r.src() == null || !r.src().contains(src));
            items.add(new ProposalItem(r.id(), text, r.ko(), req.reason(), null, missing ? Boolean.TRUE : null));
            if (samples.size() < MAX_SAMPLES) samples.add(new ReplaceSample(r.id(), cut(r.ko()), cut(text)));
        }
        String title = req.title() == null || req.title().isBlank() ? find + " → " + replace : req.title();
        ProposeResult res = proposals.create(reviewerId, title, items, src, src == null ? null : replace, src == null ? null : find);
        boolean termCandidate = src != null && !replace.isBlank()
                && entries.findByGameIdAndKindAndFind(scope.gameId(), GlossaryEntry.Kind.SRC, src.trim())
                        .map(e -> !replace.trim().equals(e.getKo())).orElse(true);
        return new ReplaceResult(res.proposalId(), res.presentNow(), matched, items.size(), locked, srcMissing, samples, termCandidate);
    }

    private SheetScope scopeOf(ReplaceRequest req) {
        SheetScope scope;
        if (req.projectId() != null) {
            Project p = scopes.project(req.projectId());
            List<SheetRef> sheets = scopes.projectSheets(p.getId());
            if (sheets.isEmpty()) throw new IllegalArgumentException("이 파일에는 설정된 시트가 없습니다.");
            scope = new SheetScope(p.getGame().getId(), sheets, sheets.stream().anyMatch(SheetRef::finalReview));
        } else if (req.gameId() != null) {
            scope = scopes.ofGame(req.gameId(), List.of());
        } else {
            throw new IllegalArgumentException("gameId나 projectId가 필요합니다");
        }
        if (req.sheet() == null || req.sheet().isBlank()) return scope;
        return new SheetScope(scope.gameId(), scope.sheets().stream().filter(s -> s.sheet().equals(req.sheet())).toList(), scope.finalReview());
    }

    private static String cut(String s) { return s.length() > SAMPLE_CHARS ? s.substring(0, SAMPLE_CHARS) + "…" : s; }
}
