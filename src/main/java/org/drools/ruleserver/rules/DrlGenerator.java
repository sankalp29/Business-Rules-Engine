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
import org.springframework.stereotype.Component;

@Component
public class DrlGenerator {

    public static final String PACKAGE = "rules.dynamic";

    public String generate(RuleDefinition definition, List<FactDeclaration> facts) {
        List<FactDeclaration> declared = facts == null ? List.of() : facts;
        StringBuilder drl = new StringBuilder();
        drl.append("package ").append(PACKAGE).append("\n\n");
        drl.append("dialect \"java\"\n\n");
        for (int i = 0; i < declared.size(); i++) {
            if (i > 0) {
                drl.append('\n');
            }
            appendDeclare(drl, declared.get(i));
        }
        drl.append('\n');
        if (definition.description() != null && !definition.description().isBlank()) {
            drl.append("// ").append(definition.description().trim()).append('\n');
        }
        drl.append("rule \"").append(definition.name()).append("\"\n");
        if (definition.salience() != null) {
            drl.append("    salience ").append(definition.salience()).append('\n');
        }
        drl.append("    no-loop true\n");
        drl.append("when\n");
        Map<String, String> bindingTypes = new LinkedHashMap<>();
        for (WhenBlock when : definition.when()) {
            bindingTypes.put(when.binding(), when.type());
        }
        Map<String, FieldNode> schema = TypeSchemas.fromFacts(declared);
        for (WhenBlock when : definition.when()) {
            drl.append("    ").append(when.binding()).append(" : ").append(when.type()).append('(');
            if (when.conditions() == null) {
                drl.append(')');
            } else {
                drl.append(' ').append(render(when.conditions(), schema.get(when.type()), false)).append(" )");
            }
            drl.append('\n');
        }
        drl.append("then\n");
        Map<String, FieldNode> boundNodes = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : bindingTypes.entrySet()) {
            boundNodes.put(entry.getKey(), schema.get(entry.getValue()));
        }
        List<ThenBlock> blocks = definition.then() == null ? List.of() : definition.then();
        for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
            ThenBlock block = blocks.get(blockIndex);
            if (block == null || block.branches() == null) {
                continue;
            }
            for (int branchIndex = 0; branchIndex < block.branches().size(); branchIndex++) {
                Branch branch = block.branches().get(branchIndex);
                if (branch == null || branch.ifArm() == null) {
                    continue;
                }
                appendArmFlag(drl, blockIndex, branchIndex, 0, branch.ifArm(), block.binding(), boundNodes);
                List<BranchArm> elseIf = branch.elseIf() == null ? List.of() : branch.elseIf();
                for (int armIndex = 0; armIndex < elseIf.size(); armIndex++) {
                    BranchArm arm = elseIf.get(armIndex);
                    if (arm == null) {
                        continue;
                    }
                    appendArmFlag(drl, blockIndex, branchIndex, armIndex + 1, arm, block.binding(), boundNodes);
                }
            }
        }
        for (Map.Entry<String, List<FieldAssignment>> entry : group(blocks).entrySet()) {
            appendConsequence(drl, "    ", entry.getKey(), schema.get(bindingTypes.get(entry.getKey())), entry.getValue(), true, "");
        }
        for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
            ThenBlock block = blocks.get(blockIndex);
            if (block == null || block.branches() == null) {
                continue;
            }
            FieldNode typeNode = schema.get(bindingTypes.get(block.binding()));
            for (int branchIndex = 0; branchIndex < block.branches().size(); branchIndex++) {
                Branch branch = block.branches().get(branchIndex);
                if (branch == null || branch.ifArm() == null) {
                    continue;
                }
                List<BranchArm> elseIf = branch.elseIf() == null ? List.of() : branch.elseIf();
                drl.append("    if ( ").append(armFlag(blockIndex, branchIndex, 0)).append(" ) {\n");
                appendArm(drl, block, typeNode, branch.ifArm(), blockIndex, branchIndex, 0);
                for (int armIndex = 0; armIndex < elseIf.size(); armIndex++) {
                    BranchArm arm = elseIf.get(armIndex);
                    if (arm == null) {
                        continue;
                    }
                    drl.append("    } else if ( ").append(armFlag(blockIndex, branchIndex, armIndex + 1)).append(" ) {\n");
                    appendArm(drl, block, typeNode, arm, blockIndex, branchIndex, armIndex + 1);
                }
                if (branch.otherwise() != null) {
                    drl.append("    } else {\n");
                    appendArm(drl, block, typeNode, branch.otherwise(), blockIndex, branchIndex, elseIf.size() + 1);
                }
                drl.append("    }\n");
            }
        }
        drl.append("end\n");
        return drl.toString();
    }

    private static void appendArmFlag(StringBuilder drl,
                                      int blockIndex,
                                      int branchIndex,
                                      int armIndex,
                                      BranchArm arm,
                                      String binding,
                                      Map<String, FieldNode> boundNodes) {
        drl.append("    boolean ").append(armFlag(blockIndex, branchIndex, armIndex)).append(" = ")
                .append(javaCondition(arm.when(), boundNodes, binding, false)).append(";\n");
    }

    private static void appendArm(StringBuilder drl,
                                  ThenBlock block,
                                  FieldNode typeNode,
                                  BranchArm arm,
                                  int blockIndex,
                                  int branchIndex,
                                  int armIndex) {
        if (arm.assignments() == null || arm.assignments().isEmpty()) {
            return;
        }
        appendConsequence(drl, "        ", block.binding(), typeNode, arm.assignments(), false,
                "_t" + blockIndex + "b" + branchIndex + "a" + armIndex);
    }

    private static String armFlag(int blockIndex, int branchIndex, int armIndex) {
        return "then" + blockIndex + "b" + branchIndex + "a" + armIndex;
    }

    private static String render(Condition condition, FieldNode self, boolean parenthesizeGroup) {
        ConditionOperator operator = ConditionOperator.fromToken(condition.getOperator());
        if (operator == null) {
            throw new IllegalArgumentException("condition operator is not supported");
        }
        if (operator.group()) {
            String joiner = operator == ConditionOperator.AND ? " && " : " || ";
            List<String> parts = new ArrayList<>();
            for (Condition child : condition.getConditions()) {
                parts.add(render(child, self, true));
            }
            String body = String.join(joiner, parts);
            // Parentheses keep the authored nesting. && binds tighter than ||.
            return parenthesizeGroup ? "( " + body + " )" : body;
        }
        if (!ConditionPaths.local(condition.getField())) {
            throw new IllegalArgumentException("condition field is not a field path");
        }
        FieldNode leaf = TypeSchemas.resolve(self, condition.getField());
        if (leaf == null || leaf.scalar() == null) {
            throw new IllegalArgumentException("condition field " + condition.getField() + " is not a scalar field");
        }
        String right;
        if (condition.hasValue()) {
            right = JsonValues.toDrlLiteral(leaf.scalar(), condition.valueNode());
        } else if (ConditionPaths.local(condition.getValueField()) || ConditionPaths.bound(condition.getValueField())) {
            right = condition.getValueField();
        } else {
            throw new IllegalArgumentException("condition valueField is not a field path");
        }
        return condition.getField() + " " + operator.token() + " " + right;
    }

    private static Map<String, List<FieldAssignment>> group(List<ThenBlock> blocks) {
        Map<String, List<FieldAssignment>> grouped = new LinkedHashMap<>();
        if (blocks == null) {
            return grouped;
        }
        for (ThenBlock block : blocks) {
            if (block == null || block.assignments() == null) {
                continue;
            }
            if (block.assignments().isEmpty()) {
                continue;
            }
            grouped.computeIfAbsent(block.binding(), key -> new ArrayList<>()).addAll(block.assignments());
        }
        return grouped;
    }

    private static String javaCondition(Condition condition,
                                         Map<String, FieldNode> boundNodes,
                                         String selfBinding,
                                         boolean parenthesizeGroup) {
        ConditionOperator operator = ConditionOperator.fromToken(condition.getOperator());
        if (operator == null) {
            throw new IllegalArgumentException("condition operator is not supported");
        }
        if (operator.group()) {
            String joiner = operator == ConditionOperator.AND ? " && " : " || ";
            List<String> parts = new ArrayList<>();
            for (Condition child : condition.getConditions()) {
                parts.add(javaCondition(child, boundNodes, selfBinding, true));
            }
            String body = String.join(joiner, parts);
            return parenthesizeGroup ? "( " + body + " )" : body;
        }
        return javaComparison(condition, operator, boundNodes, selfBinding);
    }

    private static String javaComparison(Condition condition,
                                         ConditionOperator operator,
                                         Map<String, FieldNode> boundNodes,
                                         String selfBinding) {
        JavaAccess left = access(condition.getField(), boundNodes, selfBinding);
        JavaAccess rightAccess = null;
        String right;
        if (condition.hasValue()) {
            right = JsonValues.toDrlLiteral(left.kind, condition.valueNode());
        } else {
            rightAccess = access(condition.getValueField(), boundNodes, left.binding);
            right = rightAccess.expr;
        }
        boolean leftPrimitive = left.kind.primitive();
        boolean rightPrimitive = rightAccess == null ? left.kind.primitive() && !isNullLiteral(condition) : rightAccess.kind.primitive();
        List<String> guards = new ArrayList<>(left.guards);
        if (rightAccess != null) {
            guards.addAll(rightAccess.guards);
        }
        String comparison;
        if (operator == ConditionOperator.EQ || operator == ConditionOperator.NE) {
            if (leftPrimitive && rightPrimitive) {
                comparison = left.expr + " " + operator.token() + " " + right;
            } else if (operator == ConditionOperator.EQ) {
                comparison = "java.util.Objects.equals( " + left.expr + ", " + right + " )";
            } else {
                comparison = "!java.util.Objects.equals( " + left.expr + ", " + right + " )";
            }
        } else if (operator == ConditionOperator.CONTAINS || operator == ConditionOperator.MATCHES) {
            guards.add(left.expr + " != null");
            if (rightAccess != null && !rightAccess.kind.primitive()) {
                guards.add(right + " != null");
            }
            String call = operator == ConditionOperator.CONTAINS ? "contains" : "matches";
            comparison = left.expr + "." + call + "( " + right + " )";
        } else {
            if (!leftPrimitive) {
                guards.add(left.expr + " != null");
            }
            if (rightAccess != null && !rightAccess.kind.primitive()) {
                guards.add(right + " != null");
            }
            comparison = left.expr + " " + operator.token() + " " + right;
        }
        if (guards.isEmpty()) {
            return comparison;
        }
        return "( " + String.join(" && ", guards) + " && " + comparison + " )";
    }

    private static boolean isNullLiteral(Condition condition) {
        return condition.hasValue() && (condition.valueNode() == null || condition.valueNode().isNull());
    }

    private static JavaAccess access(String path, Map<String, FieldNode> boundNodes, String selfBinding) {
        String binding;
        String relative;
        FieldNode node;
        if (ConditionPaths.bound(path)) {
            binding = ConditionPaths.binding(path);
            relative = ConditionPaths.field(path);
            node = boundNodes.get(binding);
        } else if (selfBinding != null) {
            binding = selfBinding;
            relative = path;
            node = boundNodes.get(selfBinding);
        } else if (boundNodes.size() == 1) {
            Map.Entry<String, FieldNode> sole = boundNodes.entrySet().iterator().next();
            binding = sole.getKey();
            relative = path;
            node = sole.getValue();
        } else {
            throw new IllegalArgumentException("condition field " + path + " does not name a fact");
        }
        if (node == null) {
            throw new IllegalArgumentException("condition field " + path + " is not a bound fact");
        }
        List<String> guards = new ArrayList<>();
        String current = binding;
        for (String part : relative.split("\\.", -1)) {
            FieldNode child = node.children() == null ? null : node.children().get(part);
            if (child == null) {
                throw new IllegalArgumentException("condition field " + path + " is not a field");
            }
            current = current + "." + getter(part) + "()";
            if (child.nested()) {
                guards.add(current + " != null");
            }
            node = child;
        }
        if (node.scalar() == null) {
            throw new IllegalArgumentException("condition field " + path + " is not a scalar field");
        }
        return new JavaAccess(binding, current, guards, node.scalar());
    }

    private record JavaAccess(String binding, String expr, List<String> guards, FieldKind kind) {
    }

    private static void appendConsequence(StringBuilder drl,
                                          String indent,
                                          String binding,
                                          FieldNode typeNode,
                                          List<FieldAssignment> assignments,
                                          boolean modify,
                                          String scope) {
        List<FieldAssignment> scalars = new ArrayList<>();
        Map<String, List<FieldAssignment>> nested = new LinkedHashMap<>();
        for (FieldAssignment assignment : assignments) {
            String field = assignment.getField();
            int dot = field.indexOf('.');
            if (dot < 0) {
                scalars.add(assignment);
            } else {
                nested.computeIfAbsent(field.substring(0, dot), key -> new ArrayList<>()).add(assignment);
            }
        }
        List<String> setters = new ArrayList<>();
        for (FieldAssignment assignment : scalars) {
            FieldKind kind = typeNode.children().get(assignment.getField()).scalar();
            setters.add(setter(assignment.getField()) + "( " + literal(kind, assignment) + " )");
        }
        String prefix = binding.startsWith("$") ? binding.substring(1) : binding;
        for (Map.Entry<String, List<FieldAssignment>> entry : nested.entrySet()) {
            FieldNode child = typeNode.children().get(entry.getKey());
            String variable = prefix + "_" + entry.getKey() + (modify ? "" : scope);
            drl.append(indent).append(child.declaredType()).append(' ').append(variable).append(" = ")
                    .append(binding).append('.').append(getter(entry.getKey())).append("();\n");
            drl.append(indent).append("if ( ").append(variable).append(" == null ) { ")
                    .append(variable).append(" = new ").append(child.declaredType()).append("(); }\n");
            writeNested(drl, indent, variable, child, entry.getValue(), entry.getKey() + ".");
            setters.add(setter(entry.getKey()) + "( " + variable + " )");
        }
        if (modify) {
            drl.append(indent).append("modify( ").append(binding).append(" ) { ");
            drl.append(String.join(", ", setters));
            drl.append(" };\n");
            return;
        }
        for (String setterCall : setters) {
            drl.append(indent).append(binding).append('.').append(setterCall).append(";\n");
        }
    }

    private static void writeNested(StringBuilder drl,
                                    String indent,
                                    String variable,
                                    FieldNode node,
                                    List<FieldAssignment> assignments,
                                    String prefix) {
        Map<String, List<FieldAssignment>> deeper = new LinkedHashMap<>();
        for (FieldAssignment assignment : assignments) {
            String rest = assignment.getField().substring(prefix.length());
            int dot = rest.indexOf('.');
            if (dot < 0) {
                FieldKind kind = node.children().get(rest).scalar();
                drl.append(indent).append(variable).append('.').append(setter(rest))
                        .append("( ").append(literal(kind, assignment)).append(" );\n");
            } else {
                deeper.computeIfAbsent(rest.substring(0, dot), key -> new ArrayList<>()).add(assignment);
            }
        }
        for (Map.Entry<String, List<FieldAssignment>> entry : deeper.entrySet()) {
            FieldNode child = node.children().get(entry.getKey());
            String childVariable = variable + "_" + entry.getKey();
            drl.append(indent).append(child.declaredType()).append(' ').append(childVariable).append(" = ")
                    .append(variable).append('.').append(getter(entry.getKey())).append("();\n");
            drl.append(indent).append("if ( ").append(childVariable).append(" == null ) { ")
                    .append(childVariable).append(" = new ").append(child.declaredType()).append("(); ")
                    .append(variable).append('.').append(setter(entry.getKey())).append("( ").append(childVariable).append(" ); }\n");
            writeNested(drl, indent, childVariable, child, entry.getValue(), prefix + entry.getKey() + ".");
        }
    }

    private static void appendDeclare(StringBuilder drl, FactDeclaration fact) {
        drl.append("declare ").append(fact.type()).append('\n');
        for (FieldDeclaration field : fact.fields()) {
            drl.append("    ").append(field.name()).append(" : ").append(field.type()).append('\n');
        }
        drl.append("end\n");
    }

    private static String literal(FieldKind kind, FieldAssignment assignment) {
        if (assignment.getExpression() != null && !assignment.getExpression().isBlank()) {
            return assignment.getExpression().trim();
        }
        return JsonValues.toDrlLiteral(kind, assignment.valueNode());
    }

    static String setter(String field) {
        return "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

    private static String getter(String field) {
        return "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }
}
