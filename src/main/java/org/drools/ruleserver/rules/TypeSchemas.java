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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.drools.ruleserver.model.FactDeclaration;
import org.drools.ruleserver.model.FieldDeclaration;

public final class TypeSchemas {

    public static final int MAX_NESTING_DEPTH = 10;

    private TypeSchemas() {
    }

    public record FieldNode(String name, String declaredType, FieldKind scalar, Map<String, FieldNode> children) {

        public boolean nested() {
            return scalar == null;
        }
    }

    public record Depth(int value, String cycle) {
    }

    public static Map<String, FieldNode> fromFacts(List<FactDeclaration> facts) {
        Map<String, FactDeclaration> byType = new LinkedHashMap<>();
        if (facts != null) {
            for (FactDeclaration fact : facts) {
                if (fact != null && fact.type() != null) {
                    byType.put(fact.type(), fact);
                }
            }
        }
        Map<String, FieldNode> nodes = new LinkedHashMap<>();
        for (FactDeclaration fact : byType.values()) {
            nodes.put(fact.type(), typeNode(fact, byType, new ArrayDeque<>()));
        }
        return nodes;
    }

    public static FieldNode resolve(FieldNode node, String path) {
        if (node == null || path == null || path.isBlank()) {
            return null;
        }
        FieldNode current = node;
        for (String part : path.split("\\.", -1)) {
            if (part.isEmpty() || current.children() == null) {
                return null;
            }
            FieldNode next = current.children().get(part);
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    public static Depth measure(String typeName, List<FieldDeclaration> fields, Map<String, FactDeclaration> registry) {
        Deque<String> stack = new ArrayDeque<>();
        if (typeName != null) {
            stack.addLast(typeName);
        }
        return measureFields(fields, registry, stack);
    }

    private static FieldNode typeNode(FactDeclaration fact, Map<String, FactDeclaration> byType, Deque<String> stack) {
        if (stack.contains(fact.type())) {
            return new FieldNode(fact.type(), fact.type(), null, Map.of());
        }
        stack.addLast(fact.type());
        Map<String, FieldNode> children = new LinkedHashMap<>();
        if (fact.fields() != null) {
            for (FieldDeclaration field : fact.fields()) {
                if (field != null && field.name() != null) {
                    children.put(field.name(), fieldNode(field, byType, stack));
                }
            }
        }
        stack.removeLast();
        return new FieldNode(fact.type(), fact.type(), null, children);
    }

    private static FieldNode fieldNode(FieldDeclaration field, Map<String, FactDeclaration> byType, Deque<String> stack) {
        boolean inline = field.fields() != null && !field.fields().isEmpty();
        if (!inline) {
            FieldKind kind = FieldKind.fromName(field.type());
            if (kind != null) {
                return new FieldNode(field.name(), field.type(), kind, Map.of());
            }
            FactDeclaration nested = field.type() == null ? null : byType.get(field.type());
            if (nested == null) {
                return new FieldNode(field.name(), field.type(), null, Map.of());
            }
            FieldNode resolved = typeNode(nested, byType, stack);
            return new FieldNode(field.name(), field.type(), null, resolved.children());
        }
        Map<String, FieldNode> children = new LinkedHashMap<>();
        for (FieldDeclaration child : field.fields()) {
            if (child != null && child.name() != null) {
                children.put(child.name(), fieldNode(child, byType, stack));
            }
        }
        String declared = field.type() == null ? field.name() : field.type();
        return new FieldNode(field.name(), declared, null, children);
    }

    private static Depth measureFields(List<FieldDeclaration> fields, Map<String, FactDeclaration> registry, Deque<String> stack) {
        int max = 0;
        if (fields == null) {
            return new Depth(0, null);
        }
        for (FieldDeclaration field : fields) {
            if (field == null) {
                continue;
            }
            Depth child = measureField(field, registry, stack);
            if (child.cycle() != null) {
                return child;
            }
            max = Math.max(max, child.value());
        }
        return new Depth(max, null);
    }

    private static Depth measureField(FieldDeclaration field, Map<String, FactDeclaration> registry, Deque<String> stack) {
        if (field.fields() != null && !field.fields().isEmpty()) {
            Depth nested = measureFields(field.fields(), registry, stack);
            return new Depth(nested.value() + 1, nested.cycle());
        }
        if (field.type() != null && FieldKind.fromName(field.type()) != null) {
            return new Depth(1, null);
        }
        if (field.type() == null) {
            return new Depth(1, null);
        }
        if (stack.contains(field.type())) {
            return new Depth(MAX_NESTING_DEPTH + 1, field.type());
        }
        FactDeclaration referenced = registry.get(field.type());
        if (referenced == null) {
            return new Depth(1, null);
        }
        stack.addLast(field.type());
        Depth nested = measureFields(referenced.fields(), registry, stack);
        stack.removeLast();
        return new Depth(nested.value() + 1, nested.cycle());
    }
}
