package com.gametrans.job;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobLogRepository extends JpaRepository<JobLog, Long> {
    List<JobLog> findByJobIdOrderBySeqAsc(Long jobId);
    long countByJobId(Long jobId);
}
