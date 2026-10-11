package com.gametrans.deepl;

import java.util.Collection;
import java.util.Map;

/** DeepL 직역을 오래 두는 곳. DeepLClient가 메모리 다음으로 보고, 새로 받은 직역을 넣는다. */
public interface LiteralStore {
    /** 저장된 것만, 원문 → 직역. */
    Map<String, String> load(Collection<String> texts);

    void save(Map<String, String> literal);
}
