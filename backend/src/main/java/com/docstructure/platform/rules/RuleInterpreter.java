package com.docstructure.platform.rules;

import java.util.List;

public interface RuleInterpreter {
    List<InterpretedField> interpret(String rawText, RuleSetDefinition definition);
}
