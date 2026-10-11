package com.gametrans.josa;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpJosaClient implements JosaClient {
    private final RestClient client;
    private final boolean enabled;

    record Request(List<String> texts, @JsonProperty("protected") Collection<String> protectedWords) {}
    record Response(List<List<JosaHint>> hints) {}
    record SpaceRequest(List<String> texts) {}
    record SpaceResponse(List<String> spaced) {}

    public HttpJosaClient(RestClient.Builder builder, @Value("${app.josa.url:}") String url) {
        this.enabled = url != null && !url.isBlank();
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3_000);
        f.setReadTimeout(10_000);
        this.client = builder.baseUrl(enabled ? url : "http://localhost").requestFactory(f).build();
    }

    @Override public boolean enabled() { return enabled; }

    @Override
    public List<List<JosaHint>> check(List<String> texts, Collection<String> protectedWords) {
        Response r = client.post().uri("/check").contentType(MediaType.APPLICATION_JSON)
                .body(new Request(texts, protectedWords)).retrieve().body(Response.class);
        if (r == null || r.hints() == null || r.hints().size() != texts.size())
            throw new IllegalStateException("조사 검사 응답 개수가 요청과 다릅니다.");
        return r.hints();
    }

    @Override
    public List<String> space(List<String> texts) {
        SpaceResponse r = client.post().uri("/space").contentType(MediaType.APPLICATION_JSON)
                .body(new SpaceRequest(texts)).retrieve().body(SpaceResponse.class);
        if (r == null || r.spaced() == null || r.spaced().size() != texts.size())
            throw new IllegalStateException("띄어쓰기 응답 개수가 요청과 다릅니다.");
        return r.spaced();
    }

    @Override
    public List<String> tagSpace(List<String> texts) {
        SpaceResponse r = client.post().uri("/tag-space").contentType(MediaType.APPLICATION_JSON)
                .body(new SpaceRequest(texts)).retrieve().body(SpaceResponse.class);
        if (r == null || r.spaced() == null || r.spaced().size() != texts.size())
            throw new IllegalStateException("태그 띄어쓰기 응답 개수가 요청과 다릅니다.");
        return r.spaced();
    }
}
