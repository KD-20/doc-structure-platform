package com.docstructure.platform.search;

/**
 * plainto_tsquery ANDs every significant word together, so a natural-language phrase like "doc
 * with aiko in it" (-> 'doc' & 'aiko') fails to match a document that genuinely contains "aiko"
 * but not the literal word "doc" — full-text search here is meant for people typing sentences,
 * not boolean keyword syntax, and often a partial word ("cass" for "cassandra") rather than a
 * complete one. PREFIX_OR_MATCH rewrites plainto_tsquery's AND query into an OR of prefix
 * matches (any word starting with a significant term matches; ts_rank still favors documents
 * matching more of them), by round-tripping through plainto_tsquery for its stopword/stemming
 * handling, then swapping '&' for '|' and tagging each lexeme with the :* prefix marker.
 * Verified in psql before wiring in — see chat history for the manual test. Shared between the
 * WHERE predicate (SearchQueryBuilder) and the rank expression (SearchService) so both agree on
 * what "matches :q" means.
 */
final class TsQueryExpr {

    private TsQueryExpr() {
    }

    static final String PREFIX_OR_MATCH =
            "(regexp_replace(replace(plainto_tsquery('english', :q)::text, ' & ', ' | '), "
                    + "'''(\\w+)''', '''\\1'':*', 'g'))::tsquery";

    // pg_trgm word_similarity: is :q an approximate match for some extent of raw_text? Catches
    // typos ("markettting" ~ "marketing", "invocie" ~ "invoice") that stemming/prefix matching
    // can't — see V4__pg_trgm.sql. coalesce guards documents with no raw_text yet
    // (TEXT_EXTRACTION_FAILED) rather than nulling out.
    //
    // Applied per significant word, not to the whole :q string: "search with casssaa" scored
    // only 0.35 as a single phrase against a document that scores 0.625 for "casssaa" alone —
    // the unrelated words in a multi-word query dilute the one word that's actually a typo
    // (verified in psql).
    //
    // QUERY_TERMS splits the RAW :q string directly (not plainto_tsquery's stemmed output, which
    // PREFIX_OR_MATCH uses): trigram similarity is a character-level comparison, unrelated to
    // stemming, and stemming actively broke the length guard below — "invocie" (7 typed chars)
    // stems to "invoci" (6 chars), silently dropping under the threshold (verified in psql). The
    // split is intentionally crude (any non-alphanumeric run is a separator) since it only feeds
    // a length check and a similarity score, not exact matching.
    //
    // Guarded to length(term) >= MIN_TRIGRAM_LENGTH: a short probe string only has a couple of
    // trigrams total, so word_similarity gets noisy — "invo" (4 chars) crossed the 0.4 threshold
    // against 22 of 27 real documents purely by chance, and "search" (6 chars) fuzzy-matched
    // "research" in an unrelated résumé at 0.71 — trigram similarity can't tell "this is a typo
    // of that word" apart from "this word is genuinely almost a substring of that other word,"
    // and 6-letter common words hit that ambiguity often enough to be noisy (both verified in
    // psql). 7+ character terms are rare enough as accidental substrings that real typos
    // ("invocie", "markettting", "casssaa" — all 7+) stay reliable. Below this length,
    // PREFIX_OR_MATCH already finds partial words exactly and unambiguously, so fuzzy matching
    // isn't needed there anyway.
    static final double TRIGRAM_THRESHOLD = 0.4;
    static final int MIN_TRIGRAM_LENGTH = 7;

    private static final String QUERY_TERMS = "regexp_split_to_array(lower(:q), '[^a-z0-9]+')";

    static final String TRIGRAM_MATCH =
            "EXISTS (SELECT 1 FROM unnest(" + QUERY_TERMS + ") AS term "
                    + "WHERE length(term) >= " + MIN_TRIGRAM_LENGTH + " AND term <% coalesce(d.raw_text, ''))";

    // 3rd matching method: does a term appear anywhere INSIDE a word, not just at its start?
    // PREFIX_OR_MATCH only matches word-start ("invo" -> "invoice"); it never matches "voice"
    // against "invoice" since "voice" isn't a prefix of anything in that word — verified in
    // psql, that query returned 0 results despite the literal substring genuinely being present.
    // Exact substring, not fuzzy, so unlike TRIGRAM_MATCH there's no noisy-short-string problem
    // and no minimum length beyond filtering out empty splits; the trgm GIN index from
    // V4__pg_trgm.sql accelerates leading-wildcard ILIKE too, not just similarity operators.
    static final String SUBSTRING_MATCH =
            "EXISTS (SELECT 1 FROM unnest(" + QUERY_TERMS + ") AS term "
                    + "WHERE length(term) >= 3 AND coalesce(d.raw_text, '') ILIKE '%' || term || '%')";

    static final String MATCHES_QUERY =
            "(d.raw_text_tsv @@ " + PREFIX_OR_MATCH + " OR " + TRIGRAM_MATCH + " OR " + SUBSTRING_MATCH + ")";

    static final String RANK = "GREATEST(ts_rank(d.raw_text_tsv, " + PREFIX_OR_MATCH + "), "
            + "COALESCE((SELECT MAX(word_similarity(term, coalesce(d.raw_text, ''))) FROM unnest("
            + QUERY_TERMS + ") AS term WHERE length(term) >= " + MIN_TRIGRAM_LENGTH + "), 0), "
            + "(CASE WHEN " + SUBSTRING_MATCH + " THEN 0.3 ELSE 0 END))";
}
