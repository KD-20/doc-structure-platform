package com.docstructure.platform.rules;

/** Implementations are Spring beans named after normalizer.type (e.g. "DATE"), collected by RuleInterpreter via Map<String, Normalizer> autowiring. Must never throw: a normalizer that can't parse a value returns it unchanged rather than failing the whole extraction. */
public interface Normalizer {
    Object normalize(Object value, NormalizerSpec spec);
}
