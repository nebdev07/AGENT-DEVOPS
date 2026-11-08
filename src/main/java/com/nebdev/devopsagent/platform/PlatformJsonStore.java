package com.nebdev.devopsagent.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nebdev.devopsagent.config.DevopsProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Persistance JSON atomique sous {@code state/platform/}. */
abstract class PlatformJsonStore<T> {

    private final Path file;
    private final ObjectMapper mapper;
    private final TypeReference<List<T>> type;

    protected PlatformJsonStore(DevopsProperties properties, ObjectMapper mapper, String fileName,
                                TypeReference<List<T>> type) {
        this.file = Path.of(properties.getStateDir()).resolve("platform").resolve(fileName);
        this.mapper = mapper.copy()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.type = type;
    }

    protected Path file() {
        return file;
    }

    protected synchronized List<T> readAll() throws IOException {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        List<T> all = mapper.readValue(file.toFile(), type);
        return all == null ? new ArrayList<>() : new ArrayList<>(all);
    }

    protected synchronized void writeAll(List<T> all) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        mapper.writeValue(tmp.toFile(), all);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
