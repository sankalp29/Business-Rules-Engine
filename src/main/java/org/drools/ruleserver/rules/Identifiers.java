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

import java.util.Set;
import java.util.regex.Pattern;

public final class Identifiers {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern BINDING = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*");

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "var", "yield", "record", "sealed", "permits",
            "rule", "when", "then", "end", "declare", "query", "function", "global", "dialect", "salience",
            "eval", "not", "exists", "forall", "and", "or", "collect", "accumulate", "from", "over",
            "String", "Integer", "Long", "Double", "Boolean", "Object", "Number", "Date", "List", "Map", "Set");

    private Identifiers() {
    }

    public static boolean isIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches() && !KEYWORDS.contains(value);
    }

    public static boolean isBinding(String value) {
        if (value == null || !BINDING.matcher(value).matches()) {
            return false;
        }
        return !KEYWORDS.contains(value.substring(1));
    }
}
