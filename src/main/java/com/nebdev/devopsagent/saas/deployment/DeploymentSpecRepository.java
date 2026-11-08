package com.nebdev.devopsagent.saas.deployment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentSpecRepository extends JpaRepository<DeploymentSpec, UUID> {

    List<DeploymentSpec> findByOrganizationIdAndServerIdOrderBySlugAsc(UUID organizationId, UUID serverId);

    Optional<DeploymentSpec> findByOrganizationIdAndId(UUID organizationId, UUID id);

    Optional<DeploymentSpec> findByOrganizationIdAndServerIdAndKindAndSlug(
            UUID organizationId, UUID serverId, String kind, String slug);

    boolean existsByOrganizationIdAndServerIdAndKindAndSlug(
            UUID organizationId, UUID serverId, String kind, String slug);
}
