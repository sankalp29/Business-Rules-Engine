/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.drools.ruleserver.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;

import org.drools.ruleserver.model.Branch;
import org.drools.ruleserver.model.BranchArm;
import org.drools.ruleserver.model.Condition;
import org.drools.ruleserver.model.FactResult;
import org.drools.ruleserver.model.FieldAssignment;
import org.drools.ruleserver.model.RuleDefinition;
import org.drools.ruleserver.model.ThenBlock;
import org.drools.ruleserver.model.ValueChange;
import org.drools.ruleserver.model.WhenBlock;
import org.springframework.stereotype.Component;

@Component
public class ReasoningBuilder {

    private static final Pattern LEGACY_COMPARISON = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\s*"
                    + "(==|!=|>=|<=|>|<|contains|matches)\\s*(.+)$");

    private static final Object UNPARSED = new Object();

    /**
     * Each value that changed is its own sentence. The sentence names that write and every
     * condition that evaluated true for it to run, starting at the top-level when and continuing
     * through the winning arm of the branch that performed the write. An else arm, and an
     * unconditional write, cite the top-level when only. A write that leaves the value as it was
     * is omitted. Conditions join with {@code &}.
     */
    public Reasoning describe(String ruleName,
                              boolean fired,
                              RuleDefinition definition,
                              List<FactSnapshot> before,
                              List<FactSnapshot> after) {
        List<FactResult> modified = diff(before, after);
        String plainName = plain(ruleName);
        if (!fired) {
            return new Reasoning(
                    List.of("Rule " + plainName + " did not fire. No fields were changed."),
                    modified);
        }
        List<Clause> clauses = trace(definition, before, after);
        if (clauses.isEmpty() && modified.stream().allMatch(fact -> fact.values().isEmpty())) {
            return new Reasoning(List.of("No field values changed."), modified);
        }
        if (clauses.isEmpty()) {
            clauses = traceFromDiff(modified);
        }
        return new Reasoning(sentences(before, clauses), modified);
    }

    private static List<String> sentences(List<FactSnapshot> before, List<Clause> clauses) {
        List<String> lines = new ArrayList<>();
        for (Clause clause : clauses) {
            StringBuilder text = new StringBuilder();
            text.append(plain(label(before, clause.fact))).append('.');
            text.append(plain(clause.field)).append('=').append(clause.value);
            if (clause.because != null && !clause.because.isEmpty()) {
                text.append(" because ").append(clause.because);
            }
            lines.add(text.toString());
        }
        return List.copyOf(lines);
    }

    private static List<Clause> trace(RuleDefinition definition, List<FactSnapshot> before, List<FactSnapshot> after) {
        if (definition == null || definition.then() == null || before == null) {
            return List.of();
        }
        Trace trace = new Trace(before, after);
        bind(trace, definition.when());
        List<Step> steps = new ArrayList<>();
        for (ThenBlock block : definition.then()) {
            if (block == null) {
                continue;
            }
            Integer fact = trace.bindings.get(block.binding());
            if (fact == null) {
                continue;
            }
            if (block.assignments() != null) {
                for (FieldAssignment assignment : block.assignments()) {
                    addStep(steps, trace, fact, assignment, null, fact);
                }
            }
            if (block.branches() == null) {
                continue;
            }
            for (Branch branch : block.branches()) {
                BranchArm arm = winner(trace, branch, fact);
                if (arm == null || arm.assignments() == null) {
                    continue;
                }
                Condition armCondition = arm == branch.otherwise() ? null : arm.when();
                for (FieldAssignment assignment : arm.assignments()) {
                    addStep(steps, trace, fact, assignment, armCondition, fact);
                }
            }
        }
        return clauses(trace, definition, steps);
    }

    private static void addStep(List<Step> steps,
                                Trace trace,
                                int fact,
                                FieldAssignment assignment,
                                Condition arm,
                                int armHome) {
        if (assignment == null || assignment.getField() == null || assignment.getField().isBlank()) {
            return;
        }
        boolean expression = assignment.getExpression() != null && !assignment.getExpression().isBlank();
        if (!expression && !assignment.hasValue()) {
            return;
        }
        String armText = arm == null ? null : renderTrue(trace, arm, armHome, fact);
        steps.add(new Step(
                fact,
                assignment.getField(),
                expression ? null : literal(assignment.getValue()),
                expression,
                armText));
    }

    private static List<Clause> clauses(Trace trace, RuleDefinition definition, List<Step> steps) {
        List<Map<String, Object>> shadow = new ArrayList<>();
        for (FactSnapshot fact : trace.before) {
            shadow.add(copyValues(fact.values()));
        }
        List<Clause> clauses = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            Object value = step.literal;
            if (step.expression) {
                if (writtenLater(steps, i)) {
                    continue;
                }
                value = readPath(trace.after.get(step.fact).values(), step.field).value;
            }
            Object previous = readPath(shadow.get(step.fact), step.field).value;
            if (!same(previous, value)) {
                clauses.add(new Clause(
                        step.fact,
                        step.field,
                        format(value),
                        because(trace, definition, step.fact, step.armText)));
            }
            writePath(shadow.get(step.fact), step.field, value);
        }
        return clauses;
    }

    private static boolean writtenLater(List<Step> steps, int index) {
        Step step = steps.get(index);
        for (int later = index + 1; later < steps.size(); later++) {
            Step next = steps.get(later);
            if (next.fact == step.fact && step.field.equals(next.field)) {
                return true;
            }
        }
        return false;
    }

    private static BranchArm winner(Trace trace, Branch branch, int fact) {
        if (branch == null || branch.ifArm() == null) {
            return null;
        }
        if (isTrue(trace, branch.ifArm().when(), fact)) {
            return branch.ifArm();
        }
        if (branch.elseIf() != null) {
            for (BranchArm arm : branch.elseIf()) {
                if (arm != null && isTrue(trace, arm.when(), fact)) {
                    return arm;
                }
            }
        }
        return branch.otherwise();
    }

    /**
     * The top-level when is the gate for every write. The arm text is the branch condition
     * that selected this write. Both are included when both evaluated true.
     */
    private static String because(Trace trace, RuleDefinition definition, int actionFact, String armText) {
        String whenText = gateText(trace, definition, actionFact);
        if (whenText == null || whenText.isEmpty()) {
            return armText;
        }
        if (armText == null || armText.isEmpty()) {
            return whenText;
        }
        return whenText + " & " + armText;
    }

    private static String gateText(Trace trace, RuleDefinition definition, int actionFact) {
        if (definition.when() == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (WhenBlock when : definition.when()) {
            if (when == null || when.conditions() == null) {
                continue;
            }
            Integer home = trace.bindings.get(when.binding());
            if (home == null) {
                continue;
            }
            String text = renderTrue(trace, when.conditions(), home, actionFact);
            if (text != null && !text.isEmpty()) {
                parts.add(text);
            }
        }
        return parts.isEmpty() ? null : String.join(" & ", parts);
    }

    private static void bind(Trace trace, List<WhenBlock> whens) {
        if (whens == null) {
            return;
        }
        boolean[] used = new boolean[trace.before.size()];
        for (WhenBlock when : whens) {
            if (when == null || when.binding() == null || when.type() == null) {
                continue;
            }
            int chosen = -1;
            for (int i = 0; i < trace.before.size(); i++) {
                if (used[i] || !when.type().equals(trace.before.get(i).type())) {
                    continue;
                }
                trace.bindings.put(when.binding(), i);
                if (isTrue(trace, when.conditions(), i)) {
                    chosen = i;
                    break;
                }
                trace.bindings.remove(when.binding());
            }
            if (chosen < 0) {
                for (int i = 0; i < trace.before.size(); i++) {
                    if (!used[i] && when.type().equals(trace.before.get(i).type())) {
                        chosen = i;
                        break;
                    }
                }
            }
            if (chosen >= 0) {
                used[chosen] = true;
                trace.bindings.put(when.binding(), chosen);
            }
        }
    }

    private static boolean isTrue(Trace trace, Condition condition, int home) {
        if (condition == null) {
            return true;
        }
        List<String> legacy = condition.getLegacyConstraints();
        if (legacy != null && !legacy.isEmpty()) {
            for (String constraint : legacy) {
                if (!legacyHolds(trace, constraint, home)) {
                    return false;
                }
            }
            return true;
        }
        if (condition.getShapeError() != null) {
            return false;
        }
        ConditionOperator operator = ConditionOperator.fromToken(condition.getOperator());
        if (operator == null) {
            return false;
        }
        if (operator == ConditionOperator.AND) {
            List<Condition> children = condition.getConditions();
            if (children == null || children.size() < 2) {
                return false;
            }
            for (Condition child : children) {
                if (!isTrue(trace, child, home)) {
                    return false;
                }
            }
            return true;
        }
        if (operator == ConditionOperator.OR) {
            List<Condition> children = condition.getConditions();
            if (children == null || children.isEmpty()) {
                return false;
            }
            for (Condition child : children) {
                if (isTrue(trace, child, home)) {
                    return true;
                }
            }
            return false;
        }
        return compare(trace, condition, operator, home);
    }

    /**
     * An {@code or} keeps only the first true child, because later children are not evaluated.
     * An {@code and} that matched was true on every child.
     */
    private static String renderTrue(Trace trace, Condition condition, int home, int actionFact) {
        if (condition == null || !isTrue(trace, condition, home)) {
            return null;
        }
        List<String> legacy = condition.getLegacyConstraints();
        if (legacy != null && !legacy.isEmpty()) {
            return renderLegacy(trace, legacy, home, actionFact);
        }
        ConditionOperator operator = ConditionOperator.fromToken(condition.getOperator());
        if (operator == ConditionOperator.AND) {
            List<String> parts = new ArrayList<>();
            for (Condition child : condition.getConditions()) {
                String part = renderTrue(trace, child, home, actionFact);
                if (part != null && !part.isEmpty()) {
                    parts.add(part);
                }
            }
            return parts.isEmpty() ? null : String.join(" & ", parts);
        }
        if (operator == ConditionOperator.OR) {
            for (Condition child : condition.getConditions()) {
                if (isTrue(trace, child, home)) {
                    return renderTrue(trace, child, home, actionFact);
                }
            }
            return null;
        }
        return renderComparison(trace, condition, home, actionFact);
    }

    private static String renderComparison(Trace trace, Condition condition, int home, int actionFact) {
        String left = fieldName(trace, condition.getField(), home, actionFact);
        String right = condition.getValueField() != null
                ? fieldName(trace, condition.getValueField(), home, actionFact)
                : format(literal(condition.getValue()));
        if ("==".equals(condition.getOperator())) {
            return left + "=" + right;
        }
        return left + " " + condition.getOperator() + " " + right;
    }

    private static String fieldName(Trace trace, String path, int home, int actionFact) {
        if (path == null) {
            return "field";
        }
        if (ConditionPaths.bound(path)) {
            Integer index = trace.bindings.get(ConditionPaths.binding(path));
            String rest = ConditionPaths.field(path);
            if (index == null || index == actionFact) {
                return plain(rest);
            }
            return plain(label(trace.before, index) + "." + rest);
        }
        if (home == actionFact) {
            return plain(path);
        }
        return plain(label(trace.before, home) + "." + path);
    }

    private static boolean compare(Trace trace, Condition condition, ConditionOperator operator, int home) {
        Read left = resolve(trace, condition.getField(), home);
        if (left.missingParent) {
            return false;
        }
        Read right;
        if (condition.getValueField() != null) {
            right = resolve(trace, condition.getValueField(), home);
            if (right.missingParent) {
                return false;
            }
        } else if (condition.hasValue()) {
            right = new Read(false, literal(condition.getValue()));
        } else {
            return false;
        }
        return compareResolved(operator, left.value, right.value);
    }

    private static boolean compareResolved(ConditionOperator operator, Object leftValue, Object rightValue) {
        if (operator == ConditionOperator.EQ) {
            return same(leftValue, rightValue);
        }
        if (operator == ConditionOperator.NE) {
            return !same(leftValue, rightValue);
        }
        if (operator == ConditionOperator.CONTAINS) {
            return leftValue instanceof String text && rightValue instanceof String needle && text.contains(needle);
        }
        if (operator == ConditionOperator.MATCHES) {
            if (!(leftValue instanceof String text) || !(rightValue instanceof String regex)) {
                return false;
            }
            try {
                return text.matches(regex);
            } catch (PatternSyntaxException ex) {
                return false;
            }
        }
        if (!(leftValue instanceof Number) || !(rightValue instanceof Number)) {
            return false;
        }
        int compared = new BigDecimal(leftValue.toString()).compareTo(new BigDecimal(rightValue.toString()));
        return switch (operator) {
            case GT -> compared > 0;
            case GE -> compared >= 0;
            case LT -> compared < 0;
            case LE -> compared <= 0;
            default -> false;
        };
    }

    /**
     * Rules stored before conditions were objects keep Drools text such as {@code age >= 65}.
     * Every constraint in that list has to hold, which is how the pattern matched.
     */
    private static boolean legacyHolds(Trace trace, String constraint, int home) {
        LegacyComparison parsed = parseLegacy(constraint);
        if (parsed == null) {
            return false;
        }
        Read left = resolve(trace, parsed.field, home);
        if (left.missingParent) {
            return false;
        }
        return compareResolved(parsed.operator, left.value, parsed.value);
    }

    private static String renderLegacy(Trace trace, List<String> constraints, int home, int actionFact) {
        List<String> parts = new ArrayList<>();
        for (String constraint : constraints) {
            LegacyComparison parsed = parseLegacy(constraint);
            if (parsed == null || !legacyHolds(trace, constraint, home)) {
                continue;
            }
            String left = fieldName(trace, parsed.field, home, actionFact);
            String right = format(parsed.value);
            if (parsed.operator == ConditionOperator.EQ) {
                parts.add(left + "=" + right);
            } else {
                parts.add(left + " " + parsed.operator.token() + " " + right);
            }
        }
        return parts.isEmpty() ? null : String.join(" & ", parts);
    }

    private static LegacyComparison parseLegacy(String constraint) {
        if (constraint == null) {
            return null;
        }
        Matcher matcher = LEGACY_COMPARISON.matcher(constraint.trim());
        if (!matcher.matches()) {
            return null;
        }
        ConditionOperator operator = ConditionOperator.fromToken(matcher.group(2));
        Object value = parseLegacyValue(matcher.group(3).trim());
        if (operator == null || value == UNPARSED) {
            return null;
        }
        return new LegacyComparison(matcher.group(1), operator, value);
    }

    private static Object parseLegacyValue(String text) {
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            return unescape(text.substring(1, text.length() - 1));
        }
        if ("true".equals(text)) {
            return Boolean.TRUE;
        }
        if ("false".equals(text)) {
            return Boolean.FALSE;
        }
        if ("null".equals(text)) {
            return null;
        }
        if (text.matches("-?\\d+")) {
            try {
                return Integer.valueOf(text);
            } catch (NumberFormatException ex) {
                try {
                    return Long.valueOf(text);
                } catch (NumberFormatException ignored) {
                    return UNPARSED;
                }
            }
        }
        if (text.matches("-?\\d+\\.\\d+")) {
            try {
                return Double.valueOf(text);
            } catch (NumberFormatException ex) {
                return UNPARSED;
            }
        }
        return UNPARSED;
    }

    private static String unescape(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == '"' || next == '\\') {
                    builder.append(next);
                    i++;
                    continue;
                }
            }
            builder.append(c);
        }
        return builder.toString();
    }

    private record LegacyComparison(String field, ConditionOperator operator, Object value) {
    }

    private static Read resolve(Trace trace, String path, int home) {
        if (path == null) {
            return new Read(true, null);
        }
        int index = home;
        String relative = path;
        if (ConditionPaths.bound(path)) {
            Integer bound = trace.bindings.get(ConditionPaths.binding(path));
            if (bound == null) {
                return new Read(true, null);
            }
            index = bound;
            relative = ConditionPaths.field(path);
        }
        if (index < 0 || index >= trace.before.size()) {
            return new Read(true, null);
        }
        return readPath(trace.before.get(index).values(), relative);
    }

    private static Read readPath(Map<String, Object> values, String path) {
        Object current = values;
        String[] parts = path.split("\\.", -1);
        for (int i = 0; i < parts.length; i++) {
            if (!(current instanceof Map<?, ?> map)) {
                return new Read(true, null);
            }
            if (!map.containsKey(parts[i])) {
                return new Read(i < parts.length - 1, null);
            }
            Object next = map.get(parts[i]);
            if (i == parts.length - 1) {
                return new Read(false, next);
            }
            if (next == null) {
                return new Read(true, null);
            }
            current = next;
        }
        return new Read(true, null);
    }

    private static void writePath(Map<String, Object> values, String path, Object value) {
        String[] parts = path.split("\\.", -1);
        Map<String, Object> current = values;
        for (int i = 0; i < parts.length - 1; i++) {
            Object child = current.get(parts[i]);
            if (!(child instanceof Map<?, ?>)) {
                Map<String, Object> created = new LinkedHashMap<>();
                current.put(parts[i], created);
                current = created;
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) child;
            current = nested;
        }
        current.put(parts[parts.length - 1], value);
    }

    private static Map<String, Object> copyValues(Map<String, Object> values) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (values == null) {
            return copy;
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> nested) {
                copy.put(entry.getKey(), copyValues(asMap(nested)));
            } else {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    private static Object literal(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.asText();
    }

    private static List<Clause> traceFromDiff(List<FactResult> modified) {
        List<Clause> clauses = new ArrayList<>();
        for (int i = 0; i < modified.size(); i++) {
            walkDiff(clauses, i, "", modified.get(i).values());
        }
        return clauses;
    }

    private static void walkDiff(List<Clause> clauses, int fact, String prefix, Map<String, Object> values) {
        if (values == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> nested) {
                walkDiff(clauses, fact, path, asMap(nested));
            } else if (entry.getValue() instanceof ValueChange change) {
                clauses.add(new Clause(fact, path, format(change.newValue()), null));
            }
        }
    }

    private static String label(List<FactSnapshot> facts, int index) {
        String type = facts.get(index).type();
        int count = 0;
        for (FactSnapshot fact : facts) {
            if (type.equals(fact.type())) {
                count++;
            }
        }
        if (count <= 1) {
            return type;
        }
        int number = 0;
        for (int i = 0; i <= index; i++) {
            if (type.equals(facts.get(i).type())) {
                number++;
            }
        }
        return type + "[" + number + "]";
    }

    private record Trace(List<FactSnapshot> before, List<FactSnapshot> after, Map<String, Integer> bindings) {
        private Trace(List<FactSnapshot> before, List<FactSnapshot> after) {
            this(before, after, new LinkedHashMap<>());
        }
    }

    private record Read(boolean missingParent, Object value) {
    }

    private record Step(int fact, String field, Object literal, boolean expression, String armText) {
    }

    private record Clause(int fact, String field, String value, String because) {
    }

    private static List<FactResult> diff(List<FactSnapshot> before, List<FactSnapshot> after) {
        List<FactResult> modified = new ArrayList<>();
        for (int i = 0; i < after.size(); i++) {
            FactSnapshot current = after.get(i);
            FactSnapshot previous = before.get(i);
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : current.values().entrySet()) {
                Object changed = diffValue(previous.values().get(entry.getKey()), entry.getValue());
                if (changed != null) {
                    values.put(entry.getKey(), changed);
                }
            }
            modified.add(new FactResult(current.type(), values));
        }
        return modified;
    }

    private static Object diffValue(Object previous, Object current) {
        if (previous instanceof Map<?, ?> || current instanceof Map<?, ?>) {
            Map<String, Object> left = asMap(previous);
            Map<String, Object> right = asMap(current);
            Set<String> keys = new LinkedHashSet<>();
            keys.addAll(left.keySet());
            keys.addAll(right.keySet());
            Map<String, Object> diff = new LinkedHashMap<>();
            for (String key : keys) {
                Object child = diffValue(left.get(key), right.get(key));
                if (child != null) {
                    diff.put(key, child);
                }
            }
            return diff.isEmpty() ? null : diff;
        }
        if (same(previous, current)) {
            return null;
        }
        return new ValueChange(previous, current);
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    static boolean same(Object left, Object right) {
        if (Objects.equals(left, right)) {
            return true;
        }
        if (left instanceof Number && right instanceof Number) {
            try {
                return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return false;
    }

    static String format(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Double || value instanceof Float) {
            return plain(Double.toString(((Number) value).doubleValue()));
        }
        return plain(String.valueOf(value));
    }

    /**
     * Reasoning is plain prose. Quotes, backslashes, slashes, and control characters are omitted
     * so the text itself never contains {@code \} or {@code /}, and JSON encoding does not have to escape it.
     */
    static String plain(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '/' || c == '"' || c < 0x20) {
                continue;
            }
            builder.append(c);
        }
        return builder.toString();
    }
}
