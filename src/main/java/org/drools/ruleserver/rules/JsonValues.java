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

import com.fasterxml.jackson.databind.JsonNode;

public final class JsonValues {

    private JsonValues() {
    }

    public static String problem(FieldKind kind, JsonNode node) {
        try {
            toJava(kind, node);
            return null;
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        }
    }

    public static Object toJava(FieldKind kind, JsonNode node) {
        if (node == null || node.isNull()) {
            if (kind.primitive()) {
                throw new IllegalArgumentException("cannot be null");
            }
            return null;
        }
        return switch (kind) {
            case STRING -> text(node);
            case BOOLEAN, BOOLEAN_OBJECT -> bool(node);
            case INT, INTEGER -> toLong(node, Integer.MIN_VALUE, Integer.MAX_VALUE, "int").intValue();
            case LONG, LONG_OBJECT -> toLong(node, Long.MIN_VALUE, Long.MAX_VALUE, "long");
            case DOUBLE, DOUBLE_OBJECT -> toDouble(node);
        };
    }

    public static String toDrlLiteral(FieldKind kind, JsonNode node) {
        Object value = toJava(kind, node);
        if (value == null) {
            return "null";
        }
        return switch (kind) {
            case STRING -> quote((String) value);
            case BOOLEAN, BOOLEAN_OBJECT, INT, INTEGER -> value.toString();
            case LONG, LONG_OBJECT -> value.toString() + "L";
            case DOUBLE, DOUBLE_OBJECT -> decimalLiteral(node);
        };
    }

    public static String quote(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
        return builder.toString();
    }

    private static String text(JsonNode node) {
        if (!node.isTextual()) {
            throw new IllegalArgumentException("must be a JSON string");
        }
        return node.asText();
    }

    private static Boolean bool(JsonNode node) {
        if (!node.isBoolean()) {
            throw new IllegalArgumentException("must be a JSON boolean");
        }
        return node.booleanValue();
    }

    private static Long toLong(JsonNode node, long min, long max, String label) {
        BigDecimal decimal = decimal(node);
        if (decimal.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalArgumentException("must be an integer");
        }
        final long value;
        try {
            value = decimal.longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("must be a " + label + " between " + min + " and " + max);
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("must be a " + label + " between " + min + " and " + max);
        }
        return value;
    }

    private static Double toDouble(JsonNode node) {
        double value = decimal(node).doubleValue();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("must be a finite number");
        }
        return value;
    }

    private static BigDecimal decimal(JsonNode node) {
        if (!node.isNumber()) {
            throw new IllegalArgumentException("must be a JSON number");
        }
        return node.decimalValue();
    }

    private static String decimalLiteral(JsonNode node) {
        String plain = node.decimalValue().stripTrailingZeros().toPlainString();
        if (plain.indexOf('.') < 0) {
            plain = plain + ".0";
        }
        return plain;
    }
}
