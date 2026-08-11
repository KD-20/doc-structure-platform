package com.docstructure.platform.rules;

import com.docstructure.platform.common.ApiExceptions;

import java.util.List;
import java.util.Map;

/** Typed access into a FieldRule/NormalizerSpec's generic params map, with clear errors for the rule-set preview/editor UX. */
public record RuleParams(Map<String, Object> raw) {

    public String requireString(String key) {
        Object v = raw.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new ApiExceptions.ValidationException("Rule param '" + key + "' must be a non-blank string");
        }
        return s;
    }

    public String getString(String key, String defaultValue) {
        Object v = raw.get(key);
        return v instanceof String s ? s : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        Object v = raw.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    public List<String> requireStringList(String key) {
        Object v = raw.get(key);
        if (!(v instanceof List<?> list)) {
            throw new ApiExceptions.ValidationException("Rule param '" + key + "' must be a list of strings");
        }
        return (List<String>) list;
    }
}
