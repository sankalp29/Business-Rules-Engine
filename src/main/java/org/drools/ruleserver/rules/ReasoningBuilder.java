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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.drools.ruleserver.model.FactResult;
import org.drools.ruleserver.model.ValueChange;
import org.springframework.stereotype.Component;

@Component
public class ReasoningBuilder {

    public Reasoning describe(String ruleName, boolean fired, List<FactSnapshot> before, List<FactSnapshot> after) {
        List<FactResult> modified = diff(before, after);
        String plainName = plain(ruleName);
        if (!fired) {
            return new Reasoning(
                    "Rule " + plainName + " did not fire. No fields were changed.",
                    modified);
        }
        StringBuilder text = new StringBuilder();
        text.append("Rule ").append(plainName).append(" fired.");
        if (modified.stream().allMatch(fact -> fact.values().isEmpty())) {
            text.append(" No field values changed.");
        } else {
            Map<String, Integer> counts = new HashMap<>();
            for (FactResult fact : modified) {
                counts.merge(fact.type(), 1, Integer::sum);
            }
            Map<String, Integer> seen = new HashMap<>();
            for (FactResult fact : modified) {
                int index = seen.merge(fact.type(), 1, Integer::sum);
                if (fact.values().isEmpty()) {
                    continue;
                }
                String label = counts.get(fact.type()) > 1 ? fact.type() + "[" + index + "]" : fact.type();
                appendChanges(text, label, fact.values());
            }
        }
        return new Reasoning(text.toString(), modified);
    }

    private static void appendChanges(StringBuilder text, String label, Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> nested) {
                appendChanges(text, label + "." + entry.getKey(), asMap(nested));
            } else if (entry.getValue() instanceof ValueChange change) {
                text.append(" Set ")
                        .append(plain(label))
                        .append('.')
                        .append(plain(entry.getKey()))
                        .append(" to ")
                        .append(format(change.newValue()))
                        .append(" (was ")
                        .append(format(change.previousValue()))
                        .append(").");
            }
        }
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
