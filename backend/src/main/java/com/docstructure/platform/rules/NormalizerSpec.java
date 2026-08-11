package com.docstructure.platform.rules;

import java.util.Map;

/** normalizer.type selects the Normalizer bean (e.g. "DATE", "CURRENCY"); null means NoOpNormalizer. */
public record NormalizerSpec(String type, Map<String, Object> params) {
}
