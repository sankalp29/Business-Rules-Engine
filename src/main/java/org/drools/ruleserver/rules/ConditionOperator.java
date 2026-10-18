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

public enum ConditionOperator {
    EQ("=="),
    NE("!="),
    GT(">"),
    GE(">="),
    LT("<"),
    LE("<="),
    CONTAINS("contains"),
    MATCHES("matches"),
    AND("and"),
    OR("or");

    public static final String ALLOWED = "==, !=, >, >=, <, <=, contains, matches, and, or";

    private final String token;

    ConditionOperator(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public boolean group() {
        return this == AND || this == OR;
    }

    public boolean ordered() {
        return this == GT || this == GE || this == LT || this == LE;
    }

    public boolean textual() {
        return this == CONTAINS || this == MATCHES;
    }

    public boolean allows(FieldKind kind) {
        if (kind == null || group()) {
            return false;
        }
        if (textual()) {
            return kind == FieldKind.STRING;
        }
        if (ordered()) {
            return numeric(kind);
        }
        return true;
    }

    public static String allowedFor(FieldKind kind) {
        if (kind == FieldKind.STRING) {
            return "==, !=, contains, matches";
        }
        if (kind == FieldKind.BOOLEAN || kind == FieldKind.BOOLEAN_OBJECT) {
            return "==, !=";
        }
        return "==, !=, >, >=, <, <=";
    }

    public static ConditionOperator fromToken(String token) {
        if (token == null) {
            return null;
        }
        for (ConditionOperator operator : values()) {
            if (operator.token.equals(token)) {
                return operator;
            }
        }
        return null;
    }

    public static boolean numeric(FieldKind kind) {
        return switch (kind) {
            case INT, INTEGER, LONG, LONG_OBJECT, DOUBLE, DOUBLE_OBJECT -> true;
            default -> false;
        };
    }

    public static boolean sameFamily(FieldKind left, FieldKind right) {
        if (left == right) {
            return true;
        }
        if (numeric(left) && numeric(right)) {
            return true;
        }
        boolean leftBoolean = left == FieldKind.BOOLEAN || left == FieldKind.BOOLEAN_OBJECT;
        boolean rightBoolean = right == FieldKind.BOOLEAN || right == FieldKind.BOOLEAN_OBJECT;
        return leftBoolean && rightBoolean;
    }
}
