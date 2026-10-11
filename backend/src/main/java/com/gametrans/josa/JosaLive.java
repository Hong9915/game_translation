package com.gametrans.josa;

import com.gametrans.glossary.GlossaryEntry;
import com.gametrans.glossary.GlossaryEntryRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 실시간 조사 검사(2026-10-08). 단어장 한국어 표기의 한글 낱말은 보호하고, 사전 명사로 보이는 제안(dictNoun)은 말뭉치에서
 * 고친 꼴이 원래 꼴만큼 쓰일 때만 남긴다. 조사 검사 작업(JosaCheckJob)과 명령으로 고치기가 같이 쓴다.
 */
@Component
public class JosaLive {
    private static final Pattern WORD = Pattern.compile("[가-힣]+");

    private final GlossaryEntryRepository glossary;
    private final JosaClient client;
    private final JosaCorpus corpus;

    public JosaLive(GlossaryEntryRepository glossary, JosaClient client, JosaCorpus corpus) {
        this.glossary = glossary;
        this.client = client;
        this.corpus = corpus;
    }

    public boolean enabled() { return client.enabled(); }

    /** texts와 같은 순서로 저장할 꼴의 제안. 사이드카가 실패하면 RestClientException. */
    public List<List<JosaHint>> check(Long gameId, List<String> texts) {
        List<List<JosaHint>> hints = client.check(texts, protectedWords(gameId));
        Map<String, Integer> freq = corpus.counts(gameId);
        return hints.stream().map(hs -> hs.stream()
                .filter(h -> !Boolean.TRUE.equals(h.dictNoun()) || freq.getOrDefault(h.to(), 0) >= freq.getOrDefault(h.from(), 0))
                .map(JosaHint::stored).toList()).toList();
    }

    Set<String> protectedWords(Long gameId) {
        Set<String> out = new LinkedHashSet<>();
        for (GlossaryEntry g : glossary.findByGameIdOrGameIdIsNull(gameId)) {
            if (g.getKo() == null) continue;
            Matcher m = WORD.matcher(g.getKo());
            while (m.find()) out.add(m.group());
        }
        return out;
    }
}
