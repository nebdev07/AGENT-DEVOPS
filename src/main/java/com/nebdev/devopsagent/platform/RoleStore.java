package com.nebdev.devopsagent.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebdev.devopsagent.config.DevopsProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class RoleStore extends PlatformJsonStore<PlatformModels.Role> {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String OPERATOR = "OPERATOR";
    public static final String VIEWER = "VIEWER";

    public RoleStore(DevopsProperties properties, ObjectMapper mapper) {
        super(properties, mapper, "roles.json", new TypeReference<>() {});
    }

    public synchronized List<PlatformModels.Role> list() throws IOException {
        ensureSeed();
        return List.copyOf(readAll());
    }

    public synchronized Optional<PlatformModels.Role> find(String id) throws IOException {
        ensureSeed();
        return readAll().stream().filter(r -> r.id().equals(id)).findFirst();
    }

    public synchronized PlatformModels.Role require(String id) throws IOException {
        return find(id).orElseThrow(() -> new IllegalArgumentException("Rôle inconnu : " + id));
    }

    public synchronized PlatformModels.Role updatePermissions(String id, List<String> permissions) throws IOException {
        ensureSeed();
        if (SUPER_ADMIN.equals(id) && !permissions.contains(Permission.PLATFORM_ADMIN.code())) {
            throw new IllegalArgumentException("SUPER_ADMIN doit conserver platform.admin.");
        }
        List<PlatformModels.Role> all = readAll();
        PlatformModels.Role previous = all.stream().filter(r -> r.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Rôle inconnu : " + id));
        PlatformModels.Role updated = new PlatformModels.Role(previous.id(), previous.label(), permissions);
        all.removeIf(r -> r.id().equals(id));
        all.add(updated);
        writeAll(all);
        return updated;
    }

    private void ensureSeed() throws IOException {
        if (fileExists()) {
            return;
        }
        List<PlatformModels.Role> seed = new ArrayList<>();
        seed.add(new PlatformModels.Role(SUPER_ADMIN, "Super administrateur", List.copyOf(Permission.allCodes())));
        seed.add(new PlatformModels.Role(OPERATOR, "Opérateur", List.of(
                Permission.SERVER_READ.code(),
                Permission.SERVER_ANALYZE.code(),
                Permission.SERVER_CHANGE_INSTALL.code(),
                Permission.SERVER_CHANGE_DEPLOY.code(),
                Permission.SERVER_CHANGE_BACKUP.code(),
                Permission.SERVER_CHANGE_NOTIFY.code(),
                Permission.SERVER_CHANGE_SECRET.code(),
                Permission.SERVER_JOURNAL.code(),
                Permission.AUDIT_READ.code()
        )));
        seed.add(new PlatformModels.Role(VIEWER, "Lecture seule", List.of(
                Permission.SERVER_READ.code(),
                Permission.SERVER_JOURNAL.code(),
                Permission.AUDIT_READ.code()
        )));
        writeAll(seed);
    }

    private boolean fileExists() {
        return java.nio.file.Files.isRegularFile(file());
    }
}
