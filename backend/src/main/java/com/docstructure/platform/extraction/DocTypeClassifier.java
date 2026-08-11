package com.docstructure.platform.extraction;

import com.docstructure.platform.common.TenantScoped;
import com.docstructure.platform.rules.InterpretedField;
import com.docstructure.platform.rules.RuleInterpreter;
import com.docstructure.platform.rules.RuleSetDefinition;
import com.docstructure.platform.rules.RuleSetService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Picks a document's doc type automatically instead of asking the uploader to know/type one.
 * Tiered, and each tier is deliberately simple/deterministic rather than ML — consistent with
 * this system's rules-first design (see docs/DECISIONS.md §3):
 *
 * <ol>
 *   <li>{@link #classify}: reuses the exact same RuleInterpreter that runs real extraction —
 *       score the document's text against every candidate rule set (the tenant's own active
 *       ones, plus any platform-shipped default the tenant hasn't overridden — see
 *       RuleSetService#listCandidateDefinitionsForClassification), best match wins if
 *       confident. The only tier that also triggers auto-extraction (see
 *       DocumentService#upload), since it's the only one with positive evidence the fields
 *       will actually populate.</li>
 *   <li>{@link #classifyByFilename}: a known document-type word appears in the filename itself
 *       (e.g. "March_Invoice_2024.pdf" → "invoice").</li>
 *   <li>{@link #deriveFallbackDocType}: the file extension (the part after the last ".") mapped
 *       to a document kind ("pdf_document", "image", "spreadsheet", ...), or failing that the
 *       browser-supplied content-type as a second guess — always returns something. Deliberately
 *       NOT a slug of the whole filename: a filename is close to unique per document, so
 *       slugifying it produces a "type" shared by nothing else, which isn't a type at all — the
 *       point of a fallback tier is a genuine, reusable category, which the extension actually
 *       gives you. A document should never be dumped in one flat "unclassified" bucket just
 *       because it doesn't match an existing rule set yet.</li>
 * </ol>
 */
@Component
public class DocTypeClassifier {

    /** Below this fraction of fields matched, a "best" candidate still isn't trusted. */
    private static final double MIN_SCORE = 0.34;
    /** Extra credit for satisfying every required field — should beat a higher raw hit-count on the wrong type that's missing a required field. */
    private static final double ALL_REQUIRED_BONUS = 0.25;

    /** Common business-document words worth recognizing directly from a filename. */
    private static final List<String> KEYWORD_DOC_TYPES = List.of(
            "invoice", "receipt", "contract", "agreement", "statement", "report", "resume",
            "memo", "proposal", "quote", "quotation", "estimate", "certificate", "license",
            "policy", "manual", "form", "application", "payslip", "passport", "letter");

    private final RuleSetService ruleSetService;
    private final RuleInterpreter ruleInterpreter;

    public DocTypeClassifier(RuleSetService ruleSetService, RuleInterpreter ruleInterpreter) {
        this.ruleSetService = ruleSetService;
        this.ruleInterpreter = ruleInterpreter;
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public Optional<String> classify(UUID tenantId, String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }
        String bestDocType = null;
        double bestScore = 0;
        for (RuleSetDefinition definition : ruleSetService.listCandidateDefinitionsForClassification(tenantId)) {
            if (definition.fields().isEmpty()) {
                continue;
            }
            List<InterpretedField> interpreted = ruleInterpreter.interpret(rawText, definition);
            long found = interpreted.stream().filter(InterpretedField::isFound).count();
            double score = (double) found / interpreted.size();
            boolean allRequiredFound = interpreted.stream()
                    .filter(InterpretedField::required)
                    .allMatch(InterpretedField::isFound);
            if (allRequiredFound) {
                score += ALL_REQUIRED_BONUS;
            }
            if (score > bestScore) {
                bestScore = score;
                bestDocType = definition.docType();
            }
        }
        return bestScore >= MIN_SCORE ? Optional.ofNullable(bestDocType) : Optional.empty();
    }

    public Optional<String> classifyByFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return Optional.empty();
        }
        String normalized = stripExtension(filename).toLowerCase().replaceAll("[_\\-]+", " ");
        for (String keyword : KEYWORD_DOC_TYPES) {
            if (Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b").matcher(normalized).find()) {
                return Optional.of(keyword);
            }
        }
        return Optional.empty();
    }

    /** Never returns null/blank — the true last-resort fallback, replacing a flat "unclassified" label. */
    public String deriveFallbackDocType(String filename, String contentType) {
        String byExtension = classifyByExtension(filename);
        return byExtension != null ? byExtension : classifyByContentType(contentType);
    }

    /** The part of the filename after the last "." — a genuine, reusable category (every .pdf groups together), unlike a slug of the full filename. */
    private String classifyByExtension(String filename) {
        if (filename == null) {
            return null;
        }
        var matcher = Pattern.compile("\\.([a-zA-Z0-9]{1,6})$").matcher(filename);
        if (!matcher.find()) {
            return null;
        }
        return switch (matcher.group(1).toLowerCase()) {
            case "pdf" -> "pdf_document";
            case "doc", "docx", "rtf", "odt" -> "word_document";
            case "xls", "xlsx", "csv", "ods" -> "spreadsheet";
            case "ppt", "pptx", "odp" -> "presentation";
            case "jpg", "jpeg", "png", "gif", "bmp", "tiff", "tif", "webp", "heic" -> "image";
            case "txt", "md" -> "text_document";
            case "html", "htm" -> "web_document";
            default -> null;
        };
    }

    private String classifyByContentType(String contentType) {
        if (contentType == null) {
            return "document";
        }
        if (contentType.startsWith("image/")) {
            return "image";
        }
        if (contentType.equals("application/pdf")) {
            return "pdf_document";
        }
        if (contentType.contains("word")) {
            return "word_document";
        }
        if (contentType.contains("sheet") || contentType.contains("excel")) {
            return "spreadsheet";
        }
        if (contentType.contains("presentation") || contentType.contains("powerpoint")) {
            return "presentation";
        }
        if (contentType.startsWith("text/")) {
            return "text_document";
        }
        return "document";
    }

    private String stripExtension(String filename) {
        return filename.replaceAll("\\.[a-zA-Z0-9]{1,6}$", "");
    }
}
