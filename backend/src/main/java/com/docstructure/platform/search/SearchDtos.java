package com.docstructure.platform.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.docstructure.platform.documents.DocumentStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** SearchResultItem stays package-private — callers only ever handle it through the public SearchResponse (see SearchResponse.java). */
record SearchResultItem(UUID documentId, String filename, String contentType, String docType, DocumentStatus status,
                         double textRank, JsonNode fields, Double overallConfidence, Instant createdAt) {
}
