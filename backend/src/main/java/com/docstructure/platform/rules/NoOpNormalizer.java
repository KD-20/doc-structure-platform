package com.docstructure.platform.rules;

import org.springframework.stereotype.Component;

@Component("NOOP")
public class NoOpNormalizer implements Normalizer {
    @Override
    public Object normalize(Object value, NormalizerSpec spec) {
        return value;
    }
}
