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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.drools.ruleserver.model.Branch;
import org.drools.ruleserver.model.BranchArm;
import org.drools.ruleserver.model.Condition;
import org.drools.ruleserver.model.ExecuteRequest;
import org.drools.ruleserver.model.ExecutionResponse;
import org.drools.ruleserver.model.FactDeclaration;
import org.drools.ruleserver.model.FactInput;
import org.drools.ruleserver.model.FieldAssignment;
import org.drools.ruleserver.model.FieldDeclaration;
import org.drools.ruleserver.model.RuleDefinition;
import org.drools.ruleserver.model.ThenBlock;
import org.drools.ruleserver.model.TypeDefinition;
import org.drools.ruleserver.model.ValueChange;
import org.drools.ruleserver.model.WhenBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 3, unit = TimeUnit.MINUTES)
class RuleExecutionEngineTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DrlGenerator generator = new DrlGenerator();
    private final TypeExpander expander = new TypeExpander();
    private final RuleExecutionEngine engine = new RuleExecutionEngine(new KieBaseCache(new RuleCompiler()), new ReasoningBuilder());

    @Test
    void firingTheSeniorDiscountDescribesTheFieldsThatChanged() throws Exception {
        ExecutionResponse response = execute("examples/create-senior-discount.json", "examples/execute-senior-discount.json");

        assertThat(response.fired()).isTrue();
        assertThat(response.reasoning()).isEqualTo(
                "Rule Apply senior discount fired. Set Customer.discount to 0.2 (was 0.0). Set Customer.segment to SENIOR (was STANDARD).");
        assertThat(response.reasoning()).doesNotContain("\\", "/");
        assertThat(response.inputData().get(0).values())
                .containsEntry("segment", "STANDARD")
                .containsEntry("discount", 0.0d);
        assertThat(response.outputData().get(0).values())
                .containsEntry("name", "Ada Lovelace")
                .containsEntry("age", 70)
                .containsEntry("discount", 0.2d)
                .containsEntry("segment", "SENIOR");
        assertThat(response.inputData().get(0).type()).isEqualTo("Customer");
        assertThat(response.outputData().get(0).type()).isEqualTo("Customer");
        assertThat(response.modifiedData()).hasSize(1);
        assertThat(response.modifiedData().get(0).type()).isEqualTo("Customer");
        assertThat(response.modifiedData().get(0).values()).containsOnlyKeys("discount", "segment");
        assertThat(response.inputData().get(0).values().get("address")).isEqualTo(Map.of("city", "London", "zip", "SW1A"));
        assertThat(response.outputData().get(0).values().get("address")).isEqualTo(Map.of("city", "London", "zip", "SW1A"));
    }

    @Test
    void aFactThatDoesNotMatchDoesNotChangeFields() throws Exception {
        ExecutionResponse response = execute("examples/create-senior-discount.json", "examples/execute-no-match.json");

        assertThat(response.fired()).isFalse();
        assertThat(response.reasoning()).isEqualTo(
                "Rule Apply senior discount did not fire. No fields were changed.");
        assertThat(response.modifiedData()).hasSize(1);
        assertThat(response.modifiedData().get(0).type()).isEqualTo(response.inputData().get(0).type());
        assertThat(response.modifiedData().get(0).values()).isEmpty();
        assertThat(response.inputData().get(0).values()).containsEntry("segment", "STANDARD");
        assertThat(response.outputData().get(0).values())
                .containsEntry("segment", "STANDARD")
                .containsEntry("discount", 0.0d);
    }

    @Test
    void firesOnlyOnceWhenSeveralFactsMatch() throws Exception {
        RuleDefinition definition = mapper.readValue(Files.readString(Path.of("examples/create-senior-discount.json")), RuleDefinition.class);
        ExecuteRequest request = new ExecuteRequest();
        request.setFacts(List.of(
                customer("Ada", 70),
                customer("Grace", 80)));

        ExecutionResponse response = run(definition, declares(definition), request);

        assertThat(response.fired()).isTrue();
        assertThat(response.inputData()).hasSize(2);
        assertThat(response.outputData()).hasSize(2);
        assertThat(response.modifiedData()).hasSize(2);
        assertThat(response.outputData().stream().filter(item -> Double.valueOf(0.2d).equals(item.values().get("discount"))).count()).isEqualTo(1);
        assertThat(response.modifiedData().stream().filter(item -> !item.values().isEmpty()).count()).isEqualTo(1);
    }

    @Test
    void noLoopStopsARuleWhoseConditionStaysTrue() {
        FieldAssignment grow = new FieldAssignment();
        grow.setField("discount");
        grow.setExpression("$customer.getDiscount() + 1");
        List<FactDeclaration> facts = List.of(new FactDeclaration("Customer", List.of(new FieldDeclaration("discount", "double"))));
        RuleDefinition definition = new RuleDefinition(
                null,
                "Grow discount",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer",
                        Condition.comparison("discount", "<", mapper.getNodeFactory().numberNode(5)))),
                List.of(new ThenBlock("$customer", List.of(grow))));
        FactInput fact = new FactInput();
        fact.setType("Customer");
        fact.setValues(Map.of("discount", mapper.getNodeFactory().numberNode(0)));
        ExecuteRequest request = new ExecuteRequest();
        request.setFacts(List.of(fact));

        ExecutionResponse response = run(definition, facts, request);

        assertThat(response.fired()).isTrue();
        assertThat(response.outputData().get(0).values()).containsEntry("discount", 1.0d);
        assertThat(response.inputData().get(0).values()).containsEntry("discount", 0.0d);
        assertThat(response.reasoning()).isEqualTo(
                "Rule Grow discount fired. Set Customer.discount to 1.0 (was 0.0).");
    }

    @Test
    void joinsFactsAndOnlyDescribesTheFactThatChanged() {
        List<FactDeclaration> facts = List.of(
                new FactDeclaration("Customer", List.of(
                        new FieldDeclaration("name", "String"),
                        new FieldDeclaration("age", "int"))),
                new FactDeclaration("Order", List.of(
                        new FieldDeclaration("customerName", "String"),
                        new FieldDeclaration("total", "double"),
                        new FieldDeclaration("priority", "String"),
                        new FieldDeclaration("approved", "boolean"))));
        RuleDefinition definition = new RuleDefinition(
                null,
                "Approve large adult orders",
                null,
                null,
                null,
                null,
                List.of(
                        new WhenBlock("$customer", "Customer",
                                Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(18))),
                        new WhenBlock("$order", "Order", Condition.group("and", List.of(
                                Condition.valueField("customerName", "==", "$customer.name"),
                                Condition.comparison("total", ">", mapper.getNodeFactory().numberNode(100)))))),
                List.of(new ThenBlock("$order", List.of(
                        assignment("priority", "HIGH"),
                        assignment("approved", true)))));
        ExecuteRequest request = new ExecuteRequest();
        request.setFacts(List.of(
                fact("Customer", Map.of(
                        "name", mapper.getNodeFactory().textNode("Ada"),
                        "age", mapper.getNodeFactory().numberNode(40))),
                fact("Order", Map.of(
                        "customerName", mapper.getNodeFactory().textNode("Ada"),
                        "total", mapper.getNodeFactory().numberNode(250),
                        "priority", mapper.getNodeFactory().textNode("NORMAL"),
                        "approved", mapper.getNodeFactory().booleanNode(false)))));

        ExecutionResponse response = run(definition, facts, request);

        assertThat(response.fired()).isTrue();
        assertThat(response.reasoning()).isEqualTo(
                "Rule Approve large adult orders fired. Set Order.priority to HIGH (was NORMAL). Set Order.approved to true (was false).");
        assertThat(response.reasoning()).doesNotContain("\\", "/");
        assertThat(response.inputData()).extracting(item -> item.type()).containsExactly("Customer", "Order");
        assertThat(response.outputData()).extracting(item -> item.type()).containsExactly("Customer", "Order");
        assertThat(response.modifiedData()).extracting(item -> item.type()).containsExactly("Customer", "Order");
        assertThat(response.modifiedData().get(0).values()).isEmpty();
        assertThat(response.modifiedData().get(1).values()).containsOnlyKeys("priority", "approved");
        assertThat(response.inputData().get(0).values()).containsEntry("age", 40);
        assertThat(response.outputData().get(0).values()).containsEntry("age", 40);
    }

    @Test
    void assignsANestedCityWhenTheConditionMatches() throws Exception {
        ExecutionResponse response = execute("examples/create-move-city.json", "examples/execute-move-city.json");

        assertThat(response.fired()).isTrue();
        assertThat(response.code()).isEqualTo("move-city");
        assertThat(response.reasoning()).isEqualTo(
                "Rule Move customer to Pune fired. Set Customer.address.city to Pune (was London).");
        assertThat(response.reasoning()).doesNotContain("\\", "/");
        assertThat(response.outputData().get(0).values().get("address")).isEqualTo(Map.of("city", "Pune", "zip", "SW1A"));
        @SuppressWarnings("unchecked")
        Map<String, Object> address = (Map<String, Object>) response.modifiedData().get(0).values().get("address");
        ValueChange city = (ValueChange) address.get("city");
        assertThat(city.previousValue()).isEqualTo("London");
        assertThat(city.newValue()).isEqualTo("Pune");
        assertThat(address).doesNotContainKey("zip");
    }

    @Test
    void assignsAFieldTwoLevelsDown() {
        List<FactDeclaration> facts = List.of(
                new FactDeclaration("Country", List.of(new FieldDeclaration("code", "String"))),
                new FactDeclaration("Address", List.of(
                        new FieldDeclaration("city", "String"),
                        new FieldDeclaration("country", "Country"))),
                new FactDeclaration("Customer", List.of(new FieldDeclaration("address", "Address"))));
        RuleDefinition definition = new RuleDefinition(
                "set-country",
                "Set country",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer", null)),
                List.of(new ThenBlock("$customer", List.of(assignment("address.country.code", "IN")))));
        ObjectNode country = mapper.createObjectNode().put("code", "GB");
        ObjectNode address = mapper.createObjectNode().put("city", "London");
        address.set("country", country);
        ExecuteRequest request = new ExecuteRequest();
        request.setFacts(List.of(fact("Customer", Map.of("address", address))));

        ExecutionResponse response = run(definition, facts, request);

        assertThat(response.fired()).isTrue();
        assertThat(response.reasoning()).isEqualTo(
                "Rule Set country fired. Set Customer.address.country.code to IN (was GB).");
        assertThat(response.reasoning()).doesNotContain("\\", "/");
    }

    @Test
    void nestedOrAndConditionFiresForEitherBranch() throws Exception {
        RuleDefinition definition = mapper.readValue(Files.readString(Path.of("examples/create-priority-customer.json")), RuleDefinition.class);
        List<FactDeclaration> declares = declares(definition);

        ExecutionResponse gold = execute("examples/create-priority-customer.json", "examples/execute-priority-customer.json");
        assertThat(gold.fired()).isTrue();
        assertThat(gold.outputData().get(0).values()).containsEntry("segment", "PRIORITY");

        ExecutionResponse discount = run(definition, declares, request(fact("Customer", Map.of(
                "name", mapper.getNodeFactory().textNode("Grace"),
                "age", mapper.getNodeFactory().numberNode(30),
                "discount", mapper.getNodeFactory().numberNode(0.6),
                "segment", mapper.getNodeFactory().textNode("STANDARD")))));
        assertThat(discount.fired()).isTrue();
        assertThat(discount.outputData().get(0).values()).containsEntry("segment", "PRIORITY");

        ExecutionResponse missed = run(definition, declares, request(customer("Grace", 30)));
        assertThat(missed.fired()).isFalse();
    }

    @Test
    void andConditionJoinsAFieldOnAnotherFact() throws Exception {
        ExecutionResponse response = execute("examples/create-large-adult-order.json", "examples/execute-large-adult-order.json");

        assertThat(response.fired()).isTrue();
        assertThat(response.reasoning()).isEqualTo(
                "Rule Approve large adult orders fired. Set Order.priority to HIGH (was NORMAL). Set Order.approved to true (was false).");
    }

    @Test
    void stringOperatorsMatchAndAQuotedValueCannotEscapeThePattern() {
        List<FactDeclaration> customer = List.of(new FactDeclaration("Customer", List.of(
                new FieldDeclaration("name", "String"),
                new FieldDeclaration("segment", "String"))));
        RuleDefinition contains = new RuleDefinition(
                "name-contains",
                "Name contains",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer",
                        Condition.comparison("name", "contains", mapper.getNodeFactory().textNode("Love")))),
                List.of(new ThenBlock("$customer", List.of(assignment("segment", "MATCH")))));
        ExecutionResponse matched = run(contains, customer, request(fact("Customer", Map.of(
                "name", mapper.getNodeFactory().textNode("Ada Lovelace"),
                "segment", mapper.getNodeFactory().textNode("STANDARD")))));
        assertThat(matched.fired()).isTrue();
        assertThat(matched.outputData().get(0).values()).containsEntry("segment", "MATCH");

        RuleDefinition matches = new RuleDefinition(
                "name-matches",
                "Name matches",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer",
                        Condition.comparison("name", "matches", mapper.getNodeFactory().textNode("A.*")))),
                List.of(new ThenBlock("$customer", List.of(assignment("segment", "MATCH")))));
        ExecutionResponse matchedPattern = run(matches, customer, request(fact("Customer", Map.of(
                "name", mapper.getNodeFactory().textNode("Ada Lovelace"),
                "segment", mapper.getNodeFactory().textNode("STANDARD")))));
        assertThat(matchedPattern.fired()).isTrue();

        String hostile = "x ) then System.exit(0); end";
        RuleDefinition quoted = new RuleDefinition(
                "hostile-name",
                "Hostile name",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer",
                        Condition.comparison("name", "==", mapper.getNodeFactory().textNode(hostile)))),
                List.of(new ThenBlock("$customer", List.of(assignment("segment", "QUOTED")))));
        assertThat(generator.generate(quoted, customer)).contains("name == \"x ) then System.exit(0); end\"");
        ExecutionResponse escaped = run(quoted, customer, request(fact("Customer", Map.of(
                "name", mapper.getNodeFactory().textNode(hostile),
                "segment", mapper.getNodeFactory().textNode("STANDARD")))));
        assertThat(escaped.fired()).isTrue();
        assertThat(escaped.outputData().get(0).values()).containsEntry("segment", "QUOTED");
    }

    @Test
    void theWhenGateChoosesOneArmInEachBranch() {
        List<FactDeclaration> facts = customerFacts();
        RuleDefinition definition = adultTiers();

        ExecutionResponse seniorGold = run(definition, facts, request(customer("Ada", 70, "GOLD")));
        assertThat(seniorGold.fired()).isTrue();
        assertThat(seniorGold.outputData().get(0).values())
                .containsEntry("segment", "SENIOR")
                .containsEntry("discount", 0.2d);

        ExecutionResponse adultSilver = run(definition, facts, request(customer("Ada", 50, "SILVER")));
        assertThat(adultSilver.fired()).isTrue();
        assertThat(adultSilver.outputData().get(0).values())
                .containsEntry("segment", "ADULT")
                .containsEntry("discount", 0.1d);

        ExecutionResponse young = run(definition, facts, request(customer("Ada", 20, "STANDARD")));
        assertThat(young.fired()).isTrue();
        assertThat(young.outputData().get(0).values())
                .containsEntry("segment", "YOUNG")
                .containsEntry("discount", 0.05d);

        ExecutionResponse child = run(definition, facts, request(customer("Ada", 10, "GOLD")));
        assertThat(child.fired()).isFalse();
        assertThat(child.reasoning()).isEqualTo("Rule Tiered discount did not fire. No fields were changed.");
        assertThat(child.outputData().get(0).values())
                .containsEntry("segment", "GOLD")
                .containsEntry("discount", 0.0d);
    }

    @Test
    void branchConditionsUseTheOriginalValuesAndTheLaterChainWins() {
        List<FactDeclaration> facts = customerFacts();
        FieldAssignment grow = new FieldAssignment();
        grow.setField("discount");
        grow.setExpression("$customer.getDiscount() + 1");
        RuleDefinition definition = new RuleDefinition(
                "tiered",
                "Tiered discount",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer",
                        Condition.comparison("age", ">", mapper.getNodeFactory().numberNode(18)))),
                List.of(new ThenBlock("$customer",
                        List.of(assignment("segment", "BASE")),
                        List.of(
                                new Branch(new BranchArm(
                                        Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(65)),
                                        List.of(grow)), null, null),
                                new Branch(new BranchArm(
                                        Condition.comparison("discount", ">=", mapper.getNodeFactory().numberNode(1)),
                                        List.of(assignment("segment", "APPLIED"))), null, null)))));

        ExecutionResponse response = run(definition, facts, request(customer("Ada", 70, "STANDARD")));

        assertThat(response.fired()).isTrue();
        assertThat(response.outputData().get(0).values()).containsEntry("discount", 1.0d);
        assertThat(response.outputData().get(0).values()).containsEntry("segment", "BASE");

        RuleDefinition laterWins = new RuleDefinition(
                "tiered",
                "Tiered discount",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer", null)),
                List.of(new ThenBlock("$customer", null, List.of(
                        new Branch(new BranchArm(
                                Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(65)),
                                List.of(assignment("segment", "FIRST"))), null, null),
                        new Branch(new BranchArm(
                                Condition.comparison("name", "==", mapper.getNodeFactory().textNode("Ada")),
                                List.of(assignment("segment", "SECOND"))), null, null)))));
        ExecutionResponse overwritten = run(laterWins, facts, request(customer("Ada", 70, "STANDARD")));
        assertThat(overwritten.fired()).isTrue();
        assertThat(overwritten.outputData().get(0).values()).containsEntry("segment", "SECOND");
    }

    @Test
    void aMissingNestedObjectTakesTheElseArm() {
        List<FactDeclaration> facts = List.of(
                new FactDeclaration("Address", List.of(new FieldDeclaration("city", "String"))),
                new FactDeclaration("Customer", List.of(
                        new FieldDeclaration("name", "String"),
                        new FieldDeclaration("segment", "String"),
                        new FieldDeclaration("address", "Address"))));
        RuleDefinition definition = new RuleDefinition(
                "away",
                "Away from Pune",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer", null)),
                List.of(new ThenBlock("$customer", null, List.of(new Branch(
                        new BranchArm(Condition.comparison("address.city", "!=", mapper.getNodeFactory().textNode("Pune")),
                                List.of(assignment("segment", "AWAY"))),
                        null,
                        new BranchArm(null, List.of(assignment("segment", "HOME"))))))));
        FactInput bare = fact("Customer", Map.of(
                "name", mapper.getNodeFactory().textNode("Ada"),
                "segment", mapper.getNodeFactory().textNode("STANDARD")));

        ExecutionResponse missing = run(definition, facts, request(bare));
        assertThat(missing.fired()).isTrue();
        assertThat(missing.outputData().get(0).values()).containsEntry("segment", "HOME");

        FactInput present = fact("Customer", Map.of(
                "name", mapper.getNodeFactory().textNode("Ada"),
                "segment", mapper.getNodeFactory().textNode("STANDARD"),
                "address", mapper.createObjectNode().put("city", "London")));
        ExecutionResponse away = run(definition, facts, request(present));
        assertThat(away.fired()).isTrue();
        assertThat(away.outputData().get(0).values()).containsEntry("segment", "AWAY");
    }

    @Test
    void twoFactsCanTakeABranchInOneFire() throws Exception {
        TypeDefinition orderType = mapper.readValue(Files.readString(Path.of("examples/create-order-type.json")), TypeDefinition.class);
        List<FactDeclaration> facts = List.of(
                customerFacts().get(0),
                new FactDeclaration(orderType.name(), orderType.fields()));
        RuleDefinition definition = new RuleDefinition(
                "split",
                "Split actions",
                null,
                null,
                null,
                null,
                List.of(
                        new WhenBlock("$customer", "Customer",
                                Condition.comparison("age", ">", mapper.getNodeFactory().numberNode(18))),
                        new WhenBlock("$order", "Order", null)),
                List.of(
                        new ThenBlock("$customer", null, List.of(new Branch(
                                new BranchArm(Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(65)),
                                        List.of(assignment("segment", "SENIOR"))),
                                null,
                                new BranchArm(null, List.of(assignment("segment", "ADULT")))))),
                        new ThenBlock("$order", null, List.of(new Branch(
                                new BranchArm(Condition.comparison("total", ">", mapper.getNodeFactory().numberNode(100)),
                                        List.of(assignment("priority", "HIGH"))),
                                null,
                                null)))));
        ExecuteRequest request = request(
                customer("Ada", 70, "STANDARD"),
                fact("Order", Map.of(
                        "customerName", mapper.getNodeFactory().textNode("Ada"),
                        "total", mapper.getNodeFactory().numberNode(250),
                        "priority", mapper.getNodeFactory().textNode("NORMAL"),
                        "approved", mapper.getNodeFactory().booleanNode(false))));

        ExecutionResponse response = run(definition, facts, request);

        assertThat(response.fired()).isTrue();
        assertThat(response.outputData().get(0).values()).containsEntry("segment", "SENIOR");
        assertThat(response.outputData().get(1).values()).containsEntry("priority", "HIGH");
    }

    private RuleDefinition adultTiers() {
        Branch tier = new Branch(
                new BranchArm(Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(65)),
                        List.of(assignment("segment", "SENIOR"))),
                List.of(new BranchArm(Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(40)),
                        List.of(assignment("segment", "ADULT")))),
                new BranchArm(null, List.of(assignment("segment", "YOUNG"))));
        Branch price = new Branch(
                new BranchArm(Condition.comparison("segment", "==", mapper.getNodeFactory().textNode("GOLD")),
                        List.of(decimalAssignment("discount", 0.2))),
                List.of(new BranchArm(Condition.comparison("segment", "==", mapper.getNodeFactory().textNode("SILVER")),
                        List.of(decimalAssignment("discount", 0.1)))),
                new BranchArm(null, List.of(decimalAssignment("discount", 0.05))));
        return new RuleDefinition(
                "tiered",
                "Tiered discount",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer",
                        Condition.comparison("age", ">", mapper.getNodeFactory().numberNode(18)))),
                List.of(new ThenBlock("$customer", null, List.of(tier, price))));
    }

    private List<FactDeclaration> customerFacts() {
        return List.of(new FactDeclaration("Customer", List.of(
                new FieldDeclaration("name", "String"),
                new FieldDeclaration("age", "int"),
                new FieldDeclaration("discount", "double"),
                new FieldDeclaration("segment", "String"))));
    }

    private FieldAssignment decimalAssignment(String field, double value) {
        FieldAssignment assignment = new FieldAssignment();
        assignment.setField(field);
        assignment.setValue(mapper.getNodeFactory().numberNode(value));
        return assignment;
    }

    private ExecutionResponse execute(String rulePath, String requestPath) throws Exception {
        RuleDefinition definition = mapper.readValue(Files.readString(Path.of(rulePath)), RuleDefinition.class);
        ExecuteRequest request = mapper.readValue(Files.readString(Path.of(requestPath)), ExecuteRequest.class);
        return run(definition, declares(definition), request);
    }

    private ExecutionResponse run(RuleDefinition definition, List<FactDeclaration> declares, ExecuteRequest request) {
        return engine.execute(1L, definition.code(), definition, generator.generate(definition, declares), request);
    }

    private List<FactDeclaration> declares(RuleDefinition definition) throws Exception {
        Map<String, FactDeclaration> types = catalog();
        boolean order = definition.when() != null && definition.when().stream()
                .anyMatch(block -> block != null && "Order".equals(block.type()));
        if (order) {
            TypeDefinition orderType = mapper.readValue(Files.readString(Path.of("examples/create-order-type.json")), TypeDefinition.class);
            types.put(orderType.name(), new FactDeclaration(orderType.name(), orderType.fields()));
        }
        return expander.expand(definition, types);
    }

    private ExecuteRequest request(FactInput... facts) {
        ExecuteRequest request = new ExecuteRequest();
        request.setFacts(List.of(facts));
        return request;
    }

    private Map<String, FactDeclaration> catalog() throws Exception {
        Map<String, FactDeclaration> types = new LinkedHashMap<>();
        for (String path : List.of("examples/create-address-type.json", "examples/create-customer-type.json")) {
            TypeDefinition type = mapper.readValue(Files.readString(Path.of(path)), TypeDefinition.class);
            types.put(type.name(), new FactDeclaration(type.name(), type.fields()));
        }
        return types;
    }

    private FieldAssignment assignment(String field, Object value) {
        FieldAssignment assignment = new FieldAssignment();
        assignment.setField(field);
        if (value instanceof String text) {
            assignment.setValue(mapper.getNodeFactory().textNode(text));
        } else if (value instanceof Boolean flag) {
            assignment.setValue(mapper.getNodeFactory().booleanNode(flag));
        }
        return assignment;
    }

    private FactInput customer(String name, int age) {
        return customer(name, age, "STANDARD");
    }

    private FactInput customer(String name, int age, String segment) {
        return fact("Customer", Map.of(
                "name", mapper.getNodeFactory().textNode(name),
                "age", mapper.getNodeFactory().numberNode(age),
                "discount", mapper.getNodeFactory().numberNode(0),
                "segment", mapper.getNodeFactory().textNode(segment)));
    }

    private static FactInput fact(String type, Map<String, com.fasterxml.jackson.databind.JsonNode> values) {
        FactInput fact = new FactInput();
        fact.setType(type);
        fact.setValues(values);
        return fact;
    }
}
