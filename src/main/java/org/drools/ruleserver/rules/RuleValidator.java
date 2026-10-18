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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import org.drools.ruleserver.model.Branch;
import org.drools.ruleserver.model.BranchArm;
import org.drools.ruleserver.model.ExecuteRequest;
import org.drools.ruleserver.model.FactDeclaration;
import org.drools.ruleserver.model.FactInput;
import org.drools.ruleserver.model.FieldAssignment;
import org.drools.ruleserver.model.RuleDefinition;
import org.drools.ruleserver.model.ThenBlock;
import org.drools.ruleserver.model.WhenBlock;
import org.drools.ruleserver.rules.TypeSchemas.FieldNode;
import org.springframework.stereotype.Component;

@Component
public class RuleValidator {

    static final int MAX_NAME = 200;
    static final int MAX_DESCRIPTION = 2000;
    static final int MAX_WHEN = 20;
    static final int MAX_CONDITIONS = 20;
    static final int MAX_TEXT = 500;
    static final int MAX_THEN = 20;
    static final int MAX_BRANCHES = 20;
    static final int MAX_ASSIGNMENTS = 50;
    static final int MAX_EXECUTE_FACTS = 100;
    static final int SALIENCE_LIMIT = 1_000_000;

    private static final Pattern CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    public List<String> validateForAuthoring(RuleDefinition definition, Map<String, FactDeclaration> types) {
        List<String> errors = new ArrayList<>();
        if (definition == null) {
            errors.add("request body is required");
            return errors;
        }
        Map<String, FactDeclaration> catalog = types == null ? Map.of() : types;
        validateCode(definition.code(), errors);
        validateName(definition.name(), errors);
        validateDescription(definition.description(), errors);
        validateSalience(definition.salience(), errors);
        Map<String, FieldNode> nodes = TypeSchemas.fromFacts(new ArrayList<>(catalog.values()));
        Map<String, String> bindings = validateWhen(definition.when(), catalog, nodes, errors);
        Map<String, FieldNode> boundNodes = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : bindings.entrySet()) {
            boundNodes.put(entry.getKey(), nodes.get(entry.getValue()));
        }
        validateThen(definition.then(), nodes, bindings, boundNodes, errors);
        return errors;
    }

    public List<String> validateExecution(Map<String, FactDeclaration> types, ExecuteRequest request) {
        List<String> errors = new ArrayList<>();
        if (request == null) {
            errors.add("request body is required");
            return errors;
        }
        List<FactInput> inputs = request.getFacts() == null ? List.of() : request.getFacts();
        if (inputs.size() > MAX_EXECUTE_FACTS) {
            errors.add("facts cannot contain more than " + MAX_EXECUTE_FACTS + " items");
        }
        Map<String, FieldNode> schema = TypeSchemas.fromFacts(types == null ? List.of() : new ArrayList<>(types.values()));
        int limit = Math.min(inputs.size(), MAX_EXECUTE_FACTS);
        for (int i = 0; i < limit; i++) {
            FactInput fact = inputs.get(i);
            String path = "facts[" + i + "]";
            if (fact == null || fact.getType() == null || fact.getType().isBlank()) {
                errors.add(path + ".type is required");
                continue;
            }
            String type = fact.getType().trim();
            FieldNode typeNode = schema.get(type);
            if (typeNode == null) {
                errors.add(path + ".type " + type + " is not declared on this rule");
                continue;
            }
            Map<String, JsonNode> values = fact.getValues() == null ? Map.of() : fact.getValues();
            validateValues(path + ".values", typeNode, values, errors);
        }
        return errors;
    }

    private static void validateCode(String code, List<String> errors) {
        if (code == null) {
            errors.add("code is required");
            return;
        }
        if (!CODE.matcher(code).matches()) {
            errors.add("code must start with a letter and contain only letters, digits, underscores, or hyphens");
        }
    }

    private static void validateName(String name, List<String> errors) {
        if (name == null) {
            errors.add("name is required");
            return;
        }
        if (name.length() > MAX_NAME) {
            errors.add("name cannot be longer than " + MAX_NAME + " characters");
        }
        if (hasControlOrQuote(name)) {
            errors.add("name cannot contain quotes, backslashes, or control characters");
        }
    }

    private static void validateDescription(String description, List<String> errors) {
        if (description == null) {
            return;
        }
        if (description.length() > MAX_DESCRIPTION) {
            errors.add("description cannot be longer than " + MAX_DESCRIPTION + " characters");
        }
        if (description.chars().anyMatch(ch -> ch < 0x20)) {
            errors.add("description cannot contain control characters");
        }
    }

    private static void validateSalience(Integer salience, List<String> errors) {
        if (salience == null) {
            return;
        }
        if (salience < -SALIENCE_LIMIT || salience > SALIENCE_LIMIT) {
            errors.add("salience must be between -" + SALIENCE_LIMIT + " and " + SALIENCE_LIMIT);
        }
    }

    private static Map<String, String> validateWhen(List<WhenBlock> blocks,
                                                     Map<String, FactDeclaration> catalog,
                                                     Map<String, FieldNode> nodes,
                                                     List<String> errors) {
        Map<String, String> bindings = new LinkedHashMap<>();
        Map<String, FieldNode> boundNodes = new LinkedHashMap<>();
        if (blocks == null || blocks.isEmpty()) {
            errors.add("when must contain at least one block");
            return bindings;
        }
        if (blocks.size() > MAX_WHEN) {
            errors.add("when cannot contain more than " + MAX_WHEN + " blocks");
        }
        int limit = Math.min(blocks.size(), MAX_WHEN);
        for (int i = 0; i < limit; i++) {
            WhenBlock block = blocks.get(i);
            String path = "when[" + i + "]";
            if (block == null) {
                errors.add(path + " is required");
                continue;
            }
            if (!Identifiers.isBinding(block.binding())) {
                errors.add(path + ".binding must look like $customer");
            } else if (bindings.containsKey(block.binding())) {
                errors.add(path + ".binding " + block.binding() + " is already used");
            } else {
                bindings.put(block.binding(), block.type());
                boundNodes.put(block.binding(), nodes.get(block.type()));
            }
            if (block.type() == null) {
                errors.add(path + ".type is required");
            } else if (!catalog.containsKey(block.type())) {
                errors.add(path + ".type " + block.type() + " is not defined");
            }
            ConditionChecker.check(path + ".conditions", block.conditions(), nodes.get(block.type()), boundNodes, errors);
        }
        return bindings;
    }

    private static void validateThen(List<ThenBlock> blocks,
                                     Map<String, FieldNode> nodes,
                                     Map<String, String> bindings,
                                     Map<String, FieldNode> boundNodes,
                                     List<String> errors) {
        if (blocks == null || blocks.isEmpty()) {
            errors.add("then must contain at least one block");
            return;
        }
        if (blocks.size() > MAX_THEN) {
            errors.add("then cannot contain more than " + MAX_THEN + " blocks");
        }
        Set<String> assigned = new HashSet<>();
        int limit = Math.min(blocks.size(), MAX_THEN);
        for (int i = 0; i < limit; i++) {
            ThenBlock block = blocks.get(i);
            String path = "then[" + i + "]";
            if (block == null) {
                errors.add(path + " is required");
                continue;
            }
            String type = block.binding() == null ? null : bindings.get(block.binding());
            if (!Identifiers.isBinding(block.binding()) || type == null) {
                errors.add(path + ".binding must match a when binding");
            }
            FieldNode factNode = type == null ? null : nodes.get(type);
            boolean hasAssignments = block.assignments() != null && !block.assignments().isEmpty();
            boolean hasBranches = block.branches() != null && !block.branches().isEmpty();
            if (!hasAssignments && !hasBranches) {
                errors.add(path + " must contain an assignment or a branch");
            }
            if (hasAssignments) {
                if (block.assignments().size() > MAX_ASSIGNMENTS) {
                    errors.add(path + ".assignments cannot contain more than " + MAX_ASSIGNMENTS + " items");
                }
                int assignmentLimit = Math.min(block.assignments().size(), MAX_ASSIGNMENTS);
                for (int j = 0; j < assignmentLimit; j++) {
                    validateAssignment(path + ".assignments[" + j + "]", block.binding(), block.assignments().get(j), factNode, assigned, errors);
                }
            }
            validateBranches(path, block.branches(), block.binding(), factNode, boundNodes, errors);
        }
    }

    private static void validateBranches(String blockPath,
                                         List<Branch> branches,
                                         String binding,
                                         FieldNode self,
                                         Map<String, FieldNode> boundNodes,
                                         List<String> errors) {
        if (branches == null || branches.isEmpty()) {
            return;
        }
        String path = blockPath + ".branches";
        if (branches.size() > MAX_BRANCHES) {
            errors.add(path + " cannot contain more than " + MAX_BRANCHES + " branches");
        }
        int limit = Math.min(branches.size(), MAX_BRANCHES);
        for (int i = 0; i < limit; i++) {
            Branch branch = branches.get(i);
            String branchPath = path + "[" + i + "]";
            if (branch == null) {
                errors.add(branchPath + " is required");
                continue;
            }
            if (branch.ifArm() == null) {
                errors.add(branchPath + ".if is required");
            } else {
                validateArm(branchPath + ".if", branch.ifArm(), binding, self, boundNodes, true, errors);
            }
            List<BranchArm> elseIf = branch.elseIf();
            if (elseIf != null) {
                if (elseIf.size() > MAX_BRANCHES) {
                    errors.add(branchPath + ".elseIf cannot contain more than " + MAX_BRANCHES + " arms");
                }
                int armLimit = Math.min(elseIf.size(), MAX_BRANCHES);
                for (int j = 0; j < armLimit; j++) {
                    BranchArm arm = elseIf.get(j);
                    String armPath = branchPath + ".elseIf[" + j + "]";
                    if (arm == null) {
                        errors.add(armPath + " is required");
                        continue;
                    }
                    validateArm(armPath, arm, binding, self, boundNodes, true, errors);
                }
            }
            if (branch.otherwise() != null) {
                validateArm(branchPath + ".else", branch.otherwise(), binding, self, boundNodes, false, errors);
            }
        }
    }

    private static void validateArm(String path,
                                    BranchArm arm,
                                    String binding,
                                    FieldNode self,
                                    Map<String, FieldNode> boundNodes,
                                    boolean requireWhen,
                                    List<String> errors) {
        if (requireWhen) {
            if (arm.when() == null) {
                errors.add(path + ".when is required");
            } else {
                ConditionChecker.checkBranch(path + ".when", arm.when(), self, boundNodes, errors);
            }
        } else if (arm.when() != null) {
            errors.add(path + ".when is not allowed on else");
        }
        if (arm.assignments() == null || arm.assignments().isEmpty()) {
            errors.add(path + ".assignments must contain at least one assignment");
            return;
        }
        if (arm.assignments().size() > MAX_ASSIGNMENTS) {
            errors.add(path + ".assignments cannot contain more than " + MAX_ASSIGNMENTS + " items");
        }
        Set<String> assigned = new HashSet<>();
        int limit = Math.min(arm.assignments().size(), MAX_ASSIGNMENTS);
        for (int i = 0; i < limit; i++) {
            validateAssignment(path + ".assignments[" + i + "]", binding, arm.assignments().get(i), self, assigned, errors);
        }
    }

    private static void validateAssignment(String path,
                                           String binding,
                                           FieldAssignment assignment,
                                           FieldNode factNode,
                                           Set<String> assigned,
                                           List<String> errors) {
        if (assignment == null) {
            errors.add(path + " is required");
            return;
        }
        String fieldName = assignment.getField();
        FieldNode leaf = fieldName == null ? null : TypeSchemas.resolve(factNode, fieldName);
        FieldKind kind = leaf == null ? null : leaf.scalar();
        if (fieldName == null || leaf == null) {
            errors.add(path + ".field must be a field of the bound fact");
        } else if (kind == null) {
            errors.add(path + ".field " + fieldName + " is an object; assign one of its fields");
        } else if (binding != null && !assigned.add(binding + "." + fieldName)) {
            errors.add(path + ".field " + fieldName + " is assigned more than once");
        }
        boolean hasValue = assignment.hasValue();
        boolean hasExpression = assignment.getExpression() != null && !assignment.getExpression().isBlank();
        if (hasValue == hasExpression) {
            errors.add(path + " must include either value or expression");
            return;
        }
        if (hasExpression) {
            String expression = assignment.getExpression();
            if (expression.length() > MAX_TEXT) {
                errors.add(path + ".expression cannot be longer than " + MAX_TEXT + " characters");
            }
            String problem = DrlFragments.expressionProblem(expression);
            if (problem != null) {
                errors.add(path + ".expression " + problem);
            }
            return;
        }
        if (kind != null) {
            String problem = JsonValues.problem(kind, assignment.valueNode());
            if (problem != null) {
                errors.add(path + ".value " + problem);
            }
        }
    }

    private static void validateValues(String path, FieldNode typeNode, Map<String, JsonNode> values, List<String> errors) {
        for (Map.Entry<String, JsonNode> entry : values.entrySet()) {
            FieldNode field = typeNode.children().get(entry.getKey());
            String where = path + "." + entry.getKey();
            if (field == null) {
                errors.add(where + " is not a field of " + typeNode.declaredType());
                continue;
            }
            validateValue(where, field, entry.getValue(), errors);
        }
    }

    private static void validateValue(String path, FieldNode field, JsonNode node, List<String> errors) {
        if (node == null || node.isNull()) {
            if (field.scalar() != null && field.scalar().primitive()) {
                errors.add(path + " cannot be null");
            }
            return;
        }
        if (field.scalar() != null) {
            String problem = JsonValues.problem(field.scalar(), node);
            if (problem != null) {
                errors.add(path + " " + problem);
            }
            return;
        }
        if (!node.isObject()) {
            errors.add(path + " must be a JSON object");
            return;
        }
        node.fieldNames().forEachRemaining(childName -> {
            FieldNode child = field.children().get(childName);
            String where = path + "." + childName;
            if (child == null) {
                errors.add(where + " is not a field of " + field.declaredType());
            } else {
                validateValue(where, child, node.get(childName), errors);
            }
        });
    }

    private static boolean hasControlOrQuote(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == '"' || c == '\\') {
                return true;
            }
        }
        return false;
    }
}
