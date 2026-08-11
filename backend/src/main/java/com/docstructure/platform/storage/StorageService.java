package com.docstructure.platform.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Persists raw uploaded file bytes. LocalDiskStorageService is the only implementation for
 * v1; an S3StorageService can be added later behind this same interface with no callers
 * changing — same pluggability pattern as ExtractionStrategy and EmbeddingProvider.
 */
public interface StorageService {

    /** Returns an opaque storage path/key that DocumentService persists on the Document row. */
    String store(UUID tenantId, UUID documentId, String filename, InputStream content) throws IOException;

    InputStream retrieve(String storagePath) throws IOException;

    void delete(String storagePath) throws IOException;
}
