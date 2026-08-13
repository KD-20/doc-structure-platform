package com.docstructure.platform.search;

/**
 * One structured filter from the ?filters=[...] JSON query param, e.g.
 * {"field":"invoice_number","op":"eq","value":"INV-123"}. op: "eq" (default, exact match on the
 * whole field value), "contains" (exact substring, case-insensitive), "fuzzy" (trigram
 * word-similarity — tolerates typos/case AND the field value being a longer blob than just the
 * searched term, e.g. one word within a multi-line address field; see SearchQueryBuilder), or
 * "gt"/"gte"/"lt"/"lte" (numeric range — value must parse as a number). field can also be
 * SearchQueryBuilder.ANY_FIELD ("*") to match against whichever extracted field satisfies it,
 * without the caller needing to know which one.
 */
public record SearchFilter(String field, String op, String value) {
}
