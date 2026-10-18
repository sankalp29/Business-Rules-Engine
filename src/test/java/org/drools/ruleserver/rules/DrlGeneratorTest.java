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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import org.drools.ruleserver.model.Condition;
import org.drools.ruleserver.model.FactDeclaration;
import org.drools.ruleserver.model.FieldAssignment;
import org.drools.ruleserver.model.FieldDeclaration;
import org.drools.ruleserver.model.RuleDefinition;
import org.drools.ruleserver.model.ThenBlock;
import org.drools.ruleserver.model.TypeDefinition;
import org.drools.ruleserver.model.WhenBlock;
import org.junit.jupiter.api.Test;

class DrlGeneratorTest {

    private final DrlGenerator generator = new DrlGenerator();
    private final TypeExpander expander = new TypeExpander();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void convertsTheSeniorDiscountExampleToDrl() throws Exception {
        RuleDefinition definition = read("examples/create-senior-discount.json");
        List<FactDeclaration> facts = expander.expand(definition, catalog());

        String drl = generator.generate(definition, facts);

        assertThat(drl).isEqualTo("""
                package rules.dynamic

                dialect "java"

                declare Address
                    city : String
                    zip : String
                end

                declare Customer
                    name : String
                    age : int
                    discount : double
                    segment : String
                    address : Address
                end

                // Customers aged 65 or older receive a senior discount
                rule "Apply senior discount"
                    no-loop true
                when
                    $customer : Customer( age >= 65 )
                then
                    modify( $customer ) { setDiscount( 0.2 ), setSegment( "SENIOR" ) };
                end
                """);
    }

    @Test
    void writesANestedAssignmentBeforeModify() throws Exception {
        RuleDefinition definition = read("examples/create-move-city.json");

        String drl = generator.generate(definition, expander.expand(definition, catalog()));

        assertThat(drl).contains("$customer : Customer( address.city == \"London\" )");
        assertThat(drl).contains("Address customer_address = $customer.getAddress();");
        assertThat(drl).contains("if ( customer_address == null ) { customer_address = new Address(); }");
        assertThat(drl).contains("customer_address.setCity( \"Pune\" );");
        assertThat(drl).contains("modify( $customer ) { setAddress( customer_address ) };");
    }

    @Test
    void rendersLiteralsExpressionsAndSalience() {
        FieldAssignment count = assignment("count", mapper.getNodeFactory().numberNode(3), null);
        FieldAssignment total = assignment("total", mapper.getNodeFactory().numberNode(4), null);
        FieldAssignment rate = assignment("rate", null, "$item.getCount() * 0.5");
        FieldAssignment active = assignment("active", mapper.getNodeFactory().booleanNode(true), null);
        FieldAssignment label = assignment("label", mapper.getNodeFactory().textNode("a\"b"), null);
        FieldAssignment note = assignment("note", NullNode.getInstance(), null);
        List<FactDeclaration> facts = List.of(new FactDeclaration("Item", List.of(
                new FieldDeclaration("count", "int"),
                new FieldDeclaration("total", "long"),
                new FieldDeclaration("rate", "double"),
                new FieldDeclaration("active", "boolean"),
                new FieldDeclaration("label", "String"),
                new FieldDeclaration("note", "String"))));
        RuleDefinition definition = new RuleDefinition(
                null,
                "Literal item",
                null,
                10,
                null,
                null,
                List.of(new WhenBlock("$item", "Item", null)),
                List.of(new ThenBlock("$item", List.of(count, total, rate, active, label, note))));

        String drl = generator.generate(definition, facts);

        assertThat(drl).contains("salience 10");
        assertThat(drl).contains("no-loop true");
        assertThat(drl).contains("$item : Item()");
        assertThat(drl).contains(
                "setCount( 3 ), setTotal( 4L ), setRate( $item.getCount() * 0.5 ), setActive( true ), setLabel( \"a\\\"b\" ), setNote( null )");
    }

    @Test
    void rendersAndOrNestingFieldComparisonsAndQuotedValues() throws Exception {
        RuleDefinition priority = read("examples/create-priority-customer.json");
        assertThat(generator.generate(priority, expander.expand(priority, catalog()))).contains(
                "$customer : Customer( ( age >= 65 && segment == \"GOLD\" ) || discount > 0.5 )");

        Map<String, FactDeclaration> types = catalog();
        TypeDefinition order = mapper.readValue(Files.readString(Path.of("examples/create-order-type.json")), TypeDefinition.class);
        types.put(order.name(), new FactDeclaration(order.name(), order.fields()));
        RuleDefinition large = read("examples/create-large-adult-order.json");
        assertThat(generator.generate(large, expander.expand(large, types))).contains(
                "$order : Order( customerName == $customer.name && total > 100.0 )");

        List<FactDeclaration> customer = List.of(new FactDeclaration("Customer", List.of(new FieldDeclaration("name", "String"))));
        RuleDefinition hostile = new RuleDefinition(
                null,
                "Hostile name",
                null,
                null,
                null,
                null,
                List.of(new WhenBlock("$customer", "Customer",
                        Condition.comparison("name", "==", mapper.getNodeFactory().textNode("a\"b ) then end")))),
                List.of(new ThenBlock("$customer", List.of(assignment("name", mapper.getNodeFactory().textNode("ok"), null)))));
        assertThat(generator.generate(hostile, customer)).contains("name == \"a\\\"b ) then end\"");
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

    private static FieldAssignment assignment(String field, com.fasterxml.jackson.databind.JsonNode value, String expression) {
        FieldAssignment assignment = new FieldAssignment();
        assignment.setField(field);
        if (value != null) {
            assignment.setValue(value);
        }
        assignment.setExpression(expression);
        return assignment;
    }
}
