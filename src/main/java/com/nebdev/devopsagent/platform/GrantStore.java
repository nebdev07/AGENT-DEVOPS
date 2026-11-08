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
public class GrantStore extends PlatformJsonStore<PlatformModels.ServerGrant> {

    public GrantStore(DevopsProperties properties, ObjectMapper mapper) {
        super(properties, mapper, "server-grants.json", new TypeReference<>() {});
    }

    public synchronized List<PlatformModels.ServerGrant> list() throws IOException {
        return List.copyOf(readAll());
    }

    public synchronized List<PlatformModels.ServerGrant> forAccount(String accountId) throws IOException {
        return readAll().stream().filter(g -> g.accountId().equals(accountId)).toList();
    }

    public synchronized Optional<PlatformModels.ServerGrant> find(String accountId, String host, int port, String user)
            throws IOException {
        String h = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        String u = user == null ? "" : user.strip();
        int p = port <= 0 ? 22 : port;
        return readAll().stream()
                .filter(g -> g.accountId().equals(accountId)
                        && g.host().equals(h)
                        && g.port() == p
                        && g.user().equals(u))
                .findFirst();
    }

    public synchronized boolean hasAccess(String accountId, String host, int port, String user) throws IOException {
        return find(accountId, host, port, user).isPresent();
    }

    public synchronized PlatformModels.ServerGrant assign(String accountId, String host, int port, String user,
                                                          String label) throws IOException {
        Optional<PlatformModels.ServerGrant> existing = find(accountId, host, port, user);
        if (existing.isPresent()) {
            return existing.get();
        }
        PlatformModels.ServerGrant grant = new PlatformModels.ServerGrant(
                UUID.randomUUID().toString(), accountId, host, port <= 0 ? 22 : port, user, label, Instant.now());
        List<PlatformModels.ServerGrant> all = readAll();
        all.add(grant);
        writeAll(all);
        return grant;
    }

    public synchronized void revoke(String grantId) throws IOException {
        List<PlatformModels.ServerGrant> all = readAll();
        boolean removed = all.removeIf(g -> g.id().equals(grantId));
        if (!removed) {
            throw new IllegalArgumentException("Assignation inconnue.");
        }
        writeAll(all);
    }
}
