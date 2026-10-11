package com.gametrans.storage;

import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

public class SupabaseStorage implements FileStorage {
    private final RestClient client;
    private final String bucket;

    public SupabaseStorage(RestClient.Builder builder, String supabaseUrl, String serviceKey, String bucket) {
        this.client = builder.baseUrl(supabaseUrl + "/storage/v1/object")
                .defaultHeader("Authorization", "Bearer " + serviceKey)
                .defaultHeader("apikey", serviceKey)
                .build();
        this.bucket = bucket;
    }

    @Override public void put(String path, byte[] bytes) {
        client.post().uri("/{bucket}/{path}", bucket, path)
                .header("x-upsert", "true")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes)
                .retrieve().toBodilessEntity();
    }

    @Override public byte[] get(String path) {
        return client.get().uri("/{bucket}/{path}", bucket, path).retrieve().body(byte[].class);
    }

    @Override public void deleteAll(String prefix) {
        // Storage API: 목록 조회 후 경로 배열로 일괄 삭제
        List<Map<String, Object>> objects = client.post().uri("/list/{bucket}", bucket)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("prefix", prefix, "limit", 1000))
                .retrieve().body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        if (objects == null || objects.isEmpty()) return;
        List<String> paths = objects.stream().map(o -> prefix + "/" + o.get("name")).toList();
        client.method(org.springframework.http.HttpMethod.DELETE).uri("/{bucket}", bucket)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("prefixes", paths))
                .retrieve().toBodilessEntity();
    }
}
