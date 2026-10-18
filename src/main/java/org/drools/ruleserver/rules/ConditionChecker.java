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

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;

import org.drools.ruleserver.model.Condition;
import org.drools.ruleserver.rules.TypeSchemas.FieldNode;

final class ConditionChecker {

    private static final int MAX_DEPTH = 10;

    private ConditionChecker() {
    }

    static void check(String path,
                      Condition condition,
                      FieldNode self,
                      Map<String, FieldNode> boundNodes,
                      List<String> errors) {
        if (condition == null) {
            return;
        }
        walk(path, condition, self, boundNodes, 1, new Budget(path, errors), errors);
    }

    private static void walk(String path,
                             Condition condition,
                             FieldNode self,
                             Map<String, FieldNode> boundNodes,
                             int depth,
                             Budget budget,
                             List<String> errors) {
        if (budget.stopped) {
            return;
        }
        if (condition == null) {
            errors.add(path + " is required");
            return;
        }
        if (condition.getShapeError() != null) {
            errors.add(path + " " + condition.getShapeError());
            return;
        }
        if (depth > MAX_DEPTH) {
            errors.add(path + " is nested more than " + MAX_DEPTH + " levels deep");
            return;
        }
        if (condition.getOperator() == null) {
            errors.add(path + ".operator is required");
            return;
        }
        ConditionOperator operator = ConditionOperator.fromToken(condition.getOperator());
        if (operator == null) {
            errors.add(path + ".operator must be one of: " + ConditionOperator.ALLOWED);
            return;
        }
        if (operator.group()) {
            checkGroup(path, condition, operator, self, boundNodes, depth, budget, errors);
            return;
        }
        checkComparison(path, condition, operator, self, boundNodes, budget, errors);
    }

    private static void checkGroup(String path,
                                   Condition condition,
                                   ConditionOperator operator,
                                   FieldNode self,
                                   Map<String, FieldNode> boundNodes,
                                   int depth,
                                   Budget budget,
                                   List<String> errors) {
        if (condition.getField() != null || condition.hasValue() || condition.getValueField() != null) {
            errors.add(path + " cannot include field, value, or valueField when operator is " + operator.token());
        }
        List<Condition> children = condition.getConditions();
        if (children == null || children.size() < 2) {
            errors.add(path + ".conditions must contain at least 2 conditions");
            return;
        }
        for (int i = 0; i < children.size(); i++) {
            if (budget.stopped) {
                return;
            }
            walk(path + ".conditions[" + i + "]", children.get(i), self, boundNodes, depth + 1, budget, errors);
        }
    }

    private static void checkComparison(String path,
                                        Condition condition,
                                        ConditionOperator operator,
                                        FieldNode self,
                                        Map<String, FieldNode> boundNodes,
                                        Budget budget,
                                        List<String> errors) {
        if (!budget.take()) {
            return;
        }
        if (condition.getConditions() != null) {
            errors.add(path + " cannot nest conditions when operator is " + operator.token());
        }
        String field = condition.getField();
        FieldKind kind = null;
        if (field == null) {
            errors.add(path + ".field is required");
        } else if (!ConditionPaths.local(field)) {
            errors.add(path + ".field must be a field path such as age or address.city");
        } else if (self != null) {
            Resolved resolved = resolve(self, field, field);
            if (resolved.error != null) {
                errors.add(path + ".field " + resolved.error);
            } else {
                kind = resolved.node.scalar();
                if (!operator.allows(kind)) {
                    errors.add(path + ".operator " + operator.token() + " is not allowed for " + kind.drlName()
                            + "; use one of: " + ConditionOperator.allowedFor(kind));
                }
            }
        }
        boolean hasValue = condition.hasValue();
        boolean hasField = condition.getValueField() != null;
        if (hasValue == hasField) {
            errors.add(path + " must include either value or valueField");
            return;
        }
        if (hasField) {
            checkValueField(path, condition.getValueField(), kind, self, boundNodes, errors);
            return;
        }
        if (kind != null) {
            checkLiteral(path, operator, kind, condition.valueNode(), errors);
        }
    }

    private static void checkValueField(String path,
                                        String valueField,
                                        FieldKind leftKind,
                                        FieldNode self,
                                        Map<String, FieldNode> boundNodes,
                                        List<String> errors) {
        if (valueField.length() > RuleValidator.MAX_TEXT) {
            errors.add(path + ".valueField cannot be longer than " + RuleValidator.MAX_TEXT + " characters");
            return;
        }
        FieldNode root;
        String relative;
        if (ConditionPaths.bound(valueField)) {
            String binding = ConditionPaths.binding(valueField);
            root = boundNodes.get(binding);
            relative = ConditionPaths.field(valueField);
            if (root == null) {
                errors.add(path + ".valueField " + binding + " is not a bound fact");
                return;
            }
        } else if (ConditionPaths.local(valueField)) {
            root = self;
            relative = valueField;
            if (root == null) {
                return;
            }
        } else {
            errors.add(path + ".valueField must be a field path such as age or $customer.name");
            return;
        }
        Resolved resolved = resolve(root, relative, valueField);
        if (resolved.error != null) {
            errors.add(path + ".valueField " + resolved.error);
            return;
        }
        if (leftKind != null && !ConditionOperator.sameFamily(leftKind, resolved.node.scalar())) {
            errors.add(path + ".valueField " + valueField + " is " + resolved.node.scalar().drlName()
                    + " and cannot be compared with " + leftKind.drlName());
        }
    }

    private static void checkLiteral(String path,
                                     ConditionOperator operator,
                                     FieldKind kind,
                                     JsonNode value,
                                     List<String> errors) {
        if (value == null || value.isNull()) {
            if ((operator != ConditionOperator.EQ && operator != ConditionOperator.NE) || kind.primitive()) {
                errors.add(path + ".value cannot be null");
            }
            return;
        }
        String problem = JsonValues.problem(kind, value);
        if (problem != null) {
            errors.add(path + ".value " + problem);
            return;
        }
        if (value.isTextual() && value.asText().length() > RuleValidator.MAX_TEXT) {
            errors.add(path + ".value cannot be longer than " + RuleValidator.MAX_TEXT + " characters");
            return;
        }
        if (operator == ConditionOperator.MATCHES) {
            try {
                Pattern.compile(value.asText());
            } catch (PatternSyntaxException ex) {
                errors.add(path + ".value is not a valid regular expression");
            }
        }
    }

    private static Resolved resolve(FieldNode root, String path, String label) {
        FieldNode current = root;
        String[] parts = path.split("\\.", -1);
        for (int i = 0; i < parts.length; i++) {
            FieldNode next = current.children() == null ? null : current.children().get(parts[i]);
            if (next == null) {
                if (i > 0 && current.scalar() != null) {
                    return Resolved.error(current.name() + " is " + current.scalar().drlName()
                            + " and has no nested field " + parts[i]);
                }
                return Resolved.error(label + " is not a field of " + root.declaredType());
            }
            current = next;
        }
        if (current.scalar() == null) {
            return Resolved.error(label + " is an object; compare one of its fields");
        }
        return Resolved.found(current);
    }

    private record Resolved(FieldNode node, String error) {

        static Resolved found(FieldNode node) {
            return new Resolved(node, null);
        }

        static Resolved error(String error) {
            return new Resolved(null, error);
        }
    }

    private static final class Budget {

        private final String root;
        private final List<String> errors;
        private int leaves;
        private boolean stopped;

        private Budget(String root, List<String> errors) {
            this.root = root;
            this.errors = errors;
        }

        private boolean take() {
            if (stopped) {
                return false;
            }
            leaves++;
            if (leaves > RuleValidator.MAX_CONDITIONS) {
                errors.add(root + " cannot contain more than " + RuleValidator.MAX_CONDITIONS + " conditions");
                stopped = true;
                return false;
            }
            return true;
        }
    }
}
