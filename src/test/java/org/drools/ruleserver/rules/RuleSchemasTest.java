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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.drools.ruleserver.model.Condition;
import org.drools.ruleserver.model.FactDeclaration;
import org.drools.ruleserver.model.FieldAssignment;
import org.drools.ruleserver.model.FieldDeclaration;
import org.drools.ruleserver.model.RuleDefinition;
import org.drools.ruleserver.model.ThenBlock;
import org.drools.ruleserver.model.TypeDefinition;
import org.drools.ruleserver.model.WhenBlock;
import org.drools.ruleserver.rules.RuleSchemas.Schemas;
import org.junit.jupiter.api.Test;

class RuleSchemasTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuleNormalizer normalizer = new RuleNormalizer();

    @Test
    void seniorDiscountKeepsAgeInBothSchemasAndAssignmentsOnlyInTheOutput() throws Exception {
        Schemas schemas = RuleSchemas.build(read("examples/create-senior-discount.json"), catalog());

        assertThat(fieldNames(schemas.input(), "Customer")).containsExactly("age");
        assertThat(fieldNames(schemas.output(), "Customer")).containsExactly("age", "discount", "segment");
        assertThat(schemas.input()).extracting(FactDeclaration::type).containsExactly("Customer");
        assertThat(schemas.output()).extracting(FactDeclaration::type).containsExactly("Customer");
    }

    @Test
    void moveCityProjectsOnlyTheUsedNestedField() throws Exception {
        Schemas schemas = RuleSchemas.build(read("examples/create-move-city.json"), catalog());

        FactDeclaration customer = schemas.input().get(0);
        assertThat(customer.type()).isEqualTo("Customer");
        assertThat(customer.fields()).containsExactly(
                new FieldDeclaration("address", "Address", List.of(new FieldDeclaration("city", "String"))));
        assertThat(schemas.output()).isEqualTo(schemas.input());
        assertThat(schemas.input()).extracting(FactDeclaration::type).doesNotContain("Address");
    }

    @Test
    void nestedFieldsFollowTypeOrderAndAValueFieldIsAnInput() throws Exception {
        Map<String, FactDeclaration> types = catalog();
        TypeDefinition order = mapper.readValue(Files.readString(Path.of("examples/create-order-type.json")), TypeDefinition.class);
        types.put(order.name(), new FactDeclaration(order.name(), order.fields()));

        Schemas schemas = RuleSchemas.build(read("examples/create-large-adult-order.json"), types);

        assertThat(schemas.input()).extracting(FactDeclaration::type).containsExactly("Customer", "Order");
        assertThat(fieldNames(schemas.input(), "Customer")).containsExactly("name", "age");
        assertThat(fieldNames(schemas.input(), "Order")).containsExactly("customerName", "total");
        assertThat(fieldNames(schemas.output(), "Customer")).containsExactly("name", "age");
        assertThat(fieldNames(schemas.output(), "Order")).containsExactly("customerName", "total", "priority", "approved");

        Schemas priority = RuleSchemas.build(read("examples/create-priority-customer.json"), types);
        assertThat(fieldNames(priority.input(), "Customer")).containsExactly("age", "discount", "segment");
        assertThat(fieldNames(priority.output(), "Customer")).containsExactly("age", "discount", "segment");
    }

    @Test
    void anExpressionDoesNotAddFieldsAndAnUnusedFactIsOmitted() {
        FieldAssignment grow = new FieldAssignment();
        grow.setField("discount");
        grow.setExpression("$customer.getAge() + 1");
        RuleDefinition expression = new RuleDefinition(
                "grow",
                "Grow",
                null,
                null,
                List.of(new FactDeclaration("Nope", List.of())),
                List.of(new FactDeclaration("Nope", List.of())),
                List.of(new WhenBlock("$customer", "Customer",
                        Condition.comparison("discount", "<", mapper.getNodeFactory().numberNode(5)))),
                List.of(new ThenBlock("$customer", List.of(grow))));

        Schemas schemas = RuleSchemas.build(expression, catalogWithoutFiles());

        assertThat(fieldNames(schemas.input(), "Customer")).containsExactly("discount");
        assertThat(fieldNames(schemas.output(), "Customer")).containsExactly("discount");

        RuleDefinition unusedCustomer = new RuleDefinition(
                "approve",
                "Approve",
                null,
                null,
                null,
                null,
                List.of(
                        new WhenBlock("$customer", "Customer", null),
                        new WhenBlock("$order", "Order",
                                Condition.comparison("total", ">", mapper.getNodeFactory().numberNode(1)))),
                List.of(new ThenBlock("$order", List.of(assignment("approved", true)))));
        Schemas onlyOrder = RuleSchemas.build(unusedCustomer, catalogWithoutFiles());
        assertThat(onlyOrder.input()).extracting(FactDeclaration::type).containsExactly("Order");
        assertThat(fieldNames(onlyOrder.input(), "Order")).containsExactly("total");
        assertThat(fieldNames(onlyOrder.output(), "Order")).containsExactly("total", "approved");

        RuleDefinition assignOnly = new RuleDefinition(
                "segment-only",
                "Segment only",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer", null)),
                List.of(new ThenBlock("$customer", List.of(assignment("segment", "SENIOR")))));
        Schemas outputOnly = RuleSchemas.build(assignOnly, catalogWithoutFiles());
        assertThat(outputOnly.input()).isEmpty();
        assertThat(fieldNames(outputOnly.output(), "Customer")).containsExactly("segment");
    }

    @Test
    void mergedNestedPathsFollowTheTypeEvenWhenTheRuleMentionsZipFirst() {
        Condition both = Condition.group("and", List.of(
                Condition.comparison("address.zip", "==", mapper.getNodeFactory().textNode("SW1A")),
                Condition.comparison("address.city", "==", mapper.getNodeFactory().textNode("London"))));
        RuleDefinition definition = new RuleDefinition(
                "both",
                "Both",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer", both)),
                List.of(new ThenBlock("$customer", List.of(assignment("segment", "MOVED")))));

        Schemas schemas = RuleSchemas.build(definition, catalogWithoutFiles());

        assertThat(schemas.input().get(0).fields()).containsExactly(new FieldDeclaration(
                "address",
                "Address",
                List.of(new FieldDeclaration("city", "String"), new FieldDeclaration("zip", "String"))));
        assertThat(fieldNames(schemas.output(), "Customer")).containsExactly("segment", "address");
    }

    @Test
    void legacyConstraintStringsProjectTheSameFieldsAsConditionObjects() throws Exception {
        RuleDefinition listed = mapper.readValue("""
                {"facts":[{"type":"Customer","fields":[{"name":"bogus","type":"String"}]}],
                 "when":[{"binding":"$customer","type":"Customer","conditions":["age >= 65","address.city == \\"London\\"","segment == GOLD"]}],
                 "then":[{"binding":"$customer","assignments":[{"field":"discount","value":0.2},{"field":"segment","value":"SENIOR"}]}]}
                """, RuleDefinition.class);
        assertThat(listed.when().get(0).conditions().getShapeError()).contains("must be an object");
        assertThat(listed.when().get(0).conditions().getField()).isNull();

        Schemas schemas = RuleSchemas.build(normalizer.normalize(listed), catalog());
        assertThat(schemas).isEqualTo(RuleSchemas.build(listed, catalog()));
        assertThat(schemas.input().get(0).fields()).containsExactly(
                new FieldDeclaration("age", "int"),
                new FieldDeclaration("segment", "String"),
                new FieldDeclaration("address", "Address", List.of(new FieldDeclaration("city", "String"))));
        assertThat(fieldNames(schemas.output(), "Customer")).containsExactly("age", "discount", "segment", "address");

        RuleDefinition quoted = mapper.readValue("""
                {"when":[{"binding":"$customer","type":"Customer","conditions":"( age >= 65 && segment == \\"GOLD\\" ) || discount > 0.5"}],
                 "then":[{"binding":"$customer","assignments":[{"field":"segment","value":"PRIORITY"}]}]}
                """, RuleDefinition.class);
        Schemas priority = RuleSchemas.build(quoted, catalog());
        assertThat(fieldNames(priority.input(), "Customer")).containsExactly("age", "discount", "segment");
        assertThat(fieldNames(priority.output(), "Customer")).containsExactly("age", "discount", "segment");

        RuleDefinition quotedValue = mapper.readValue("""
                {"when":[{"binding":"$customer","type":"Customer","conditions":"name == \\"age >= 65\\""}],"then":[]}
                """, RuleDefinition.class);
        assertThat(fieldNames(RuleSchemas.build(quotedValue, catalog()).input(), "Customer")).containsExactly("name");
    }

    @Test
    void aLegacyBoundValueFieldIsAReadAndABooleanLiteralIsNot() throws Exception {
        RuleDefinition legacy = mapper.readValue("""
                {"when":[
                   {"binding":"$customer","type":"Customer","conditions":["age >= 18"]},
                   {"binding":"$order","type":"Order","conditions":"customerName == $customer.name && total > 100 && approved == true"}
                 ],
                 "then":[{"binding":"$order","assignments":[{"field":"priority","value":"HIGH"},{"field":"approved","value":true}]}]}
                """, RuleDefinition.class);

        Schemas schemas = RuleSchemas.build(legacy, catalogWithoutFiles());

        assertThat(fieldNames(schemas.input(), "Customer")).containsExactly("name", "age");
        assertThat(fieldNames(schemas.input(), "Order")).containsExactly("customerName", "total", "approved");
        assertThat(fieldNames(schemas.output(), "Customer")).containsExactly("name", "age");
        assertThat(fieldNames(schemas.output(), "Order")).containsExactly("customerName", "total", "priority", "approved");
        assertThat(LegacyConstraints.reads("approved == true && total > 1 && age >= 1 ) then System.exit(0); end"))
                .containsExactly(
                        new LegacyConstraints.Read(null, "approved"),
                        new LegacyConstraints.Read(null, "total"),
                        new LegacyConstraints.Read(null, "age"));
    }

    @Test
    void anArrayOfConditionObjectsIsNotALegacyConstraint() throws Exception {
        RuleDefinition objects = mapper.readValue("""
                {"when":[{"binding":"$customer","type":"Customer","conditions":[{"field":"age","operator":">=","value":65}]}],
                 "then":[{"binding":"$customer","assignments":[{"field":"segment","value":"SENIOR"}]}]}
                """, RuleDefinition.class);

        Schemas schemas = RuleSchemas.build(objects, catalogWithoutFiles());

        assertThat(schemas.input()).isEmpty();
        assertThat(fieldNames(schemas.output(), "Customer")).containsExactly("segment");
    }

    @Test
    void aFactsPropertyIsIgnoredAndStoredJsonDoesNotContainIt() throws Exception {
        RuleDefinition parsed = mapper.readValue("""
                {"code":"senior-discount","name":"Apply senior discount","facts":[{"type":"Customer","fields":[{"name":"bogus","type":"String"}]}],
                 "inputSchema":[{"type":"Nope","fields":[{"name":"nope","type":"String"}]}],
                 "when":[{"binding":"$customer","type":"Customer","conditions":{"field":"age","operator":">=","value":65}}],
                 "then":[{"binding":"$customer","assignments":[{"field":"discount","value":0.2}]}]}
                """, RuleDefinition.class);
        RuleDefinition normalized = normalizer.normalize(parsed);

        assertThat(normalized.inputSchema()).isNull();
        assertThat(normalized.outputSchema()).isNull();
        Schemas schemas = RuleSchemas.build(normalized, catalog());
        assertThat(fieldNames(schemas.input(), "Customer")).containsExactly("age");
        assertThat(fieldNames(schemas.output(), "Customer")).containsExactly("age", "discount");

        String stored = mapper.writeValueAsString(new RuleDefinition(
                normalized.code(),
                normalized.name(),
                normalized.description(),
                normalized.salience(),
                schemas.input(),
                schemas.output(),
                normalized.when(),
                normalized.then()));
        assertThat(stored).doesNotContain("\"facts\"");
        assertThat(stored).doesNotContain("\"code\"");
        assertThat(stored).doesNotContain("Apply senior discount");
        assertThat(stored).doesNotContain("bogus");
        assertThat(stored).doesNotContain("Nope");
        RuleDefinition reread = mapper.readValue(stored, RuleDefinition.class);
        assertThat(reread.code()).isNull();
        assertThat(reread.name()).isNull();
        assertThat(reread.when()).hasSize(1);

        assertThatThrownBy(() -> mapper.readValue("{\"foo\":1}", RuleDefinition.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    private Map<String, FactDeclaration> catalogWithoutFiles() {
        Map<String, FactDeclaration> types = new LinkedHashMap<>();
        types.put("Address", new FactDeclaration("Address", List.of(
                new FieldDeclaration("city", "String"),
                new FieldDeclaration("zip", "String"))));
        types.put("Customer", new FactDeclaration("Customer", List.of(
                new FieldDeclaration("name", "String"),
                new FieldDeclaration("age", "int"),
                new FieldDeclaration("discount", "double"),
                new FieldDeclaration("segment", "String"),
                new FieldDeclaration("address", "Address"))));
        types.put("Order", new FactDeclaration("Order", List.of(
                new FieldDeclaration("customerName", "String"),
                new FieldDeclaration("total", "double"),
                new FieldDeclaration("priority", "String"),
                new FieldDeclaration("approved", "boolean"))));
        return types;
    }

    private List<String> fieldNames(List<FactDeclaration> schema, String type) {
        FactDeclaration fact = schema.stream().filter(item -> type.equals(item.type())).findFirst().orElseThrow();
        return fact.fields().stream().map(FieldDeclaration::name).toList();
    }

    private FieldAssignment assignment(String field, boolean value) {
        FieldAssignment assignment = new FieldAssignment();
        assignment.setField(field);
        assignment.setValue(mapper.getNodeFactory().booleanNode(value));
        return assignment;
    }

    private FieldAssignment assignment(String field, String value) {
        FieldAssignment assignment = new FieldAssignment();
        assignment.setField(field);
        assignment.setValue(mapper.getNodeFactory().textNode(value));
        return assignment;
    }

    private RuleDefinition read(String path) throws Exception {
        return mapper.readValue(Files.readString(Path.of(path)), RuleDefinition.class);
    }

    private Map<String, FactDeclaration> catalog() throws Exception {
        Map<String, FactDeclaration> types = new LinkedHashMap<>();
        for (String path : List.of("examples/create-address-type.json", "examples/create-customer-type.json")) {
            TypeDefinition type = mapper.readValue(Files.readString(Path.of(path)), TypeDefinition.class);
            types.put(type.name(), new FactDeclaration(type.name(), type.fields()));
        }
        return types;
    }
}
