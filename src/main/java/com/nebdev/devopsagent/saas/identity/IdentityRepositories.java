package com.nebdev.devopsagent.saas.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Dépôts du module identité (tables globales, hors RLS). */
public final class IdentityRepositories {

    private IdentityRepositories() {}

    public interface Users extends JpaRepository<UserAccount, UUID> {
        @Query("select u from UserAccount u where lower(u.email) = lower(:email)")
        Optional<UserAccount> findByEmail(@Param("email") String email);
    }

    public interface Staff extends JpaRepository<PlatformStaff, UUID> {}

    public interface Sessions extends JpaRepository<AuthSession, UUID> {
        Optional<AuthSession> findByTokenHash(String tokenHash);

        @Query("select s from AuthSession s where s.userId = :user and s.revokedAt is null and s.expiresAt > :now "
                + "order by s.createdAt desc")
        List<AuthSession> findActive(@Param("user") UUID userId, @Param("now") Instant now);

        @Modifying
        @Query("update AuthSession s set s.revokedAt = :now where s.userId = :user and s.revokedAt is null")
        int revokeAll(@Param("user") UUID userId, @Param("now") Instant now);

        @Modifying
        @Query("delete from AuthSession s where s.expiresAt < :before")
        int purgeExpired(@Param("before") Instant before);
    }

    public interface Tokens extends JpaRepository<OneTimeToken, UUID> {
        Optional<OneTimeToken> findByTokenHash(String tokenHash);
    }
}
