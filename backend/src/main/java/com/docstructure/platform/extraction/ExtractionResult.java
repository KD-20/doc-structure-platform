package com.docstructure.platform.extraction;

import com.docstructure.platform.search.ExtractedDataStatus;

import java.util.Map;

public record ExtractionResult(Map<String, FieldOutcome> fields, double overallConfidence, ExtractedDataStatus status) {
}
