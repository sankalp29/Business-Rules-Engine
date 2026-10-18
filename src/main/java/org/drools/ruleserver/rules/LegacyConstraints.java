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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Field reads inside a constraint string from a rule stored before conditions were objects.
 * Authoring still rejects that shape. A quoted value is not a field.
 */
final class LegacyConstraints {

    record Read(String binding, String path) {
    }

    private static final Set<String> LITERALS = Set.of("true", "false", "null");

    private static final Pattern COMPARISON = Pattern.compile(
            "(?<![A-Za-z0-9_$.])([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\s*"
                    + "(==|!=|>=|<=|>|<|contains|matches)\\s*"
                    + "(\\$[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+"
                    + "|[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)?");

    private LegacyConstraints() {
    }

    static List<Read> reads(String constraint) {
        if (constraint == null || constraint.isBlank()) {
            return List.of();
        }
        Matcher matcher = COMPARISON.matcher(blankQuotes(constraint));
        List<Read> reads = new ArrayList<>();
        while (matcher.find()) {
            addLocal(reads, matcher.group(1));
            addRight(reads, matcher.group(3));
        }
        return reads;
    }

    private static void addLocal(List<Read> reads, String path) {
        if (ConditionPaths.local(path)) {
            reads.add(new Read(null, path));
        }
    }

    private static void addRight(List<Read> reads, String token) {
        if (token == null || LITERALS.contains(token)) {
            return;
        }
        if (ConditionPaths.bound(token)) {
            reads.add(new Read(ConditionPaths.binding(token), ConditionPaths.field(token)));
        } else if (ConditionPaths.local(token)) {
            reads.add(new Read(null, token));
        }
    }

    private static String blankQuotes(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current != '"' && current != '\'') {
                out.append(current);
                index++;
                continue;
            }
            char quote = current;
            out.append(' ');
            index++;
            while (index < text.length()) {
                char quoted = text.charAt(index);
                if (quoted == '\\' && index + 1 < text.length()) {
                    out.append(' ').append(' ');
                    index += 2;
                    continue;
                }
                out.append(' ');
                index++;
                if (quoted == quote) {
                    break;
                }
            }
        }
        return out.toString();
    }
}
