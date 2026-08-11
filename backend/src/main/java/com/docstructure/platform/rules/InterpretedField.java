package com.docstructure.platform.rules;

public record InterpretedField(String name, Object value, double confidence, boolean required) {
    public boolean isFound() {
        return value != null;
    }
}
