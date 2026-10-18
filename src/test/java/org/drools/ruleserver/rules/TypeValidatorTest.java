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

import org.drools.ruleserver.model.FactDeclaration;
import org.drools.ruleserver.model.FieldDeclaration;
import org.drools.ruleserver.model.TypeDefinition;
import org.junit.jupiter.api.Test;

class TypeValidatorTest {

    private final TypeValidator validator = new TypeValidator();

    @Test
    void acceptsATypeThatReferencesAnExistingType() {
        Map<String, FactDeclaration> existing = Map.of(
                "Address", new FactDeclaration("Address", List.of(new FieldDeclaration("city", "String"))));

        assertThat(validator.validate(new TypeDefinition("Customer", List.of(
                new FieldDeclaration("name", "String"),
                new FieldDeclaration("address", "Address"))), existing)).isEmpty();
    }

    @Test
    void rejectsAnUnknownFieldTypeACycleAndADuplicateName() {
        assertThat(validator.validate(new TypeDefinition("Customer", List.of(new FieldDeclaration("address", "Address"))), Map.of()))
                .anyMatch(error -> error.contains("Address is not a defined type"));

        assertThat(validator.validate(new TypeDefinition("Node", List.of(new FieldDeclaration("next", "Node"))), Map.of()))
                .anyMatch(error -> error.contains("cycles through Node"));

        Map<String, FactDeclaration> existing = Map.of(
                "Address", new FactDeclaration("Address", List.of(new FieldDeclaration("city", "String"))));
        assertThat(validator.validate(new TypeDefinition("Address", List.of(new FieldDeclaration("city", "String"))), existing))
                .anyMatch(error -> error.contains("already defined"));
    }

    @Test
    void rejectsAFieldThatSetsBothATypeAndNestedFields() {
        FieldDeclaration both = new FieldDeclaration("city", "String", List.of(new FieldDeclaration("name", "String")));

        assertThat(validator.validate(new TypeDefinition("Address", List.of(both)), Map.of()))
                .anyMatch(error -> error.contains("must include either type or fields"));
    }

    @Test
    void acceptsNestingAtDepth10AndRejectsDepth11() {
        assertThat(validator.validate(new TypeDefinition("Deep", List.of(nested(9, new FieldDeclaration("value", "String")))), Map.of()))
                .isEmpty();
        assertThat(validator.validate(new TypeDefinition("TooDeep", List.of(nested(10, new FieldDeclaration("value", "String")))), Map.of()))
                .anyMatch(error -> error.contains("nesting depth is 11; the maximum is 10"));
    }

    @Test
    void countsEachObjectHopWhenMeasuringAReferencedType() {
        Map<String, FactDeclaration> registry = new LinkedHashMap<>();
        registry.put("Address", new FactDeclaration("Address", List.of(new FieldDeclaration("city", "String"))));

        assertThat(TypeSchemas.measure("Address", registry.get("Address").fields(), Map.of()).value()).isEqualTo(1);
        assertThat(TypeSchemas.measure("Customer", List.of(new FieldDeclaration("address", "Address")), registry).value()).isEqualTo(2);
    }

    @Test
    void rejectsANestedFieldWhoseSyntheticNameIsAlreadyAType() {
        Map<String, FactDeclaration> existing = Map.of(
                "Customer_address", new FactDeclaration("Customer_address", List.of(new FieldDeclaration("city", "String"))));
        TypeDefinition customer = new TypeDefinition("Customer", List.of(
                new FieldDeclaration("address", null, List.of(new FieldDeclaration("city", "String")))));

        assertThat(validator.validate(customer, existing))
                .anyMatch(error -> error.contains("would declare Customer_address"));
    }

    private static FieldDeclaration nested(int wrappers, FieldDeclaration leaf) {
        FieldDeclaration current = leaf;
        for (int i = 0; i < wrappers; i++) {
            current = new FieldDeclaration("n" + i, null, List.of(current));
        }
        return current;
    }
}
