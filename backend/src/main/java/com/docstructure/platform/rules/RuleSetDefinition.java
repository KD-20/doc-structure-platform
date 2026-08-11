package com.docstructure.platform.rules;

import java.util.List;

public record RuleSetDefinition(String docType, List<FieldRule> fields) {
}
