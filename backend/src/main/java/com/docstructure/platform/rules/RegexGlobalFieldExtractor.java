package com.docstructure.platform.rules;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** params: pattern (required regex, first capture group used if present) — searches the whole document, no anchor. Lower confidence than ANCHOR_REGEX since there's no surrounding context to disambiguate a match. */
@Component("REGEX_GLOBAL")
public class RegexGlobalFieldExtractor implements FieldExtractor {

    @Override
    public FieldExtractionResult extract(String rawText, FieldRule rule) {
        if (rawText == null || rawText.isBlank()) {
            return FieldExtractionResult.notFound();
        }
        String patternStr = new RuleParams(rule.params()).requireString("pattern");
        Matcher matcher = Pattern.compile(patternStr).matcher(rawText);
        if (!matcher.find()) {
            return FieldExtractionResult.notFound();
        }
        String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group(0);
        return new FieldExtractionResult(value, 0.7);
    }
}
