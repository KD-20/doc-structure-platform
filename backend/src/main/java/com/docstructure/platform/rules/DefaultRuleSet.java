package com.docstructure.platform.rules;

import com.docstructure.platform.common.BaseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;

import java.time.Instant;

/** A platform-shipped baseline rule set for a doc type, used as a fallback when a tenant has no active custom rule set of its own. Not tenant-scoped. */
@Entity
@Table(name = "default_rule_sets")
public class DefaultRuleSet extends BaseEntity {

    @Column(name = "doc_type", nullable = false, unique = true)
    private String docType;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode definition;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public JsonNode getDefinition() {
        return definition;
    }

    public void setDefinition(JsonNode definition) {
        this.definition = definition;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
