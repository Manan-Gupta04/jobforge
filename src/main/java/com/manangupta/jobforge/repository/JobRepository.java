package com.manangupta.jobforge.repository;

import com.manangupta.jobforge.domain.Job;
import com.manangupta.jobforge.domain.JobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    /** Candidates for execution: due, pending, highest priority first, oldest first within a priority. */
    @Query("select j from Job j where j.status = :status and j.runAt <= :now "
            + "order by j.priority desc, j.runAt asc")
    List<Job> findClaimable(@Param("status") JobStatus status, @Param("now") Instant now, Pageable page);

    /**
     * Compare-and-swap claim. Succeeds (returns 1) only if the row is still PENDING at the
     * version the caller observed, so concurrent nodes racing for the same job see exactly one
     * winner. The version bump keeps JPA optimistic locking consistent.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Job j set j.status = :running, j.leaseOwner = :owner, j.leaseUntil = :leaseUntil, "
            + "j.startedAt = :now, j.updatedAt = :now, j.attempts = j.attempts + 1, j.version = j.version + 1 "
            + "where j.id = :id and j.version = :version and j.status = :pending")
    int claim(@Param("id") UUID id, @Param("version") long version, @Param("owner") String owner,
              @Param("leaseUntil") Instant leaseUntil, @Param("now") Instant now,
              @Param("running") JobStatus running, @Param("pending") JobStatus pending);

    /** Heartbeat: extend the lease only while this node still owns the job. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Job j set j.leaseUntil = :leaseUntil where j.id = :id and j.leaseOwner = :owner "
            + "and j.status = :running")
    int extendLease(@Param("id") UUID id, @Param("owner") String owner,
                    @Param("leaseUntil") Instant leaseUntil, @Param("running") JobStatus running);

    List<Job> findByStatusAndLeaseUntilBefore(JobStatus status, Instant cutoff);

    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    @Query("select j from Job j join j.dependsOn d where d = :dependencyId")
    List<Job> findDependents(@Param("dependencyId") UUID dependencyId);

    @Query("select count(j) from Job j where j.id in :ids and j.status = :status")
    long countByIdInAndStatus(@Param("ids") java.util.Collection<UUID> ids, @Param("status") JobStatus status);

    long countByStatus(JobStatus status);

    @Query("select j.status, count(j) from Job j group by j.status")
    List<Object[]> countGroupedByStatus();

    Page<Job> findByStatus(JobStatus status, Pageable pageable);

    Page<Job> findByType(String type, Pageable pageable);

    Page<Job> findByStatusAndType(JobStatus status, String type, Pageable pageable);
}
