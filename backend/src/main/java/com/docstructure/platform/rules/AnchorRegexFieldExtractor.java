package com.docstructure.platform.rules;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * params: anchorText (single string) or anchorTexts (list — alternative section-heading
 * wordings, e.g. a resume's work-history section might be labeled "Experience", "Work History",
 * or "Employment History" depending on the template; whichever one actually appears earliest in
 * the document wins, so this doesn't require every document to use the same wording). One of the
 * two is required. pattern (required regex, first capture group used if present),
 * searchWindowChars (default 200) — finds the anchor, then searches only the following window
 * for pattern. v1 only supports "nearest following" anchoring; a future anchorMode value (e.g.
 * PRECEDING) can be added as a pure config/interpreter change.
 */
@Component("ANCHOR_REGEX")
public class AnchorRegexFieldExtractor implements FieldExtractor {

    @Override
    public FieldExtractionResult extract(String rawText, FieldRule rule) {
        if (rawText == null || rawText.isBlank()) {
            return FieldExtractionResult.notFound();
        }
        RuleParams params = new RuleParams(rule.params());
        List<String> anchorCandidates = params.getStringList("anchorTexts", null);
        if (anchorCandidates == null) {
            anchorCandidates = List.of(params.requireString("anchorText"));
        }
        String patternStr = params.requireString("pattern");
        int windowChars = params.getInt("searchWindowChars", 200);

        String lowerText = rawText.toLowerCase();
        int anchorIdx = -1;
        int anchorLen = 0;
        for (String candidate : anchorCandidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            int idx = lowerText.indexOf(candidate.toLowerCase());
            if (idx >= 0 && (anchorIdx < 0 || idx < anchorIdx)) {
                anchorIdx = idx;
                anchorLen = candidate.length();
            }
        }
        if (anchorIdx < 0) {
            return FieldExtractionResult.notFound();
        }
        int windowStart = anchorIdx + anchorLen;
        int windowEnd = Math.min(rawText.length(), windowStart + windowChars);
        String window = rawText.substring(windowStart, windowEnd);

        Matcher matcher = Pattern.compile(patternStr).matcher(window);
        if (!matcher.find()) {
            return FieldExtractionResult.notFound();
        }
        String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group(0);
        return new FieldExtractionResult(value, 0.9);
    }
}
