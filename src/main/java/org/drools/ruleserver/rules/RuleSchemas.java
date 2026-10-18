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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.drools.ruleserver.model.Condition;
import org.drools.ruleserver.model.FactDeclaration;
import org.drools.ruleserver.model.FieldAssignment;
import org.drools.ruleserver.model.FieldDeclaration;
import org.drools.ruleserver.model.RuleDefinition;
import org.drools.ruleserver.model.ThenBlock;
import org.drools.ruleserver.model.WhenBlock;
import org.drools.ruleserver.rules.TypeSchemas.FieldNode;

/**
 * Schemas stored on a rule. A field a condition reads, including a {@code valueField}, is part of
 * both schemas. A field a {@code then} assignment sets is part of {@code outputSchema} only.
 * A constraint string from a rule stored before conditions were objects is read the same way.
 * Facts follow the first {@code when} block that uses the type. Fields follow the type declaration.
 */
public final class RuleSchemas {

    private RuleSchemas() {
    }

    public record Schemas(List<FactDeclaration> input, List<FactDeclaration> output) {
    }

    public static Schemas build(RuleDefinition definition, Map<String, FactDeclaration> catalog) {
        Map<String, FieldNode> nodes = TypeSchemas.fromFacts(catalog == null ? List.of() : new ArrayList<>(catalog.values()));
        Map<String, String> bindings = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> inputPaths = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> outputPaths = new LinkedHashMap<>();
        if (definition != null && definition.when() != null) {
            for (WhenBlock when : definition.when()) {
                if (when == null || when.type() == null) {
                    continue;
                }
                if (when.binding() != null) {
                    bindings.putIfAbsent(when.binding(), when.type());
                }
                inputPaths.computeIfAbsent(when.type(), key -> new LinkedHashSet<>());
                outputPaths.computeIfAbsent(when.type(), key -> new LinkedHashSet<>());
                collectCondition(when.conditions(), when.type(), bindings, inputPaths, outputPaths);
            }
        }
        if (definition != null && definition.then() != null) {
            for (ThenBlock block : definition.then()) {
                if (block == null || block.assignments() == null) {
                    continue;
                }
                String type = bindings.get(block.binding());
                if (type == null) {
                    continue;
                }
                LinkedHashSet<String> paths = outputPaths.computeIfAbsent(type, key -> new LinkedHashSet<>());
                for (FieldAssignment assignment : block.assignments()) {
                    if (assignment != null && assignment.getField() != null && !assignment.getField().isBlank()) {
                        paths.add(assignment.getField());
                    }
                }
            }
        }
        return new Schemas(project(inputPaths, nodes), project(outputPaths, nodes));
    }

    private static void collectCondition(Condition condition,
                                         String selfType,
                                         Map<String, String> bindings,
                                         Map<String, LinkedHashSet<String>> inputPaths,
                                         Map<String, LinkedHashSet<String>> outputPaths) {
        if (condition == null) {
            return;
        }
        if (condition.getShapeError() != null) {
            collectLegacy(condition.getLegacyConstraints(), selfType, bindings, inputPaths, outputPaths);
            return;
        }
        ConditionOperator operator = ConditionOperator.fromToken(condition.getOperator());
        if (operator == null) {
            return;
        }
        if (operator.group()) {
            if (condition.getConditions() == null) {
                return;
            }
            for (Condition child : condition.getConditions()) {
                collectCondition(child, selfType, bindings, inputPaths, outputPaths);
            }
            return;
        }
        addRead(selfType, condition.getField(), inputPaths, outputPaths);
        String valueField = condition.getValueField();
        if (ConditionPaths.local(valueField)) {
            addRead(selfType, valueField, inputPaths, outputPaths);
        } else if (ConditionPaths.bound(valueField)) {
            addRead(bindings.get(ConditionPaths.binding(valueField)), ConditionPaths.field(valueField), inputPaths, outputPaths);
        }
    }

    private static void collectLegacy(List<String> constraints,
                                      String selfType,
                                      Map<String, String> bindings,
                                      Map<String, LinkedHashSet<String>> inputPaths,
                                      Map<String, LinkedHashSet<String>> outputPaths) {
        if (constraints == null) {
            return;
        }
        for (String constraint : constraints) {
            for (LegacyConstraints.Read read : LegacyConstraints.reads(constraint)) {
                String type = read.binding() == null ? selfType : bindings.get(read.binding());
                addRead(type, read.path(), inputPaths, outputPaths);
            }
        }
    }

    private static void addRead(String type,
                                String path,
                                Map<String, LinkedHashSet<String>> inputPaths,
                                Map<String, LinkedHashSet<String>> outputPaths) {
        if (type == null || path == null || path.isBlank()) {
            return;
        }
        inputPaths.computeIfAbsent(type, key -> new LinkedHashSet<>()).add(path);
        outputPaths.computeIfAbsent(type, key -> new LinkedHashSet<>()).add(path);
    }

    private static List<FactDeclaration> project(Map<String, LinkedHashSet<String>> pathsByType, Map<String, FieldNode> nodes) {
        List<FactDeclaration> facts = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : pathsByType.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            FieldNode node = nodes.get(entry.getKey());
            if (node == null) {
                continue;
            }
            List<FieldDeclaration> fields = projectFields(node, entry.getValue());
            if (!fields.isEmpty()) {
                facts.add(new FactDeclaration(entry.getKey(), fields));
            }
        }
        return facts;
    }

    private static List<FieldDeclaration> projectFields(FieldNode node, LinkedHashSet<String> paths) {
        Map<String, LinkedHashSet<String>> tails = new LinkedHashMap<>();
        LinkedHashSet<String> scalars = new LinkedHashSet<>();
        for (String path : paths) {
            int dot = path.indexOf('.');
            if (dot < 0) {
                scalars.add(path);
            } else {
                tails.computeIfAbsent(path.substring(0, dot), key -> new LinkedHashSet<>()).add(path.substring(dot + 1));
            }
        }
        List<FieldDeclaration> fields = new ArrayList<>();
        if (node.children() == null) {
            return fields;
        }
        for (FieldNode child : node.children().values()) {
            if (child.scalar() != null && scalars.contains(child.name())) {
                fields.add(new FieldDeclaration(child.name(), child.declaredType()));
            } else if (child.nested()) {
                LinkedHashSet<String> nestedPaths = tails.get(child.name());
                if (nestedPaths == null || nestedPaths.isEmpty()) {
                    continue;
                }
                List<FieldDeclaration> nested = projectFields(child, nestedPaths);
                if (!nested.isEmpty()) {
                    fields.add(new FieldDeclaration(child.name(), child.declaredType(), nested));
                }
            }
        }
        return fields;
    }
}
