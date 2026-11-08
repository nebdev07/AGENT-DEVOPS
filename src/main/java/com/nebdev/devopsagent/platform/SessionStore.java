package com.nebdev.devopsagent.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class SessionStore extends PlatformJsonStore<PlatformModels.Session> {

    public SessionStore(DevopsProperties properties, ObjectMapper mapper) {
        super(properties, mapper, "sessions.json", new TypeReference<>() {});
    }

    public synchronized Optional<PlatformModels.Session> findValid(String id, Instant now) throws IOException {
        purgeExpired(now);
        return readAll().stream()
                .filter(s -> s.id().equals(id) && s.validAt(now))
                .findFirst();
    }

    public synchronized PlatformModels.Session create(String accountId, Instant expiresAt) throws IOException {
        PlatformModels.Session session = new PlatformModels.Session(
                UUID.randomUUID().toString(), accountId, expiresAt, Instant.now());
        List<PlatformModels.Session> all = readAll();
        all.add(session);
        writeAll(all);
        return session;
    }

    public synchronized void delete(String id) throws IOException {
        List<PlatformModels.Session> all = readAll();
        all.removeIf(s -> s.id().equals(id));
        writeAll(all);
    }

    public synchronized void deleteByAccount(String accountId) throws IOException {
        if (accountId == null || accountId.isBlank()) {
            return;
        }
        List<PlatformModels.Session> all = readAll();
        int before = all.size();
        all.removeIf(s -> accountId.equals(s.accountId()));
        if (all.size() != before) {
            writeAll(all);
        }
    }

    public synchronized void purgeExpired(Instant now) throws IOException {
        List<PlatformModels.Session> all = readAll();
        int before = all.size();
        all.removeIf(s -> !s.validAt(now));
        if (all.size() != before) {
            writeAll(all);
        }
    }
}
