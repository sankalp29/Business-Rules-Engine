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
import java.util.List;

import org.drools.ruleserver.model.Branch;
import org.drools.ruleserver.model.BranchArm;
import org.drools.ruleserver.model.Condition;
import org.drools.ruleserver.model.FieldAssignment;
import org.drools.ruleserver.model.RuleDefinition;
import org.drools.ruleserver.model.ThenBlock;
import org.drools.ruleserver.model.WhenBlock;
import org.springframework.stereotype.Component;

@Component
public class RuleNormalizer {

    public RuleDefinition normalize(RuleDefinition input) {
        if (input == null) {
            return new RuleDefinition(null, null, null, null, null, null, null, null);
        }
        // Schemas are computed after validation. A client-supplied facts list or schema is ignored.
        return new RuleDefinition(
                trimToNull(input.code()),
                trimToNull(input.name()),
                trimToNull(input.description()),
                input.salience(),
                null,
                null,
                normalizeWhen(input.when()),
                normalizeThen(input.then()));
    }

    private static List<Branch> normalizeBranches(List<Branch> branches) {
        if (branches == null || branches.isEmpty()) {
            return null;
        }
        List<Branch> normalized = new ArrayList<>();
        for (Branch branch : branches) {
            if (branch == null) {
                normalized.add(null);
                continue;
            }
            normalized.add(new Branch(
                    normalizeArm(branch.ifArm()),
                    normalizeArms(branch.elseIf()),
                    normalizeArm(branch.otherwise())));
        }
        return normalized;
    }

    private static List<BranchArm> normalizeArms(List<BranchArm> arms) {
        if (arms == null || arms.isEmpty()) {
            return null;
        }
        List<BranchArm> normalized = new ArrayList<>();
        for (BranchArm arm : arms) {
            normalized.add(normalizeArm(arm));
        }
        return normalized;
    }

    private static BranchArm normalizeArm(BranchArm arm) {
        if (arm == null) {
            return null;
        }
        return new BranchArm(normalizeCondition(arm.when()), normalizeAssignments(arm.assignments()));
    }

    private static List<WhenBlock> normalizeWhen(List<WhenBlock> blocks) {
        if (blocks == null) {
            return null;
        }
        List<WhenBlock> normalized = new ArrayList<>();
        for (WhenBlock block : blocks) {
            if (block == null) {
                normalized.add(null);
                continue;
            }
            normalized.add(new WhenBlock(
                    trimToNull(block.binding()),
                    trimToNull(block.type()),
                    normalizeCondition(block.conditions())));
        }
        return normalized;
    }

    private static Condition normalizeCondition(Condition condition) {
        if (condition == null) {
            return null;
        }
        Condition copy = new Condition();
        copy.setShapeError(condition.getShapeError());
        copy.setLegacyConstraints(normalizeLegacy(condition.getLegacyConstraints()));
        copy.setField(trimToNull(condition.getField()));
        copy.setOperator(trimToNull(condition.getOperator()));
        copy.setValueField(trimToNull(condition.getValueField()));
        if (condition.hasValue()) {
            copy.setValue(condition.valueNode());
        }
        if (condition.getConditions() != null) {
            List<Condition> children = new ArrayList<>();
            for (Condition child : condition.getConditions()) {
                children.add(normalizeCondition(child));
            }
            copy.setConditions(children);
        }
        return copy;
    }

    private static List<ThenBlock> normalizeThen(List<ThenBlock> blocks) {
        if (blocks == null) {
            return null;
        }
        List<ThenBlock> normalized = new ArrayList<>();
        for (ThenBlock block : blocks) {
            if (block == null) {
                normalized.add(null);
                continue;
            }
            normalized.add(new ThenBlock(
                    trimToNull(block.binding()),
                    normalizeAssignments(block.assignments()),
                    normalizeBranches(block.branches())));
        }
        return normalized;
    }

    private static List<FieldAssignment> normalizeAssignments(List<FieldAssignment> assignments) {
        if (assignments == null) {
            return null;
        }
        List<FieldAssignment> normalized = new ArrayList<>();
        for (FieldAssignment assignment : assignments) {
            if (assignment == null) {
                normalized.add(null);
                continue;
            }
            FieldAssignment copy = new FieldAssignment();
            copy.setField(trimToNull(assignment.getField()));
            if (assignment.hasValue()) {
                copy.setValue(assignment.valueNode());
            }
            String expression = assignment.getExpression();
            if (expression != null && !expression.isBlank()) {
                copy.setExpression(expression.trim());
            }
            normalized.add(copy);
        }
        return normalized;
    }

    private static List<String> normalizeLegacy(List<String> constraints) {
        if (constraints == null) {
            return null;
        }
        List<String> normalized = new ArrayList<>();
        for (String constraint : constraints) {
            if (constraint == null) {
                continue;
            }
            String trimmed = constraint.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
