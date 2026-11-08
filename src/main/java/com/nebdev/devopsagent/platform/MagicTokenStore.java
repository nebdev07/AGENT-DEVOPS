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
public class MagicTokenStore extends PlatformJsonStore<PlatformModels.MagicToken> {

    public MagicTokenStore(DevopsProperties properties, ObjectMapper mapper) {
        super(properties, mapper, "magic-tokens.json", new TypeReference<>() {});
    }

    public synchronized PlatformModels.MagicToken create(String accountId, String email, Instant expiresAt)
            throws IOException {
        PlatformModels.MagicToken token = new PlatformModels.MagicToken(
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
                accountId, email, expiresAt, false);
        List<PlatformModels.MagicToken> all = readAll();
        all.removeIf(t -> t.accountId().equals(accountId) && !t.used());
        all.add(token);
        writeAll(all);
        return token;
    }

    public synchronized Optional<PlatformModels.MagicToken> consume(String id, Instant now) throws IOException {
        List<PlatformModels.MagicToken> all = readAll();
        PlatformModels.MagicToken found = null;
        for (PlatformModels.MagicToken t : all) {
            if (t.id().equals(id)) {
                found = t;
                break;
            }
        }
        if (found == null || found.used() || !found.expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        PlatformModels.MagicToken used = new PlatformModels.MagicToken(
                found.id(), found.accountId(), found.email(), found.expiresAt(), true);
        all.removeIf(t -> t.id().equals(id));
        all.add(used);
        writeAll(all);
        return Optional.of(used);
    }
}
