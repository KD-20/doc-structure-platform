package com.docstructure.platform.rules;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the extraction strategy/normalizer implementations — no Spring context,
 * no database. These are the same classes exercised end-to-end against the docker-compose
 * stack in manual verification; this suite pins their behavior for regressions.
 */
class FieldExtractorsAndNormalizersTest {

    private static final String SAMPLE_TEXT = """
            ACME Supplies Inc.
            Invoice Number: INV-2024-0088
            Date: 03/14/2026
            Bill To: Contoso Ltd

            Line Items
            Widget A    2   10.00   20.00
            Widget B    1   50.00   50.00

            Total: $70.00
            """;

    @Test
    void anchorRegexExtractsValueAfterAnchor() {
        FieldRule rule = new FieldRule("invoice_number", "string", true, "ANCHOR_REGEX",
                Map.of("anchorText", "Invoice Number:", "searchWindowChars", 50, "pattern", "([A-Z]{2,4}-\\d{4}-\\d{4,8})"),
                null);
        FieldExtractionResult result = new AnchorRegexFieldExtractor().extract(SAMPLE_TEXT, rule);
        assertThat(result.isFound()).isTrue();
        assertThat(result.value()).isEqualTo("INV-2024-0088");
    }

    @Test
    void anchorRegexReturnsNotFoundWhenAnchorTextMissing() {
        FieldRule rule = new FieldRule("po_number", "string", false, "ANCHOR_REGEX",
                Map.of("anchorText", "Purchase Order:", "searchWindowChars", 50, "pattern", "(PO-\\d+)"), null);
        FieldExtractionResult result = new AnchorRegexFieldExtractor().extract(SAMPLE_TEXT, rule);
        assertThat(result.isFound()).isFalse();
        assertThat(result.confidence()).isZero();
    }

    @Test
    void anchorRegexOnlySearchesWithinTheConfiguredWindow() {
        // "Total:" appears ~140 chars into SAMPLE_TEXT; a tiny window after "Invoice Number:"
        // (which appears near the top) must NOT reach far enough to match the amount pattern.
        FieldRule rule = new FieldRule("bleed_through", "string", false, "ANCHOR_REGEX",
                Map.of("anchorText", "Invoice Number:", "searchWindowChars", 5, "pattern", "(\\$[\\d.]+)"), null);
        FieldExtractionResult result = new AnchorRegexFieldExtractor().extract(SAMPLE_TEXT, rule);
        assertThat(result.isFound()).isFalse();
    }

    @Test
    void regexGlobalFindsPatternAnywhereInDocument() {
        FieldRule rule = new FieldRule("total", "decimal", true, "REGEX_GLOBAL",
                Map.of("pattern", "\\$([\\d.]+)"), null);
        FieldExtractionResult result = new RegexGlobalFieldExtractor().extract(SAMPLE_TEXT, rule);
        assertThat(result.value()).isEqualTo("70.00");
    }

    @Test
    void tableUnderHeadingParsesRowsSplitOnMultiSpaceRuns() {
        FieldRule rule = new FieldRule("line_items", "table", false, "TABLE_UNDER_HEADING",
                Map.of("headingText", "Line Items", "columns", List.of("description", "qty", "unit_price", "amount")),
                null);
        FieldExtractionResult result = new TableUnderHeadingFieldExtractor().extract(SAMPLE_TEXT, rule);
        assertThat(result.isFound()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> rows = (List<Map<String, String>>) result.value();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("description", "Widget A").containsEntry("amount", "20.00");
        assertThat(rows.get(1)).containsEntry("description", "Widget B").containsEntry("amount", "50.00");
    }

    @Test
    void dateNormalizerConvertsToIso8601() {
        NormalizerSpec spec = new NormalizerSpec("DATE", Map.of("inputFormats", List.of("MM/dd/yyyy")));
        Object result = new DateNormalizer().normalize("03/14/2026", spec);
        assertThat(result).isEqualTo("2026-03-14");
    }

    @Test
    void dateNormalizerReturnsOriginalValueWhenNoFormatMatches() {
        NormalizerSpec spec = new NormalizerSpec("DATE", Map.of("inputFormats", List.of("yyyy-MM-dd")));
        Object result = new DateNormalizer().normalize("not-a-date", spec);
        assertThat(result).isEqualTo("not-a-date");
    }

    @Test
    void currencyNormalizerStripsSymbolsAndCommas() {
        Object result = new CurrencyNormalizer().normalize("$1,234.50", new NormalizerSpec("CURRENCY", Map.of()));
        assertThat(result).isEqualTo("1234.50");
    }

    @Test
    void defaultRuleInterpreterAppliesNormalizerOnlyWhenFieldWasFound() {
        FieldRule dateField = new FieldRule("invoice_date", "date", true, "ANCHOR_REGEX",
                Map.of("anchorText", "Date:", "searchWindowChars", 20, "pattern", "(\\d{1,2}/\\d{1,2}/\\d{4})"),
                new NormalizerSpec("DATE", Map.of("inputFormats", List.of("MM/dd/yyyy"))));
        FieldRule missingField = new FieldRule("po_number", "string", false, "ANCHOR_REGEX",
                Map.of("anchorText", "PO:", "searchWindowChars", 20, "pattern", "(PO-\\d+)"),
                new NormalizerSpec("DATE", Map.of("inputFormats", List.of("MM/dd/yyyy"))));

        DefaultRuleInterpreter interpreter = new DefaultRuleInterpreter(
                Map.of("ANCHOR_REGEX", new AnchorRegexFieldExtractor()),
                Map.of("DATE", new DateNormalizer(), "NOOP", new NoOpNormalizer()));

        List<InterpretedField> results = interpreter.interpret(SAMPLE_TEXT,
                new RuleSetDefinition("invoice", List.of(dateField, missingField)));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).value()).isEqualTo("2026-03-14");
        assertThat(results.get(1).isFound()).isFalse();
        assertThat(results.get(1).value()).isNull();
    }
}
