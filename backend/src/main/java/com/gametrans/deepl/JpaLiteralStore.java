package com.gametrans.deepl;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** literal_cache 테이블(V21). 직역은 참고용이라 읽기·쓰기가 실패해도 화면은 직역 없이 그대로 쓴다 — 예외를 삼키고 로그만 남긴다. */
@Component
public class JpaLiteralStore implements LiteralStore {
    private static final Logger log = LoggerFactory.getLogger(JpaLiteralStore.class);

    private final LiteralCacheRepository repo;

    public JpaLiteralStore(LiteralCacheRepository repo) { this.repo = repo; }

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> load(Collection<String> texts) {
        Map<String, String> out = new HashMap<>();
        if (texts.isEmpty()) return out;
        try {
            for (LiteralCache c : repo.findAllById(texts)) out.put(c.getSrc(), c.getKo());
        } catch (DataAccessException e) {
            log.warn("저장된 직역을 읽지 못했다: {}", e.getMessage());
        }
        return out;
    }

    @Override
    @Transactional
    public void save(Map<String, String> literal) {
        if (literal.isEmpty()) return;
        try {
            // 두 요청이 같은 원문을 동시에 저장하면 같은 키로 merge할 뿐이다(값도 같은 DeepL 결과)
            repo.saveAll(literal.entrySet().stream().map(e -> new LiteralCache(e.getKey(), e.getValue())).toList());
        } catch (DataAccessException e) {
            log.warn("직역을 저장하지 못했다({}개): {}", literal.size(), e.getMessage());
        }
    }
}
