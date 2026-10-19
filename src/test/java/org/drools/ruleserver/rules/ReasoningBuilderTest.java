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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.drools.ruleserver.model.Branch;
import org.drools.ruleserver.model.BranchArm;
import org.drools.ruleserver.model.Condition;
import org.drools.ruleserver.model.FieldAssignment;
import org.drools.ruleserver.model.RuleDefinition;
import org.drools.ruleserver.model.ThenBlock;
import org.drools.ruleserver.model.ValueChange;
import org.drools.ruleserver.model.WhenBlock;
import org.junit.jupiter.api.Test;

class ReasoningBuilderTest {

    private final ReasoningBuilder builder = new ReasoningBuilder();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void describesARuleThatDidNotFire() {
        Reasoning reasoning = builder.describe("Apply senior discount", false, null,
                List.of(customer("STANDARD", 0.0, 70)), List.of(customer("STANDARD", 0.0, 70)));

        assertThat(reasoning.changes()).hasSize(1);
        assertThat(reasoning.changes().get(0).type()).isEqualTo("Customer");
        assertThat(reasoning.changes().get(0).values()).isEmpty();
        assertThat(reasoning.lines()).containsExactly(
                "Rule Apply senior discount did not fire. No fields were changed.");
        assertThat(reasoning.lines()).allSatisfy(line -> assertThat(line).doesNotContain("\\", "/"));
    }

    @Test
    void describesChangedFieldsAndIndexesRepeatedFacts() {
        FactSnapshot firstBefore = customer("STANDARD", 0.0, 70);
        FactSnapshot secondBefore = customer("STANDARD", 0.0, 30);
        FactSnapshot firstAfter = customer("SENIOR", 0.2, 70);
        FactSnapshot secondAfter = customer("STANDARD", 0.0, 30);
        RuleDefinition definition = seniorDiscount();

        Reasoning reasoning = builder.describe("Apply senior discount", true, definition,
                List.of(firstBefore, secondBefore), List.of(firstAfter, secondAfter));

        assertThat(reasoning.lines()).containsExactly(
                "Customer[1].discount=0.2 because age >= 65",
                "Customer[1].segment=SENIOR because age >= 65");
        assertThat(reasoning.lines()).allSatisfy(line -> assertThat(line).doesNotContain("\\", "/"));
        assertThat(reasoning.changes()).hasSize(2);
        assertThat(reasoning.changes().get(0).type()).isEqualTo("Customer");
        assertThat(reasoning.changes().get(0).values()).containsOnlyKeys("segment", "discount");
        assertThat(((ValueChange) reasoning.changes().get(0).values().get("segment")).newValue()).isEqualTo("SENIOR");
        assertThat(((ValueChange) reasoning.changes().get(0).values().get("discount")).newValue()).isEqualTo(0.2);
        assertThat(reasoning.changes().get(1).type()).isEqualTo("Customer");
        assertThat(reasoning.changes().get(1).values()).isEmpty();
    }

    @Test
    void tracesEveryTrueConditionThatSelectedAWrite() {
        RuleDefinition definition = new RuleDefinition(
                null,
                "Applicant tiers",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$applicant", "ApplicantInfo",
                        Condition.comparison("age", ">", mapper.getNodeFactory().numberNode(18)))),
                List.of(new ThenBlock("$applicant", null, List.of(
                        new Branch(new BranchArm(
                                Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(45)),
                                List.of(assignment("segment", "YOUNG"))), null, null),
                        new Branch(new BranchArm(
                                Condition.comparison("segment", "==", mapper.getNodeFactory().textNode("GOLD")),
                                List.of(assignment("discount", 0.1))), null, null)))));
        FactSnapshot before = applicant(50, "GOLD", 0.0);
        FactSnapshot after = applicant(50, "YOUNG", 0.1);

        Reasoning reasoning = builder.describe("Applicant tiers", true, definition, List.of(before), List.of(after));

        assertThat(reasoning.lines()).containsExactly(
                "ApplicantInfo.segment=YOUNG because age > 18 & age >= 45",
                "ApplicantInfo.discount=0.1 because age > 18 & segment=GOLD");
        assertThat(reasoning.lines()).allSatisfy(line -> assertThat(line).doesNotContain("\\", "/"));
    }

    @Test
    void aNoOpWriteDoesNotHideTheWhenGateFromTheNextChange() {
        RuleDefinition definition = new RuleDefinition(
                null,
                "Applicant tiers",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$applicant", "ApplicantInfo",
                        Condition.comparison("age", ">", mapper.getNodeFactory().numberNode(18)))),
                List.of(new ThenBlock("$applicant", null, List.of(
                        new Branch(new BranchArm(
                                Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(45)),
                                List.of(assignment("segment", "YOUNG"))), null, null),
                        new Branch(new BranchArm(
                                Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(45)),
                                List.of(assignment("discount", 0.1))), null, null)))));
        FactSnapshot before = applicant(50, "YOUNG", 0.0);
        FactSnapshot after = applicant(50, "YOUNG", 0.1);

        Reasoning reasoning = builder.describe("Applicant tiers", true, definition, List.of(before), List.of(after));

        assertThat(reasoning.lines()).containsExactly(
                "ApplicantInfo.discount=0.1 because age > 18 & age >= 45");
    }

    @Test
    void tracesLegacyConstraintTextThatSelectedTheWrite() throws Exception {
        Condition senior = mapper.readValue("[\"age >= 65\"]", Condition.class);
        RuleDefinition definition = new RuleDefinition(
                null,
                "Apply senior discount",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer", senior)),
                List.of(new ThenBlock("$customer", List.of(
                        assignment("discount", 0.2),
                        assignment("segment", "SENIOR")))));
        FactSnapshot younger = customer("STANDARD", 0.0, 30);
        FactSnapshot older = customer("STANDARD", 0.0, 70);

        Reasoning reasoning = builder.describe("Apply senior discount", true, definition,
                List.of(younger, older),
                List.of(younger, customer("SENIOR", 0.2, 70)));

        assertThat(reasoning.lines()).containsExactly(
                "Customer[2].discount=0.2 because age >= 65",
                "Customer[2].segment=SENIOR because age >= 65");
    }

    @Test
    void tracesALegacyEqualityOnANestedField() throws Exception {
        Condition london = mapper.readValue("[\"address.city == \\\"London\\\"\"]", Condition.class);
        RuleDefinition definition = new RuleDefinition(
                null,
                "Move customer to Pune",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer", london)),
                List.of(new ThenBlock("$customer", List.of(assignment("address.city", "Pune")))));
        FactSnapshot before = customerIn("London");
        FactSnapshot after = customerIn("Pune");

        Reasoning reasoning = builder.describe("Move customer to Pune", true, definition, List.of(before), List.of(after));

        assertThat(reasoning.lines()).containsExactly(
                "Customer.address.city=Pune because address.city=London");
    }

    @Test
    void everyWriteCitesTheTopWhenAndEveryTrueConditionOnTheArm() {
        RuleDefinition definition = new RuleDefinition(
                null,
                "Applicant tiers",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$applicant", "ApplicantInfo",
                        Condition.comparison("age", ">", mapper.getNodeFactory().numberNode(18)))),
                List.of(new ThenBlock("$applicant", null, List.of(
                        new Branch(new BranchArm(
                                Condition.group("and", List.of(
                                        Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(45)),
                                        Condition.comparison("segment", "==", mapper.getNodeFactory().textNode("GOLD")))),
                                List.of(assignment("discount", 0.1), assignment("note", "VIP"))),
                                null,
                                null)))));
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("age", 50);
        before.put("segment", "GOLD");
        before.put("discount", 0.0);
        before.put("note", "");
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("discount", 0.1);
        after.put("note", "VIP");

        Reasoning reasoning = builder.describe("Applicant tiers", true, definition,
                List.of(new FactSnapshot("ApplicantInfo", before)),
                List.of(new FactSnapshot("ApplicantInfo", after)));

        assertThat(reasoning.lines()).containsExactly(
                "ApplicantInfo.discount=0.1 because age > 18 & age >= 45 & segment=GOLD",
                "ApplicantInfo.note=VIP because age > 18 & age >= 45 & segment=GOLD");
    }

    @Test
    void anOrKeepsOnlyTheTrueSideAndAFalseArmIsLeftOut() {
        RuleDefinition definition = new RuleDefinition(
                null,
                "Mark priority customer",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer", Condition.group("or", List.of(
                        Condition.group("and", List.of(
                                Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(65)),
                                Condition.comparison("segment", "==", mapper.getNodeFactory().textNode("GOLD")))),
                        Condition.comparison("discount", ">", mapper.getNodeFactory().numberNode(0.5)))))),
                List.of(new ThenBlock("$customer", List.of(assignment("segment", "PRIORITY")))));
        FactSnapshot before = customer("GOLD", 0.0, 70);
        FactSnapshot after = customer("PRIORITY", 0.0, 70);

        Reasoning reasoning = builder.describe("Mark priority customer", true, definition, List.of(before), List.of(after));

        assertThat(reasoning.lines()).containsExactly(
                "Customer.segment=PRIORITY because age >= 65 & segment=GOLD");
    }

    @Test
    void omitsSlashesBackslashesAndQuotesFromTheReasoningText() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("note", "a/b\\c\"d");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("note", "x\\y/z");
        RuleDefinition definition = new RuleDefinition(
                null,
                "Path/A\\B \"C\"",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer", null)),
                List.of(new ThenBlock("$customer", List.of(assignment("note", "x\\y/z")))));

        Reasoning reasoning = builder.describe("Path/A\\B \"C\"", true, definition,
                List.of(new FactSnapshot("Customer", before)),
                List.of(new FactSnapshot("Customer", after)));

        assertThat(reasoning.lines()).containsExactly("Customer.note=xyz");
        assertThat(reasoning.lines()).allSatisfy(line -> assertThat(line).doesNotContain("\\", "/"));
        ValueChange note = (ValueChange) reasoning.changes().get(0).values().get("note");
        assertThat(note.previousValue()).isEqualTo("a/b\\c\"d");
        assertThat(note.newValue()).isEqualTo("x\\y/z");
    }

    private RuleDefinition seniorDiscount() {
        return new RuleDefinition(
                null,
                "Apply senior discount",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer",
                        Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(65)))),
                List.of(new ThenBlock("$customer", List.of(
                        assignment("discount", 0.2),
                        assignment("segment", "SENIOR")))));
    }

    private FieldAssignment assignment(String field, Object value) {
        FieldAssignment assignment = new FieldAssignment();
        assignment.setField(field);
        if (value instanceof String text) {
            assignment.setValue(mapper.getNodeFactory().textNode(text));
        } else if (value instanceof Double number) {
            assignment.setValue(mapper.getNodeFactory().numberNode(number));
        } else if (value instanceof Integer number) {
            assignment.setValue(mapper.getNodeFactory().numberNode(number));
        }
        return assignment;
    }

    private static FactSnapshot customerIn(String city) {
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("city", city);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("address", address);
        return new FactSnapshot("Customer", values);
    }

    private static FactSnapshot customer(String segment, double discount, int age) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("segment", segment);
        values.put("discount", discount);
        values.put("age", age);
        return new FactSnapshot("Customer", values);
    }

    private static FactSnapshot applicant(int age, String segment, double discount) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("age", age);
        values.put("segment", segment);
        values.put("discount", discount);
        return new FactSnapshot("ApplicantInfo", values);
    }
}
