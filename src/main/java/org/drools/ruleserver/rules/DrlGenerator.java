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
        for (Map.Entry<String, List<FieldAssignment>> entry : group(definition).entrySet()) {
            appendConsequence(drl, entry.getKey(), schema.get(bindingTypes.get(entry.getKey())), entry.getValue());
        }
        drl.append("end\n");
        return drl.toString();
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

    private static Map<String, List<FieldAssignment>> group(RuleDefinition definition) {
        Map<String, List<FieldAssignment>> grouped = new LinkedHashMap<>();
        for (ThenBlock block : definition.then()) {
            grouped.computeIfAbsent(block.binding(), key -> new ArrayList<>()).addAll(block.assignments());
        }
        return grouped;
    }

    private static void appendConsequence(StringBuilder drl, String binding, FieldNode typeNode, List<FieldAssignment> assignments) {
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
            String variable = prefix + "_" + entry.getKey();
            drl.append("    ").append(child.declaredType()).append(' ').append(variable).append(" = ")
                    .append(binding).append('.').append(getter(entry.getKey())).append("();\n");
            drl.append("    if ( ").append(variable).append(" == null ) { ")
                    .append(variable).append(" = new ").append(child.declaredType()).append("(); }\n");
            writeNested(drl, variable, child, entry.getValue(), entry.getKey() + ".");
            setters.add(setter(entry.getKey()) + "( " + variable + " )");
        }
        drl.append("    modify( ").append(binding).append(" ) { ");
        drl.append(String.join(", ", setters));
        drl.append(" };\n");
    }

    private static void writeNested(StringBuilder drl,
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
                drl.append("    ").append(variable).append('.').append(setter(rest))
                        .append("( ").append(literal(kind, assignment)).append(" );\n");
            } else {
                deeper.computeIfAbsent(rest.substring(0, dot), key -> new ArrayList<>()).add(assignment);
            }
        }
        for (Map.Entry<String, List<FieldAssignment>> entry : deeper.entrySet()) {
            FieldNode child = node.children().get(entry.getKey());
            String childVariable = variable + "_" + entry.getKey();
            drl.append("    ").append(child.declaredType()).append(' ').append(childVariable).append(" = ")
                    .append(variable).append('.').append(getter(entry.getKey())).append("();\n");
            drl.append("    if ( ").append(childVariable).append(" == null ) { ")
                    .append(childVariable).append(" = new ").append(child.declaredType()).append("(); ")
                    .append(variable).append('.').append(setter(entry.getKey())).append("( ").append(childVariable).append(" ); }\n");
            writeNested(drl, childVariable, child, entry.getValue(), prefix + entry.getKey() + ".");
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
