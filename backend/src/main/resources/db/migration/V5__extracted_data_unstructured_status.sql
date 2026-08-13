-- Adds UNSTRUCTURED to extracted_data.status's allowed values — see ExtractedDataStatus
-- and RuleBasedExtractionStrategy: a document with no matching rule set (custom or platform
-- default) now still gets an extracted_data row and an embedding, just with zero fields, so it
-- stays findable via semantic/fuzzy search instead of never being processed at all.
ALTER TABLE extracted_data DROP CONSTRAINT extracted_data_status_check;
ALTER TABLE extracted_data ADD CONSTRAINT extracted_data_status_check
    CHECK (status IN ('COMPLETE', 'PARTIAL', 'NEEDS_REVIEW', 'UNSTRUCTURED'));
