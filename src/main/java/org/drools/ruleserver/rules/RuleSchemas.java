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

import org.drools.ruleserver.model.Branch;
import org.drools.ruleserver.model.BranchArm;
import org.drools.ruleserver.model.Condition;
import org.drools.ruleserver.model.FactDeclaration;
import org.drools.ruleserver.model.FieldAssignment;
import org.drools.ruleserver.model.FieldDeclaration;
import org.drools.ruleserver.model.RuleDefinition;
import org.drools.ruleserver.model.ThenBlock;
import org.drools.ruleserver.model.WhenBlock;
import org.drools.ruleserver.rules.TypeSchemas.FieldNode;

/**
 * Schemas stored on a rule. A field a condition reads, including a {@code valueField} and a field
 * read by an if or else-if arm, is part of both schemas. A field an assignment sets, including an
 * arm of a branch, is part of {@code outputSchema} only.
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
                if (block == null) {
                    continue;
                }
                String type = bindings.get(block.binding());
                if (type == null) {
                    continue;
                }
                addAssignments(type, block.assignments(), outputPaths);
                collectBranches(block.branches(), type, bindings, inputPaths, outputPaths);
            }
        }
        return new Schemas(project(inputPaths, nodes), project(outputPaths, nodes));
    }

    private static void collectBranches(List<Branch> branches,
                                        String selfType,
                                        Map<String, String> bindings,
                                        Map<String, LinkedHashSet<String>> inputPaths,
                                        Map<String, LinkedHashSet<String>> outputPaths) {
        if (branches == null) {
            return;
        }
        for (Branch branch : branches) {
            if (branch == null) {
                continue;
            }
            collectArm(branch.ifArm(), selfType, bindings, inputPaths, outputPaths);
            if (branch.elseIf() != null) {
                for (BranchArm arm : branch.elseIf()) {
                    collectArm(arm, selfType, bindings, inputPaths, outputPaths);
                }
            }
            collectArm(branch.otherwise(), selfType, bindings, inputPaths, outputPaths);
        }
    }

    private static void collectArm(BranchArm arm,
                                   String selfType,
                                   Map<String, String> bindings,
                                   Map<String, LinkedHashSet<String>> inputPaths,
                                   Map<String, LinkedHashSet<String>> outputPaths) {
        if (arm == null) {
            return;
        }
        collectBranchCondition(arm.when(), selfType, bindings, inputPaths, outputPaths);
        addAssignments(selfType, arm.assignments(), outputPaths);
    }

    private static void addAssignments(String type,
                                       List<FieldAssignment> assignments,
                                       Map<String, LinkedHashSet<String>> outputPaths) {
        if (type == null || assignments == null) {
            return;
        }
        LinkedHashSet<String> paths = outputPaths.computeIfAbsent(type, key -> new LinkedHashSet<>());
        for (FieldAssignment assignment : assignments) {
            if (assignment != null && assignment.getField() != null && !assignment.getField().isBlank()) {
                paths.add(assignment.getField());
            }
        }
    }

    private static void collectBranchCondition(Condition condition,
                                               String selfType,
                                               Map<String, String> bindings,
                                               Map<String, LinkedHashSet<String>> inputPaths,
                                               Map<String, LinkedHashSet<String>> outputPaths) {
        if (condition == null || condition.getShapeError() != null) {
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
                collectBranchCondition(child, selfType, bindings, inputPaths, outputPaths);
            }
            return;
        }
        String leftType = ownerType(condition.getField(), selfType, bindings);
        addRead(leftType, relativePath(condition.getField()), inputPaths, outputPaths);
        String valueField = condition.getValueField();
        if (ConditionPaths.local(valueField)) {
            addRead(leftType, valueField, inputPaths, outputPaths);
        } else if (ConditionPaths.bound(valueField)) {
            addRead(bindings.get(ConditionPaths.binding(valueField)), ConditionPaths.field(valueField), inputPaths, outputPaths);
        }
    }

    private static String ownerType(String path, String selfType, Map<String, String> bindings) {
        if (ConditionPaths.bound(path)) {
            return bindings.get(ConditionPaths.binding(path));
        }
        return selfType;
    }

    private static String relativePath(String path) {
        if (path == null || !ConditionPaths.bound(path)) {
            return path;
        }
        return ConditionPaths.field(path);
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
