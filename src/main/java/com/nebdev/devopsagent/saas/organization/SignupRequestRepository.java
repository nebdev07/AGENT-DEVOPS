package com.nebdev.devopsagent.saas.organization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SignupRequestRepository extends JpaRepository<SignupRequest, UUID> {

    Optional<SignupRequest> findByEmailIgnoreCaseAndStatus(String email, String status);

    List<SignupRequest> findByStatusOrderByCreatedAtDesc(String status);
}
