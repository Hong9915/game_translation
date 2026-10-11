package com.gametrans.josa;

import com.gametrans.normalize.SegmentRepository;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 누군가 파일을 열면 그 파일 행을 조사 검사 대기에 올린다(2026-10-07 사용자 결정: 연 파일과 고친 행만 검사).
 * 요약은 자주 불리므로 한 번 올린 파일은 기억해 두고 건너뛴다. 재시작하면 잊지만 다시 올려도 null 행이 없어 0건이다.
 */
@Component
public class JosaRequests {
    private final SegmentRepository segments;
    private final Set<Long> opened = ConcurrentHashMap.newKeySet();

    public JosaRequests(SegmentRepository segments) { this.segments = segments; }

    public void open(Long projectId) {
        if (opened.add(projectId)) segments.requestJosa(projectId);
    }
}
