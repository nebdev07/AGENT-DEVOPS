package com.nebdev.devopsagent.saas.operation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class OperationRepositories {

    private OperationRepositories() {}

    public interface Operations extends JpaRepository<OperationEntities.Operation, UUID> {
        Optional<OperationEntities.Operation> findByIdAndOrganizationId(UUID id, UUID org);

        Optional<OperationEntities.Operation> findByOrganizationIdAndIdempotencyKey(UUID org, String key);

        List<OperationEntities.Operation> findByOrganizationIdOrderByCreatedAtDesc(UUID org, Pageable page);

        List<OperationEntities.Operation> findByOrganizationIdAndServerIdOrderByCreatedAtDesc(UUID org, UUID server, Pageable page);

        List<OperationEntities.Operation> findByOrganizationIdAndStatusOrderByCreatedAtDesc(UUID org, String status, Pageable page);

        List<OperationEntities.Operation> findByOrganizationIdAndServerIdAndStatusOrderByCreatedAtDesc(
                UUID org, UUID server, String status, Pageable page);

        /** Paramètres facultatifs sans {@code :x is null} (type indéterminé côté PostgreSQL). */
        default List<OperationEntities.Operation> search(UUID org, UUID server, String status, Pageable page) {
            if (server == null && status == null) {
                return findByOrganizationIdOrderByCreatedAtDesc(org, page);
            }
            if (status == null) {
                return findByOrganizationIdAndServerIdOrderByCreatedAtDesc(org, server, page);
            }
            if (server == null) {
                return findByOrganizationIdAndStatusOrderByCreatedAtDesc(org, status, page);
            }
            return findByOrganizationIdAndServerIdAndStatusOrderByCreatedAtDesc(org, server, status, page);
        }

        @Query("select o from Operation o where o.status = 'PROPOSED' and o.expiresAt < :now")
        List<OperationEntities.Operation> expiredProposals(@Param("now") Instant now);

        @Query("select o from Operation o where o.status = 'APPROVED' order by o.updatedAt asc")
        List<OperationEntities.Operation> approvedWaiting(Pageable page);
    }

    public interface Approvals extends JpaRepository<OperationEntities.Approval, UUID> {
        List<OperationEntities.Approval> findByOperationIdOrderByDecidedAt(UUID operationId);
    }

    public interface Executions extends JpaRepository<OperationEntities.Execution, UUID> {
        List<OperationEntities.Execution> findByOperationIdOrderByQueuedAt(UUID operationId);

        @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
        @Query("select e from Execution e where e.id = :id")
        Optional<OperationEntities.Execution> lockById(@Param("id") UUID id);

        @Query("select count(e) > 0 from Execution e where e.serverId = :server "
                + "and e.status in ('QUEUED','RUNNING')")
        boolean activeOnServer(@Param("server") UUID serverId);

        @Query(value = "select id from operation_execution where status = 'QUEUED' order by queued_at "
                + "limit 1 for update skip locked", nativeQuery = true)
        Optional<UUID> claimNext();

        @Query("select e from Execution e where e.status = 'RUNNING' and e.heartbeatAt < :before")
        List<OperationEntities.Execution> stale(@Param("before") Instant before);

        @Modifying
        @Query("update Execution e set e.heartbeatAt = :now where e.id in :ids and e.status = 'RUNNING'")
        int heartbeat(@Param("ids") List<UUID> ids, @Param("now") Instant now);

        @Modifying
        @Query("update Execution e set e.outputExcerpt = null where e.endedAt < :before "
                + "and e.outputExcerpt is not null")
        int purgeOutputs(@Param("before") Instant before);
    }
}
