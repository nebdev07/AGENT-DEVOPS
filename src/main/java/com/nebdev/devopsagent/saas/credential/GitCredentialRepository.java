package com.nebdev.devopsagent.saas.credential;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GitCredentialRepository extends JpaRepository<GitCredential, UUID> {

    List<GitCredential> findByOrganizationIdOrderByKeyIdAsc(UUID organizationId);

    Optional<GitCredential> findByOrganizationIdAndKeyId(UUID organizationId, String keyId);

    Optional<GitCredential> findByOrganizationIdAndId(UUID organizationId, UUID id);
}
