package com.docstructure.platform.search;

/**
 * One structured filter from the ?filters=[...] JSON query param, e.g.
 * {"field":"invoice_number","op":"eq","value":"INV-123"}. op: "eq" (default), "contains"
 * (substring, case-insensitive), or "gt"/"gte"/"lt"/"lte" (numeric range — value must parse as a
 * number; see SearchQueryBuilder).
 */
public record SearchFilter(String field, String op, String value) {
}
