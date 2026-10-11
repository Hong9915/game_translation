package com.gametrans.job;

import java.util.List;

public final class JobDtos {
    private JobDtos() {}

    public record JobDto(Long id, Long projectId, String kind, String status, int totalBatches, int doneBatches, List<String> logs) {
        public static JobDto of(TranslationJob j, List<String> logs) {
            return new JobDto(j.getId(), j.getProjectId(), j.getKind().name(), j.getStatus().name(), j.getTotalBatches(), j.getDoneBatches(), logs);
        }
    }
    public record Progress(int done, int total) {}
    public record LogLine(String line) {}
    /** 프로젝트 활동 스트림 한 건. type: started | log | progress | done. */
    public record Activity(String type, Long jobId, String kind, String status, int done, int total, String line, String at) {
        public static Activity of(String type, TranslationJob j, String line) {
            return new Activity(type, j.getId(), j.getKind().name(), j.getStatus().name(), j.getDoneBatches(), j.getTotalBatches(), line,
                    java.time.Instant.now().toString());
        }
    }
    /** 프로젝트 스트림 첫 이벤트: 최근 작업 몇 개(로그 꼬리 포함). */
    public record ActivitySnapshot(List<JobDto> recent) {}
}
