package com.docstructure.platform.rules;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/** params: inputFormats (list of java.time patterns to try in order). Output is always ISO-8601 (yyyy-MM-dd). */
@Component("DATE")
public class DateNormalizer implements Normalizer {

    @Override
    public Object normalize(Object value, NormalizerSpec spec) {
        if (!(value instanceof String raw) || raw.isBlank()) {
            return value;
        }
        List<?> inputFormats = spec != null && spec.params() != null
                ? (List<?>) spec.params().getOrDefault("inputFormats", List.of())
                : List.of();
        for (Object fmt : inputFormats) {
            try {
                LocalDate date = LocalDate.parse(raw.trim(), DateTimeFormatter.ofPattern(fmt.toString()));
                return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException | IllegalArgumentException ignored) {
                // try the next format
            }
        }
        return value;
    }
}
