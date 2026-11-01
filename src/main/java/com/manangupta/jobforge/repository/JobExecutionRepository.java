package com.manangupta.jobforge.repository;

import com.manangupta.jobforge.domain.JobExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobExecutionRepository extends JpaRepository<JobExecution, UUID> {

    List<JobExecution> findByJobIdOrderByAttemptAsc(UUID jobId);
}
