package com.gametrans.job;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class JobEventBus {
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    /** 프로젝트 단위 구독자: 어느 화면에 있든 그 프로젝트의 모든 작업 이벤트를 받는다. */
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> projectSubscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long jobId) {
        SseEmitter emitter = new SseEmitter(30L * 60 * 1000);
        List<SseEmitter> list = subscribers.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        emitter.onError(e -> list.remove(emitter));
        return emitter;
    }

    public void publish(Long jobId, String event, Object data) {
        send(subscribers.get(jobId), event, data);
    }

    public SseEmitter subscribeProject(Long projectId) {
        SseEmitter emitter = new SseEmitter(30L * 60 * 1000);
        List<SseEmitter> list = projectSubscribers.computeIfAbsent(projectId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        emitter.onError(e -> list.remove(emitter));
        return emitter;
    }

    /** 프로젝트 스트림에는 항상 "activity" 이벤트 하나로 보내고, 어떤 작업의 무슨 일인지는 payload가 말한다. */
    public void publishProject(Long projectId, Object activity) {
        send(projectSubscribers.get(projectId), "activity", activity);
    }

    /** 프로젝트 스트림에 activity 말고 다른 이름의 이벤트를 보낸다(공동 검토의 "review"). */
    public void publishProjectEvent(Long projectId, String event, Object data) {
        send(projectSubscribers.get(projectId), event, data);
    }

    /** 프로젝트 스트림 구독자를 모두 닫는다(테스트·서버 종료용). */
    public void completeProject(Long projectId) {
        List<SseEmitter> list = projectSubscribers.remove(projectId);
        if (list == null) return;
        for (SseEmitter e : list) { try { e.complete(); } catch (RuntimeException ignored) { } }
    }

    /**
     * IOException|IllegalStateException 둘만 잡아도 충분하다(좁히거나 "정리"하지 말 것):
     * ResponseBodyEmitter.sendInternal이 그 밖의 모든 Throwable을 IllegalStateException으로 감싸 던진다.
     * 이벤트는 afterCommit에서 나가므로, 여기서 예외가 새어 나가면 이미 커밋된 저장이 500으로 돌아간다.
     */
    private static void send(List<SseEmitter> list, String event, Object data) {
        if (list == null) return;
        for (SseEmitter e : list) {
            try { e.send(SseEmitter.event().name(event).data(data)); }
            catch (IOException | IllegalStateException ex) { list.remove(e); }
        }
    }

    public void complete(Long jobId) {
        List<SseEmitter> list = subscribers.remove(jobId);
        if (list == null) return;
        for (SseEmitter e : list) { try { e.complete(); } catch (RuntimeException ignored) { } }
    }
}
