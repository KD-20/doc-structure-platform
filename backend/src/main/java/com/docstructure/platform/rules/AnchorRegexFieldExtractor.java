package com.docstructure.platform.rules;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * params: anchorText (required), pattern (required regex, first capture group used if
 * present), searchWindowChars (default 200) — finds anchorText, then searches only the
 * following window for pattern. v1 only supports "nearest following" anchoring; a future
 * anchorMode value (e.g. PRECEDING) can be added as a pure config/interpreter change.
 */
@Component("ANCHOR_REGEX")
public class AnchorRegexFieldExtractor implements FieldExtractor {

    @Override
    public FieldExtractionResult extract(String rawText, FieldRule rule) {
        if (rawText == null || rawText.isBlank()) {
            return FieldExtractionResult.notFound();
        }
        RuleParams params = new RuleParams(rule.params());
        String anchorText = params.requireString("anchorText");
        String patternStr = params.requireString("pattern");
        int windowChars = params.getInt("searchWindowChars", 200);

        int anchorIdx = rawText.toLowerCase().indexOf(anchorText.toLowerCase());
        if (anchorIdx < 0) {
            return FieldExtractionResult.notFound();
        }
        int windowStart = anchorIdx + anchorText.length();
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
