package com.gametrans;

import com.gametrans.josa.JosaClient;
import com.gametrans.josa.JosaHint;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.ResourceAccessException;

/** 문장 → 제안을 미리 정해 두는 가짜. 모르는 문장은 제안 없음. */
public class FakeJosaClient implements JosaClient {
    public final Map<String, List<JosaHint>> answers = new HashMap<>();
    public final List<Collection<String>> protectedSeen = new ArrayList<>();
    /** 띄어쓰기 교정: 공백 없는 표기 → 표준 꼴. 모르는 표기는 그대로 돌려준다. */
    public final Map<String, String> spaced = new HashMap<>();
    /** 태그 경계 띄어쓰기: 번역문 → 공백을 더한 꼴. 모르는 문장은 그대로 돌려준다. 받은 문장은 tagSpaceSeen에 남는다. */
    public final Map<String, String> tagSpaced = new HashMap<>();
    public final List<String> tagSpaceSeen = new ArrayList<>();
    public Runnable duringCheck = () -> {};
    public boolean failing = false;

    public void reset() { answers.clear(); protectedSeen.clear(); spaced.clear(); tagSpaced.clear(); tagSpaceSeen.clear(); duringCheck = () -> {}; failing = false; }

    @Override public boolean enabled() { return true; }

    @Override
    public List<List<JosaHint>> check(List<String> texts, Collection<String> protectedWords) {
        if (failing) throw new ResourceAccessException("josa down");
        protectedSeen.add(protectedWords);
        duringCheck.run();
        return texts.stream().map(t -> answers.getOrDefault(t, List.of())).toList();
    }

    @Override
    public List<String> space(List<String> texts) {
        if (failing) throw new ResourceAccessException("josa down");
        return texts.stream().map(t -> spaced.getOrDefault(t, t)).toList();
    }

    @Override
    public List<String> tagSpace(List<String> texts) {
        if (failing) throw new ResourceAccessException("josa down");
        tagSpaceSeen.addAll(texts);
        return texts.stream().map(t -> tagSpaced.getOrDefault(t, t)).toList();
    }
}
