package com.nebdev.devopsagent.saas.organization;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class OrganizationRepositories {

    private OrganizationRepositories() {}

    public interface Organizations extends JpaRepository<Organization, UUID> {
        boolean existsBySlug(String slug);

        @Query("select o from Organization o where o.id in "
                + "(select m.organizationId from Membership m where m.userId = :user and m.status = 'ACTIVE') "
                + "order by o.name")
        List<Organization> forUser(@Param("user") UUID userId);
    }

    public interface Memberships extends JpaRepository<Membership, UUID> {
        Optional<Membership> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

        List<Membership> findByOrganizationIdOrderByCreatedAt(UUID organizationId);

        long countByOrganizationIdAndStatus(UUID organizationId, String status);

        long countByOrganizationIdAndRoleCodeAndStatus(UUID organizationId, String roleCode, String status);
    }

    public interface Roles extends JpaRepository<OrgRole, UUID> {
        @Query("select r from OrgRole r where r.code = :code and (r.organizationId is null or r.organizationId = :org) "
                + "order by r.organizationId nulls last")
        List<OrgRole> resolve(@Param("org") UUID organizationId, @Param("code") String code);

        @Query("select r from OrgRole r where r.organizationId is null or r.organizationId = :org order by r.system desc, r.code")
        List<OrgRole> available(@Param("org") UUID organizationId);
    }
}
