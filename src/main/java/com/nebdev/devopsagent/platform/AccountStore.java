package com.nebdev.devopsagent.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Component
public class AccountStore extends PlatformJsonStore<PlatformModels.Account> {

    public AccountStore(DevopsProperties properties, ObjectMapper mapper) {
        super(properties, mapper, "accounts.json", new TypeReference<>() {});
    }

    public synchronized List<PlatformModels.Account> list() throws IOException {
        return readAll().stream().map(PlatformModels.Account::publicView).toList();
    }

    public synchronized Optional<PlatformModels.Account> findById(String id) throws IOException {
        return readAll().stream().filter(a -> a.id().equals(id)).findFirst();
    }

    public synchronized Optional<PlatformModels.Account> findByEmail(String email) throws IOException {
        String e = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        return readAll().stream().filter(a -> a.email().equals(e)).findFirst();
    }

    public synchronized PlatformModels.Account require(String id) throws IOException {
        return findById(id).orElseThrow(() -> new IllegalArgumentException("Compte inconnu."));
    }

    public synchronized PlatformModels.Account create(String email, String displayName, String roleId)
            throws IOException {
        return create(email, displayName, roleId, null, false);
    }

    public synchronized PlatformModels.Account create(String email, String displayName, String roleId,
                                                      String rawPassword, boolean mustChangePassword)
            throws IOException {
        if (findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Un compte existe déjà pour " + email);
        }
        String hash = rawPassword == null || rawPassword.isBlank() ? "" : PasswordHashes.hash(rawPassword);
        PlatformModels.Account account = new PlatformModels.Account(
                UUID.randomUUID().toString(), email, displayName, roleId, true, Instant.now(),
                hash, mustChangePassword && !hash.isEmpty());
        List<PlatformModels.Account> all = readAll();
        all.add(account);
        writeAll(all);
        return account.publicView();
    }

    public synchronized PlatformModels.Account setActive(String id, boolean active) throws IOException {
        return replace(id, previous -> new PlatformModels.Account(
                previous.id(), previous.email(), previous.displayName(), previous.roleId(), active,
                previous.createdAt(), previous.passwordHash(), previous.mustChangePassword()));
    }

    public synchronized PlatformModels.Account setRole(String id, String roleId) throws IOException {
        return replace(id, previous -> new PlatformModels.Account(
                previous.id(), previous.email(), previous.displayName(), roleId, previous.active(),
                previous.createdAt(), previous.passwordHash(), previous.mustChangePassword()));
    }

    public synchronized PlatformModels.Account setPassword(String id, String rawPassword, boolean mustChange)
            throws IOException {
        String hash = PasswordHashes.hash(rawPassword);
        return replace(id, previous -> new PlatformModels.Account(
                previous.id(), previous.email(), previous.displayName(), previous.roleId(), previous.active(),
                previous.createdAt(), hash, mustChange));
    }

    public synchronized boolean isEmpty() throws IOException {
        return readAll().isEmpty();
    }

    private interface Mutator {
        PlatformModels.Account apply(PlatformModels.Account previous);
    }

    private PlatformModels.Account replace(String id, Mutator mutator) throws IOException {
        List<PlatformModels.Account> all = readAll();
        PlatformModels.Account previous = all.stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Compte inconnu."));
        PlatformModels.Account updated = mutator.apply(previous);
        all.removeIf(a -> a.id().equals(id));
        all.add(updated);
        writeAll(all);
        return updated.publicView();
    }
}
