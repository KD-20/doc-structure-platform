package com.docstructure.platform.rules;

import java.util.Map;

/**
 * One field's extraction recipe within a RuleSetDefinition. `strategy` selects the
 * FieldExtractor bean (by bean name, e.g. "ANCHOR_REGEX") that interprets `params`; params
 * stays a generic map — deliberately, since that's what "config-driven" means here: adding a
 * new field or a new document type never requires a new Java class, only new JSON.
 */
public record FieldRule(String name, String type, boolean required, String strategy,
                         Map<String, Object> params, NormalizerSpec normalizer) {
}
