package com.nebdev.devopsagent.saas.staging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OperationStagingRepository extends JpaRepository<OperationStaging, UUID> {
    Optional<OperationStaging> findByOrganizationIdAndId(UUID organizationId, UUID id);
}
