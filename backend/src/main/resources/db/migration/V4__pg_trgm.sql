-- Typo-tolerant full-text search: plainto_tsquery relies on exact stemmed words, so a
-- misspelling ("markettting") never matches "marketing" no matter how close it is. pg_trgm's
-- word_similarity finds an approximately-matching word/phrase within a larger text, which is
-- exactly the "did they mean this" signal a search box needs. See SearchQueryBuilder/SearchService.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS ix_documents_raw_text_trgm ON documents USING gin (raw_text gin_trgm_ops);
