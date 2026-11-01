package com.manangupta.jobforge.repository;

import com.manangupta.jobforge.domain.ClusterNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ClusterNodeRepository extends JpaRepository<ClusterNode, String> {

    List<ClusterNode> findByLastSeenAfterOrderByStartedAtAsc(Instant cutoff);
}
