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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

import org.drools.ruleserver.model.Branch;
import org.drools.ruleserver.model.BranchArm;
import org.drools.ruleserver.model.Condition;
import org.drools.ruleserver.model.ExecuteRequest;
import org.drools.ruleserver.model.FactDeclaration;
import org.drools.ruleserver.model.FactInput;
import org.drools.ruleserver.model.FieldAssignment;
import org.drools.ruleserver.model.FieldDeclaration;
import org.drools.ruleserver.model.RuleDefinition;
import org.drools.ruleserver.model.ThenBlock;
import org.drools.ruleserver.model.TypeDefinition;
import org.drools.ruleserver.model.WhenBlock;
import org.junit.jupiter.api.Test;

class RuleValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuleNormalizer normalizer = new RuleNormalizer();
    private final RuleValidator validator = new RuleValidator();
    private final TypeExpander expander = new TypeExpander();

    @Test
    void acceptsTheSeniorDiscountExample() throws Exception {
        RuleDefinition definition = normalizer.normalize(readRule("examples/create-senior-discount.json"));
        Map<String, FactDeclaration> types = catalog();

        assertThat(validator.validateForAuthoring(definition, types)).isEmpty();
    }

    @Test
    void rejectsARuleThatSkipsTheCodeOrUsesAnUndefinedType() throws Exception {
        Map<String, FactDeclaration> types = catalog();
        List<WhenBlock> when = List.of(new WhenBlock("$customer", "Customer", numberComparison("age", ">=", 65)));

        assertThat(validator.validateForAuthoring(new RuleDefinition(null, "Apply senior discount", null, null, null, null, when, List.of()), types))
                .anyMatch(error -> error.contains("code is required"));
        assertThat(validator.validateForAuthoring(new RuleDefinition("12", "Apply senior discount", null, null, null, null, when, List.of()), types))
                .anyMatch(error -> error.contains("code must start with a letter"));
        assertThat(validator.validateForAuthoring(new RuleDefinition(
                "order-rule",
                "Orders",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$order", "Order", null)),
                List.of(new ThenBlock("$order", List.of(numberAssignment("total", 1))))), types))
                .anyMatch(error -> error.contains("Order is not defined"));
    }

    @Test
    void rejectsAQuotedConstraintAndAFieldThatBreaksOutOfThePattern() throws Exception {
        Map<String, FactDeclaration> types = customerAge();
        RuleDefinition quoted = mapper.readValue("""
                {"code":"injected","name":"injected","facts":[{"type":"Customer","fields":[{"name":"age","type":"int"}]}],
                 "when":[{"binding":"$c","type":"Customer","conditions":"age >= 1 ) then System.exit(0); end"}],
                 "then":[{"binding":"$c","assignments":[{"field":"age","value":1}]}]}
                """, RuleDefinition.class);

        assertThat(validator.validateForAuthoring(quoted, types)).anyMatch(error -> error.contains("must be an object"));

        RuleDefinition listed = mapper.readValue("""
                {"code":"listed","name":"listed","facts":[{"type":"Customer","fields":[{"name":"age","type":"int"}]}],
                 "when":[{"binding":"$c","type":"Customer","conditions":["age >= 1"]}],
                 "then":[{"binding":"$c","assignments":[{"field":"age","value":1}]}]}
                """, RuleDefinition.class);
        assertThat(validator.validateForAuthoring(listed, types)).anyMatch(error -> error.contains("must be an object"));

        RuleDefinition definition = new RuleDefinition(
                "injected",
                "injected",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$c", "Customer",
                        Condition.comparison("age >= 1 ) then System.exit(0); end", "==", mapper.getNodeFactory().numberNode(1)))),
                List.of(new ThenBlock("$c", List.of(numberAssignment("age", 1)))));

        assertThat(validator.validateForAuthoring(definition, types)).anyMatch(error -> error.contains("must be a field path"));
    }

    @Test
    void rejectsAValueWhoseJsonTypeDoesNotMatchTheField() {
        FieldAssignment assignment = new FieldAssignment();
        assignment.setField("age");
        assignment.setValue(TextNode.valueOf("seventy"));
        RuleDefinition definition = new RuleDefinition(
                "bad-age",
                "bad age",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$c", "Customer", numberComparison("age", ">", 0))),
                List.of(new ThenBlock("$c", List.of(assignment))));

        assertThat(validator.validateForAuthoring(definition, customerAge())).anyMatch(error -> error.contains("must be a JSON number"));
    }

    @Test
    void rejectsUnknownFieldsAndWholeObjectAssignments() throws Exception {
        RuleDefinition senior = normalizer.normalize(readRule("examples/create-senior-discount.json"));
        Map<String, FactDeclaration> types = catalog();
        RuleDefinition unknown = new RuleDefinition(
                senior.code(),
                senior.name(),
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer", numberComparison("salary", ">", 1))),
                senior.then());
        assertThat(validator.validateForAuthoring(unknown, types)).anyMatch(error -> error.contains("salary is not a field of Customer"));

        RuleDefinition nested = new RuleDefinition(
                senior.code(),
                senior.name(),
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer",
                        Condition.comparison("address.city", "==", TextNode.valueOf("London")))),
                senior.then());
        assertThat(validator.validateForAuthoring(nested, types)).isEmpty();

        FieldAssignment wholeAddress = new FieldAssignment();
        wholeAddress.setField("address");
        wholeAddress.setValue(mapper.createObjectNode());
        RuleDefinition objectAssignment = new RuleDefinition(
                senior.code(),
                senior.name(),
                null,
                null,
                null,
                null,
                senior.when(),
                List.of(new ThenBlock("$customer", List.of(wholeAddress))));
        assertThat(validator.validateForAuthoring(objectAssignment, types)).anyMatch(error -> error.contains("is an object; assign one of its fields"));
    }

    @Test
    void rejectsAComparisonWhoseValueDoesNotMatchTheFieldType() throws Exception {
        RuleDefinition senior = normalizer.normalize(readRule("examples/create-senior-discount.json"));
        Map<String, FactDeclaration> types = catalog();
        RuleDefinition stringComparedWithNumber = new RuleDefinition(
                senior.code(),
                senior.name(),
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer", numberComparison("segment", ">=", 65))),
                senior.then());
        assertThat(validator.validateForAuthoring(stringComparedWithNumber, types))
                .anyMatch(error -> error.contains("operator >= is not allowed for String"));

        RuleDefinition numberComparedWithText = new RuleDefinition(
                senior.code(),
                senior.name(),
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer",
                        Condition.comparison("age", "==", TextNode.valueOf("70")))),
                senior.then());
        assertThat(validator.validateForAuthoring(numberComparedWithText, types))
                .anyMatch(error -> error.contains("value must be a JSON number"));

        RuleDefinition fieldTypesDiffer = new RuleDefinition(
                senior.code(),
                senior.name(),
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer", Condition.valueField("age", ">=", "segment"))),
                senior.then());
        assertThat(validator.validateForAuthoring(fieldTypesDiffer, types))
                .anyMatch(error -> error.contains("segment is String and cannot be compared with int"));

        RuleDefinition wholeObject = new RuleDefinition(
                senior.code(),
                senior.name(),
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer",
                        Condition.comparison("address", "==", TextNode.valueOf("London")))),
                senior.then());
        assertThat(validator.validateForAuthoring(wholeObject, types))
                .anyMatch(error -> error.contains("address is an object; compare one of its fields"));

        RuleDefinition nullAge = new RuleDefinition(
                senior.code(),
                senior.name(),
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer",
                        Condition.comparison("age", "==", mapper.nullNode()))),
                senior.then());
        assertThat(validator.validateForAuthoring(nullAge, types)).anyMatch(error -> error.contains("value cannot be null"));
    }

    @Test
    void acceptsAndOrGroupsNestedConditionsAndFieldComparisons() throws Exception {
        Map<String, FactDeclaration> types = catalog();
        TypeDefinition order = mapper.readValue(Files.readString(Path.of("examples/create-order-type.json")), TypeDefinition.class);
        types.put(order.name(), new FactDeclaration(order.name(), order.fields()));

        RuleDefinition priority = normalizer.normalize(readRule("examples/create-priority-customer.json"));
        assertThat(validator.validateForAuthoring(priority, types)).isEmpty();

        RuleDefinition large = normalizer.normalize(readRule("examples/create-large-adult-order.json"));
        assertThat(validator.validateForAuthoring(large, types)).isEmpty();

        RuleDefinition moved = normalizer.normalize(readRule("examples/create-move-city.json"));
        assertThat(validator.validateForAuthoring(moved, types)).isEmpty();

        Condition later = Condition.valueField("name", "==", "$order.customerName");
        RuleDefinition forward = new RuleDefinition(
                "forward-ref",
                "Forward",
                null,
                null,
                null,
                null,
                List.of(
                        new WhenBlock("$customer", "Customer", later),
                        new WhenBlock("$order", "Order", null)),
                List.of(new ThenBlock("$customer", List.of(numberAssignment("age", 1)))));
        assertThat(validator.validateForAuthoring(normalizer.normalize(forward), types))
                .anyMatch(error -> error.contains("$order is not a bound fact"));

        Condition onlyOne = Condition.group("and", List.of(numberComparison("age", ">=", 18)));
        RuleDefinition single = new RuleDefinition(
                "one-side",
                "One side",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer", onlyOne)),
                List.of(new ThenBlock("$customer", List.of(numberAssignment("age", 1)))));
        assertThat(validator.validateForAuthoring(single, types))
                .anyMatch(error -> error.contains("must contain at least 2 conditions"));
    }

    @Test
    void rejectsAnExecutionFactThatIsNotDeclared() throws Exception {
        Map<String, FactDeclaration> types = catalog();
        FactInput fact = new FactInput();
        fact.setType("Order");
        ExecuteRequest request = new ExecuteRequest();
        request.setFacts(List.of(fact));

        assertThat(validator.validateExecution(declared(readRule("examples/create-senior-discount.json"), types), request))
                .contains("facts[0].type Order is not declared on this rule");
    }

    @Test
    void rejectsExecutionValuesThatDoNotMatchTheDeclaredField() throws Exception {
        Map<String, FactDeclaration> declared = declared(readRule("examples/create-senior-discount.json"), catalog());

        assertThat(validator.validateExecution(declared, execution("age", TextNode.valueOf("seventy"))))
                .anyMatch(error -> error.contains("age must be a JSON number"));
        assertThat(validator.validateExecution(declared, execution("address", mapper.createObjectNode().put("city", 12))))
                .anyMatch(error -> error.contains("address.city must be a JSON string"));
        assertThat(validator.validateExecution(declared, execution("salary", mapper.getNodeFactory().numberNode(1))))
                .anyMatch(error -> error.contains("salary is not a field of Customer"));
        assertThat(validator.validateExecution(declared, execution("address", TextNode.valueOf("London"))))
                .anyMatch(error -> error.contains("address must be a JSON object"));
    }

    @Test
    void aThenBlockNeedsAnAssignmentOrABranch() throws Exception {
        Map<String, FactDeclaration> types = catalog();
        List<WhenBlock> facts = List.of(new WhenBlock("$customer", "Customer", null));
        Branch senior = new Branch(
                new BranchArm(Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(65)),
                        List.of(textAssignment("segment", "SENIOR"))),
                List.of(new BranchArm(Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(40)),
                        List.of(textAssignment("segment", "ADULT")))),
                new BranchArm(null, List.of(textAssignment("segment", "YOUNG"))));
        RuleDefinition branched = new RuleDefinition(
                "tiered", "Tiered", null, null, null, null, facts,
                List.of(new ThenBlock("$customer", List.of(), List.of(senior))));

        assertThat(validator.validateForAuthoring(normalizer.normalize(branched), types)).isEmpty();

        RuleDefinition neither = new RuleDefinition(
                "tiered", "Tiered", null, null, null, null, facts, List.of());
        assertThat(validator.validateForAuthoring(neither, types))
                .anyMatch(error -> error.contains("then must contain at least one block"));

        RuleDefinition missingIf = new RuleDefinition(
                "tiered", "Tiered", null, null, null, null, facts,
                List.of(new ThenBlock("$customer", null, List.of(new Branch(null, null,
                        new BranchArm(null, List.of(textAssignment("segment", "YOUNG"))))))));
        assertThat(validator.validateForAuthoring(missingIf, types))
                .anyMatch(error -> error.contains("then[0].branches[0].if is required"));

        RuleDefinition elseCondition = new RuleDefinition(
                "tiered", "Tiered", null, null, null, null, facts,
                List.of(new ThenBlock("$customer", null, List.of(new Branch(
                        new BranchArm(Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(65)),
                                List.of(textAssignment("segment", "SENIOR"))),
                        null,
                        new BranchArm(Condition.comparison("age", "<", mapper.getNodeFactory().numberNode(65)),
                                List.of(textAssignment("segment", "YOUNG"))))))));
        assertThat(validator.validateForAuthoring(elseCondition, types))
                .anyMatch(error -> error.contains("then[0].branches[0].else.when is not allowed on else"));
    }

    @Test
    void aBranchFieldIsLocalToItsThenFact() throws Exception {
        Map<String, FactDeclaration> types = catalog();
        TypeDefinition order = mapper.readValue(Files.readString(Path.of("examples/create-order-type.json")), TypeDefinition.class);
        types.put(order.name(), new FactDeclaration(order.name(), order.fields()));
        List<WhenBlock> facts = List.of(
                new WhenBlock("$customer", "Customer", null),
                new WhenBlock("$order", "Order", null));
        RuleDefinition wrongFact = new RuleDefinition(
                "tiered", "Tiered", null, null, null, null, facts,
                List.of(new ThenBlock("$order", null, List.of(new Branch(
                        new BranchArm(Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(18)),
                                List.of(textAssignment("priority", "HIGH"))),
                        null,
                        null)))));

        assertThat(validator.validateForAuthoring(wrongFact, types))
                .anyMatch(error -> error.contains("age is not a field of Order"));

        RuleDefinition named = new RuleDefinition(
                "tiered", "Tiered", null, null, null, null, facts,
                List.of(
                        new ThenBlock("$customer", null, List.of(new Branch(
                                new BranchArm(Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(65)),
                                        List.of(textAssignment("segment", "SENIOR"))),
                                null,
                                null))),
                        new ThenBlock("$order", null, List.of(new Branch(
                                new BranchArm(Condition.comparison("$customer.age", ">", mapper.getNodeFactory().numberNode(18)),
                                        List.of(textAssignment("priority", "HIGH"))),
                                null,
                                null)))));
        assertThat(validator.validateForAuthoring(named, types)).isEmpty();
    }

    @Test
    void theSameFieldMayBeSetOnDifferentArmsButNotTwiceOnOne() throws Exception {
        Map<String, FactDeclaration> types = catalog();
        List<WhenBlock> facts = List.of(new WhenBlock("$customer", "Customer", null));
        RuleDefinition across = new RuleDefinition(
                "tiered", "Tiered", null, null, null, null, facts,
                List.of(new ThenBlock("$customer", null, List.of(
                        new Branch(
                                new BranchArm(Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(65)),
                                        List.of(textAssignment("segment", "SENIOR"))),
                                null,
                                new BranchArm(null, List.of(textAssignment("segment", "ADULT")))),
                        new Branch(
                                new BranchArm(Condition.comparison("name", "==", mapper.getNodeFactory().textNode("Ada")),
                                        List.of(textAssignment("segment", "NAMED"))),
                                null,
                                null)))));
        assertThat(validator.validateForAuthoring(across, types)).isEmpty();

        RuleDefinition twice = new RuleDefinition(
                "tiered", "Tiered", null, null, null, null, facts,
                List.of(new ThenBlock("$customer", null, List.of(new Branch(
                        new BranchArm(Condition.comparison("age", ">=", mapper.getNodeFactory().numberNode(65)),
                                List.of(textAssignment("segment", "SENIOR"), textAssignment("segment", "GOLD"))),
                        null,
                        null)))));
        assertThat(validator.validateForAuthoring(twice, types))
                .anyMatch(error -> error.contains("segment is assigned more than once"));
    }

    private FieldAssignment textAssignment(String field, String value) {
        FieldAssignment assignment = new FieldAssignment();
        assignment.setField(field);
        assignment.setValue(mapper.getNodeFactory().textNode(value));
        return assignment;
    }

    private Condition numberComparison(String field, String operator, int value) {
        return Condition.comparison(field, operator, mapper.getNodeFactory().numberNode(value));
    }

    private FieldAssignment numberAssignment(String field, int value) {
        FieldAssignment assignment = new FieldAssignment();
        assignment.setField(field);
        assignment.setValue(mapper.getNodeFactory().numberNode(value));
        return assignment;
    }

    private ExecuteRequest execution(String field, JsonNode value) {
        FactInput fact = new FactInput();
        fact.setType("Customer");
        fact.setValues(Map.of(field, value));
        ExecuteRequest request = new ExecuteRequest();
        request.setFacts(List.of(fact));
        return request;
    }

    private Map<String, FactDeclaration> customerAge() {
        return Map.of("Customer", new FactDeclaration("Customer", List.of(new FieldDeclaration("age", "int"))));
    }

    private Map<String, FactDeclaration> declared(RuleDefinition definition, Map<String, FactDeclaration> catalog) {
        Map<String, FactDeclaration> types = new LinkedHashMap<>();
        for (FactDeclaration fact : expander.expand(definition, catalog)) {
            types.put(fact.type(), fact);
        }
        return types;
    }

    private Map<String, FactDeclaration> catalog() throws Exception {
        Map<String, FactDeclaration> types = new LinkedHashMap<>();
        for (String path : List.of("examples/create-address-type.json", "examples/create-customer-type.json")) {
            TypeDefinition type = mapper.readValue(Files.readString(Path.of(path)), TypeDefinition.class);
            types.put(type.name(), new FactDeclaration(type.name(), type.fields()));
        }
        return types;
    }

    private RuleDefinition readRule(String path) throws Exception {
        return mapper.readValue(Files.readString(Path.of(path)), RuleDefinition.class);
    }
}
