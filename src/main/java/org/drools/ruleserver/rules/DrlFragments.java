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

/**
 * Keeps a then-expression inside the Drools slot the generator wraps it in.
 * An expression is compiled as rule code.
 */
public final class DrlFragments {

    private DrlFragments() {
    }

    public static String expressionProblem(String value) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                if (depth < 0) {
                    return "closes the surrounding Drools block";
                }
                continue;
            }
            if (c == '{' || c == '}' || c == ';') {
                return "cannot contain '" + c + "'";
            }
            if (c == '\n' || c == '\r') {
                return "cannot contain a new line";
            }
            if (c == '/' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                if (next == '/' || next == '*') {
                    return "cannot contain comments";
                }
            }
        }
        if (inString) {
            return "has an unterminated string";
        }
        if (depth != 0) {
            return "has unbalanced parentheses";
        }
        return null;
    }
}
