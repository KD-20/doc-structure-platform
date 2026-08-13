package com.docstructure.platform.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class LocalDiskStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskStorageService.class);

    private final Path basePath;

    public LocalDiskStorageService(@Value("${platform.storage.base-path}") String basePathConfig) {
        this.basePath = Paths.get(basePathConfig).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.basePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create storage directory " + this.basePath, e);
        }
        log.info("local disk storage initialized basePath={}", this.basePath);
    }

    @Override
    public String store(UUID tenantId, UUID documentId, String filename, InputStream content) throws IOException {
        String relativePath = tenantId + "/" + documentId + "__" + sanitize(filename);
        Path target = resolveSafe(relativePath);
        Files.createDirectories(target.getParent());
        long bytesWritten = Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        log.debug("stored file tenant={} path={} bytes={}", tenantId, relativePath, bytesWritten);
        return relativePath;
    }

    @Override
    public InputStream retrieve(String storagePath) throws IOException {
        return Files.newInputStream(resolveSafe(storagePath));
    }

    @Override
    public void delete(String storagePath) throws IOException {
        boolean deleted = Files.deleteIfExists(resolveSafe(storagePath));
        log.debug("delete storagePath={} existed={}", storagePath, deleted);
    }

    /** Rejects any path.resolve() result that would escape basePath (covers both write and read). */
    private Path resolveSafe(String storagePath) throws IOException {
        Path target = basePath.resolve(storagePath).normalize();
        if (!target.startsWith(basePath)) {
            throw new IOException("Storage path escapes base directory: " + storagePath);
        }
        return target;
    }

    private String sanitize(String filename) {
        String base = Paths.get(filename).getFileName().toString();
        return base.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
