package com.docstructure.platform.search;

import com.docstructure.platform.common.BaseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * embedding/embedding_model columns exist in the DB (see V1__init_schema.sql) but are
 * deliberately NOT mapped here: adding a pgvector Java type/dependency just for a
 * write-then-never-read-via-JPA column isn't worth it. ExtractionService writes the vector with
 * a native UPDATE (see writeEmbedding) after this entity is already saved, and SearchService
 * reads it back the same way (native SQL, `<=>` cosine distance) — see docs/DECISIONS.md.
 */
@Entity
@Table(name = "extracted_data")
public class ExtractedData extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "extraction_run_id", nullable = false)
    private UUID extractionRunId;

    @Column(name = "doc_type", nullable = false)
    private String docType;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode fields;

    @Column(name = "overall_confidence")
    private BigDecimal overallConfidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExtractedDataStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public UUID getExtractionRunId() {
        return extractionRunId;
    }

    public void setExtractionRunId(UUID extractionRunId) {
        this.extractionRunId = extractionRunId;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public JsonNode getFields() {
        return fields;
    }

    public void setFields(JsonNode fields) {
        this.fields = fields;
    }

    public BigDecimal getOverallConfidence() {
        return overallConfidence;
    }

    public void setOverallConfidence(BigDecimal overallConfidence) {
        this.overallConfidence = overallConfidence;
    }

    public ExtractedDataStatus getStatus() {
        return status;
    }

    public void setStatus(ExtractedDataStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
