package com.docstructure.platform.rules;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * params: headingText (required), columns (required list of column names) — finds
 * headingText, then reads subsequent non-blank lines as table rows, splitting each line on
 * runs of 2+ whitespace characters (the common "aligned columns via padding" layout Tika's
 * plain-text extraction produces for simple tables). Best-effort: irregular whitespace or
 * wrapped cells will misparse — this is the known limitation table extraction has in v1.
 */
@Component("TABLE_UNDER_HEADING")
public class TableUnderHeadingFieldExtractor implements FieldExtractor {

    @Override
    public FieldExtractionResult extract(String rawText, FieldRule rule) {
        if (rawText == null || rawText.isBlank()) {
            return FieldExtractionResult.notFound();
        }
        RuleParams params = new RuleParams(rule.params());
        String headingText = params.requireString("headingText");
        List<String> columns = params.requireStringList("columns");

        int headingIdx = rawText.toLowerCase().indexOf(headingText.toLowerCase());
        if (headingIdx < 0) {
            return FieldExtractionResult.notFound();
        }
        String afterHeading = rawText.substring(headingIdx + headingText.length());

        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : afterHeading.split("\\r?\\n")) {
            if (line.isBlank()) {
                if (!rows.isEmpty()) {
                    break;
                }
                continue;
            }
            String[] cells = line.trim().split("\\s{2,}");
            if (cells.length < 2) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size() && i < cells.length; i++) {
                row.put(columns.get(i), cells[i].trim());
            }
            rows.add(row);
        }

        if (rows.isEmpty()) {
            return FieldExtractionResult.notFound();
        }
        return new FieldExtractionResult(rows, 0.6);
    }
}
