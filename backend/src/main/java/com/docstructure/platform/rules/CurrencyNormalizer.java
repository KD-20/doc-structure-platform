package com.docstructure.platform.rules;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** params: stripSymbols (default true) — strips currency symbols/commas/whitespace and parses as a decimal, output as a plain numeric string. */
@Component("CURRENCY")
public class CurrencyNormalizer implements Normalizer {

    @Override
    public Object normalize(Object value, NormalizerSpec spec) {
        if (!(value instanceof String raw) || raw.isBlank()) {
            return value;
        }
        String cleaned = raw.replaceAll("[^0-9.-]", "");
        if (cleaned.isBlank()) {
            return value;
        }
        try {
            return new BigDecimal(cleaned).toPlainString();
        } catch (NumberFormatException e) {
            return value;
        }
    }
}
