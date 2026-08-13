package com.docstructure.platform.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Seeds a handful of built-in rule sets for common document types (invoice, receipt, resume,
 * contract, payslip) on every boot, so a tenant gets working structured extraction without
 * defining any rules themselves first — see RuleSetService#resolveDefinition for how a
 * tenant's own active rule set still overrides the matching default when one exists.
 *
 * Upsert-by-doc_type, not gated on an empty table like BootstrapDataInitializer: this lets a
 * new platform release add/refresh a built-in template on top of an existing database, without
 * a new migration or touching anything a tenant has customized (defaults and tenant rule sets
 * live in separate tables entirely).
 */
@Component
@Order(1)
public class DefaultRuleSetSeeder implements CommandLineRunner {

    private final DefaultRuleSetRepository repository;
    private final ObjectMapper objectMapper;

    public DefaultRuleSetSeeder(DefaultRuleSetRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        for (RuleSetDefinition template : templates()) {
            DefaultRuleSet entity = repository.findByDocType(template.docType()).orElseGet(DefaultRuleSet::new);
            entity.setDocType(template.docType());
            entity.setDefinition(objectMapper.valueToTree(template));
            repository.save(entity);
        }
    }

    private List<RuleSetDefinition> templates() {
        return List.of(invoice(), receipt(), resume(), contract(), payslip());
    }

    private RuleSetDefinition invoice() {
        return new RuleSetDefinition("invoice", List.of(
                anchorRegex("invoice_number", "string", true, "Invoice Number:", 50,
                        "([A-Z]{2,4}-\\d{4}-\\d{4,8})", null),
                anchorRegex("invoice_date", "date", true, "Date:", 50,
                        "(\\d{1,2}/\\d{1,2}/\\d{4})", dateNormalizer("MM/dd/yyyy")),
                anchorRegex("total_amount", "decimal", true, "Total:", 30,
                        "\\$?([\\d,]+\\.\\d{2})", currencyNormalizer()),
                tableUnderHeading("line_items", false, "Line Items",
                        List.of("description", "qty", "unit_price", "amount"))
        ));
    }

    private RuleSetDefinition receipt() {
        return new RuleSetDefinition("receipt", List.of(
                anchorRegex("receipt_number", "string", false, "Receipt Number:", 40,
                        "([A-Z0-9-]{4,20})", null),
                anchorRegex("transaction_date", "date", false, "Date:", 50,
                        "(\\d{1,2}/\\d{1,2}/\\d{4})", dateNormalizer("MM/dd/yyyy")),
                anchorRegex("total", "decimal", true, "Total:", 30,
                        "\\$?([\\d,]+\\.\\d{2})", currencyNormalizer())
        ));
    }

    // Real resumes aren't literal tables (no consistent column delimiters), so TABLE_UNDER_HEADING
    // never matches anything under either section in practice — replaced with ANCHOR_REGEX
    // capturing the whole free-text block following the heading (institution, location, company,
    // dates — not just one keyword), which is both far more likely to actually extract something
    // and far more useful to search/filter against afterward. Work history headings vary a lot
    // more across templates than education ones do ("Experience" vs "Work History" vs
    // "Employment History", observed across real uploaded resumes) — anchorRegexMulti tries each
    // and uses whichever appears first; "Education" alone already substring-matches variants like
    // "Education And Training" without needing a list.
    private RuleSetDefinition resume() {
        return new RuleSetDefinition("resume", List.of(
                regexGlobal("email", "string", false,
                        "([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})"),
                regexGlobal("phone", "string", false,
                        "(\\+?\\d{1,3}[-.\\s]?\\(?\\d{2,4}\\)?[-.\\s]?\\d{3,4}[-.\\s]?\\d{3,4})"),
                regexGlobal("linkedin", "string", false,
                        "(linkedin\\.com/in/[A-Za-z0-9-]+)"),
                anchorRegex("education", "string", false, "Education", 500, "\\s*([\\s\\S]+)", null),
                anchorRegexMulti("work_experience", "string", false,
                        List.of("Experience", "Work History", "Employment History"), 600, "\\s*([\\s\\S]+)", null)
        ));
    }

    private RuleSetDefinition contract() {
        return new RuleSetDefinition("contract", List.of(
                anchorRegex("effective_date", "date", false, "Effective Date:", 50,
                        "(\\d{1,2}/\\d{1,2}/\\d{4})", dateNormalizer("MM/dd/yyyy")),
                anchorRegex("term", "string", false, "Term:", 60,
                        "([^\\n]{1,60})", null)
        ));
    }

    private RuleSetDefinition payslip() {
        return new RuleSetDefinition("payslip", List.of(
                anchorRegex("employee_name", "string", false, "Employee Name:", 50,
                        "([^\\n]{1,60})", null),
                anchorRegex("pay_period", "string", false, "Pay Period:", 50,
                        "([^\\n]{1,40})", null),
                anchorRegex("gross_pay", "decimal", false, "Gross Pay:", 30,
                        "\\$?([\\d,]+\\.\\d{2})", currencyNormalizer()),
                anchorRegex("net_pay", "decimal", true, "Net Pay:", 30,
                        "\\$?([\\d,]+\\.\\d{2})", currencyNormalizer())
        ));
    }

    private FieldRule anchorRegex(String name, String type, boolean required, String anchorText,
                                   int searchWindowChars, String pattern, NormalizerSpec normalizer) {
        return new FieldRule(name, type, required, "ANCHOR_REGEX",
                Map.of("anchorText", anchorText, "searchWindowChars", searchWindowChars, "pattern", pattern),
                normalizer);
    }

    private FieldRule anchorRegexMulti(String name, String type, boolean required, List<String> anchorTexts,
                                        int searchWindowChars, String pattern, NormalizerSpec normalizer) {
        return new FieldRule(name, type, required, "ANCHOR_REGEX",
                Map.of("anchorTexts", anchorTexts, "searchWindowChars", searchWindowChars, "pattern", pattern),
                normalizer);
    }

    private FieldRule regexGlobal(String name, String type, boolean required, String pattern) {
        return new FieldRule(name, type, required, "REGEX_GLOBAL", Map.of("pattern", pattern), null);
    }

    private FieldRule tableUnderHeading(String name, boolean required, String headingText, List<String> columns) {
        return new FieldRule(name, "table", required, "TABLE_UNDER_HEADING",
                Map.of("headingText", headingText, "columns", columns), null);
    }

    private NormalizerSpec dateNormalizer(String... inputFormats) {
        return new NormalizerSpec("DATE", Map.of("inputFormats", List.of(inputFormats)));
    }

    private NormalizerSpec currencyNormalizer() {
        return new NormalizerSpec("CURRENCY", Map.of("stripSymbols", true));
    }
}
