package com.docstructure.platform.rules;

public record FieldExtractionResult(Object value, double confidence) {
    public static FieldExtractionResult notFound() {
        return new FieldExtractionResult(null, 0.0);
    }

    public boolean isFound() {
        return value != null;
    }
}
