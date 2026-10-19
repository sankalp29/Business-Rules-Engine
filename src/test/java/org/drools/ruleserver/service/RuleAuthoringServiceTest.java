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
package org.drools.ruleserver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.drools.ruleserver.model.FactDeclaration;
import org.drools.ruleserver.model.FieldDeclaration;
import org.drools.ruleserver.model.RuleDefinition;
import org.drools.ruleserver.model.RuleResponse;
import org.drools.ruleserver.model.ThenBlock;
import org.drools.ruleserver.persistence.AuthoredRule;
import org.drools.ruleserver.persistence.AuthoredRuleRepository;
import org.drools.ruleserver.persistence.RuleVersionRepository;
import org.drools.ruleserver.rules.DrlGenerator;
import org.drools.ruleserver.rules.KieBaseCache;
import org.drools.ruleserver.rules.RuleNormalizer;
import org.drools.ruleserver.rules.RuleValidator;
import org.drools.ruleserver.rules.TypeExpander;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RuleAuthoringServiceTest {

    private AuthoredRuleRepository repository;
    private FactTypeService typeService;
    private RuleAuthoringService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(AuthoredRuleRepository.class);
        typeService = Mockito.mock(FactTypeService.class);
        service = new RuleAuthoringService(
                repository,
                Mockito.mock(RuleVersionRepository.class),
                typeService,
                new RuleNormalizer(),
                Mockito.mock(RuleValidator.class),
                Mockito.mock(TypeExpander.class),
                Mockito.mock(DrlGenerator.class),
                Mockito.mock(KieBaseCache.class),
                new ObjectMapper(),
                Clock.systemUTC(),
                Mockito.mock(ApprovalService.class));
    }

    @Test
    void aRuleStoredBeforeSchemasExistedIsFilledOnReadAndTheRowIsNotRewritten() {
        String json = """
                {"facts":[{"type":"Customer","fields":[{"name":"name","type":"String"},{"name":"age","type":"int"}]}],
                 "when":[{"binding":"$customer","type":"Customer","conditions":["age >= 65"]}],
                 "then":[{"binding":"$customer","assignments":[{"field":"discount","value":0.2},{"field":"segment","value":"SENIOR"}]}]}
                """;
        AuthoredRule entity = rule("legacy-string-schema", json);
        when(repository.findByCode("legacy-string-schema")).thenReturn(Optional.of(entity));
        when(typeService.catalog()).thenReturn(catalog());

        RuleResponse response = service.get("legacy-string-schema");

        assertThat(response.versions().get(0).definition().inputSchema()).containsExactly(new FactDeclaration("Customer", List.of(
                new FieldDeclaration("age", "int"))));
        assertThat(response.versions().get(0).definition().outputSchema()).containsExactly(new FactDeclaration("Customer", List.of(
                new FieldDeclaration("age", "int"),
                new FieldDeclaration("discount", "double"),
                new FieldDeclaration("segment", "String"))));
        assertThat(entity.getDefinition()).isEqualTo(json);
        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aStoredSchemaStaysAsSavedAndOnlyANullSchemaIsFilled() {
        String kept = """
                {"inputSchema":[],"outputSchema":[{"type":"Customer","fields":[{"name":"segment","type":"String"}]}],
                 "when":[{"binding":"$customer","type":"Customer","conditions":{"field":"age","operator":">=","value":65}}],
                 "then":[{"binding":"$customer","assignments":[{"field":"discount","value":0.2}]}]}
                """;
        String inputOnly = """
                {"inputSchema":[{"type":"Customer","fields":[{"name":"name","type":"String"}]}],
                 "when":[{"binding":"$customer","type":"Customer","conditions":{"field":"age","operator":">=","value":65}}],
                 "then":[{"binding":"$customer","assignments":[{"field":"discount","value":0.2},{"field":"segment","value":"SENIOR"}]}]}
                """;
        AuthoredRule keptRule = rule("kept-schema", kept);
        AuthoredRule partial = rule("partial-schema", inputOnly);
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(keptRule, partial));
        when(typeService.catalog()).thenReturn(catalog());

        List<RuleResponse> responses = service.list();

        assertThat(responses.get(0).versions().get(0).definition().inputSchema()).isEmpty();
        assertThat(responses.get(0).versions().get(0).definition().outputSchema()).containsExactly(new FactDeclaration("Customer", List.of(
                new FieldDeclaration("segment", "String"))));
        assertThat(responses.get(1).versions().get(0).definition().inputSchema()).containsExactly(new FactDeclaration("Customer", List.of(
                new FieldDeclaration("name", "String"))));
        assertThat(responses.get(1).versions().get(0).definition().outputSchema()).containsExactly(new FactDeclaration("Customer", List.of(
                new FieldDeclaration("age", "int"),
                new FieldDeclaration("discount", "double"),
                new FieldDeclaration("segment", "String"))));
        assertThat(keptRule.getDefinition()).isEqualTo(kept);
        assertThat(partial.getDefinition()).isEqualTo(inputOnly);
        verify(typeService, times(1)).catalog();
        verify(repository, never()).save(any());
    }

    @Test
    void anObjectConditionWithoutASchemaMatchesANewlyComputedRule() {
        String json = """
                {"when":[{"binding":"$customer","type":"Customer","conditions":{"operator":"or","conditions":[
                    {"operator":"and","conditions":[
                      {"field":"age","operator":">=","value":65},
                      {"field":"segment","operator":"==","value":"GOLD"}]},
                    {"field":"discount","operator":">","value":0.5}]}}],
                 "then":[{"binding":"$customer","assignments":[{"field":"segment","value":"PRIORITY"}]}]}
                """;
        AuthoredRule entity = rule(null, json);
        when(repository.findByCode("17")).thenReturn(Optional.empty());
        when(repository.findById(17L)).thenReturn(Optional.of(entity));
        when(typeService.catalog()).thenReturn(catalog());

        RuleDefinition definition = service.get("17").versions().get(0).definition();

        assertThat(definition.inputSchema()).containsExactly(customer("age", "discount", "segment"));
        assertThat(definition.outputSchema()).containsExactly(customer("age", "discount", "segment"));
    }

    @Test
    void rulesStoredWithATopLevelBranchListStillLoadAndEachIfStays() {
        String nested = """
                {"salience":0,"inputSchema":[{"type":"Customer","fields":[{"name":"age","type":"int"}]}],
                 "outputSchema":[{"type":"Customer","fields":[{"name":"discount","type":"double"}]}],
                 "when":[{"binding":"$customer","type":"Customer","conditions":null}],"then":[],
                 "branches":[
                   {"when":{"operator":"and","conditions":[
                      {"field":"$customer.age","operator":">=","value":65},
                      {"field":"$customer.segment","operator":"==","value":"GOLD"}]},
                    "then":[{"binding":"$customer","assignments":[{"field":"discount","value":0.2}]}]},
                   {"when":{"field":"$customer.name","operator":"contains","value":"Grace"},
                    "then":[{"binding":"$customer","assignments":[{"field":"segment","value":"MATCH"}]}]}]}
                """;
        String applicant = """
                {"salience":0,"inputSchema":[{"type":"ApplicantInfo","fields":[{"name":"age","type":"int"}]}],
                 "outputSchema":[{"type":"ApplicantInfo","fields":[{"name":"name","type":"String"}]}],
                 "when":[{"binding":"$applicantInfo","type":"ApplicantInfo","conditions":null}],"then":[],
                 "branches":[
                   {"when":{"field":"$applicantInfo.age","operator":">","value":25},
                    "then":[{"binding":"$applicantInfo","assignments":[{"field":"name","value":"Is > 25"}]}]},
                   {"when":{"field":"$applicantInfo.age","operator":">","value":35},
                    "then":[{"binding":"$applicantInfo","assignments":[{"field":"name","value":"Is > 35"}]}]}]}
                """;
        AuthoredRule nestedRule = rule("branch-nested-mvdzjcn7", nested);
        AuthoredRule applicantRule = rule("branch-rule", applicant);
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(nestedRule, applicantRule));

        List<RuleResponse> responses = service.list();

        ThenBlock customer = responses.get(0).versions().get(0).definition().then().get(0);
        assertThat(customer.binding()).isEqualTo("$customer");
        assertThat(customer.assignments()).isEmpty();
        assertThat(customer.branches()).hasSize(2);
        assertThat(customer.branches().get(0).elseIf()).isNull();
        assertThat(customer.branches().get(0).otherwise()).isNull();
        assertThat(customer.branches().get(0).ifArm().when().getOperator()).isEqualTo("and");
        assertThat(customer.branches().get(0).ifArm().when().getConditions()).extracting(condition -> condition.getField())
                .containsExactly("$customer.age", "$customer.segment");
        assertThat(customer.branches().get(0).ifArm().assignments().get(0).getField()).isEqualTo("discount");
        assertThat(customer.branches().get(0).ifArm().assignments().get(0).getValue().asText()).isEqualTo("0.2");
        assertThat(customer.branches().get(1).ifArm().when().getOperator()).isEqualTo("contains");
        assertThat(customer.branches().get(1).ifArm().when().getField()).isEqualTo("$customer.name");
        assertThat(customer.branches().get(1).ifArm().assignments().get(0).getValue().textValue()).isEqualTo("MATCH");
        assertThat(responses.get(0).versions().get(0).definition().inputSchema()).containsExactly(customer("age"));
        ThenBlock person = responses.get(1).versions().get(0).definition().then().get(0);
        assertThat(person.binding()).isEqualTo("$applicantInfo");
        assertThat(person.branches()).hasSize(2);
        assertThat(person.branches().get(0).ifArm().assignments().get(0).getValue().textValue()).isEqualTo("Is > 25");
        assertThat(person.branches().get(1).ifArm().when().getValue().asInt()).isEqualTo(35);
        assertThat(person.branches().get(0).otherwise()).isNull();
        assertThat(nestedRule.getDefinition()).isEqualTo(nested);
        assertThat(applicantRule.getDefinition()).isEqualTo(applicant);
        assertThat(responses.get(0).versions().get(0).drl()).isEqualTo(nestedRule.getDrl());
        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aLegacyBranchKeepsUnconditionalAssignmentsAndLandsOnEachFact() {
        String json = """
                {"inputSchema":[],"outputSchema":[],
                 "when":[{"binding":"$customer","type":"Customer"},{"binding":"$order","type":"Order"}],
                 "then":[{"binding":"$customer","assignments":[{"field":"nickname","value":"kept"}]}],
                 "branches":[{"when":{"field":"$order.total","operator":">","value":100},
                   "then":[
                     {"binding":"$customer","assignments":[{"field":"segment","value":"BIG"}]},
                     {"binding":"$order","assignments":[{"field":"priority","value":"HIGH"}]}]}]}
                """;
        AuthoredRule entity = rule("split-facts", json);
        when(repository.findByCode("split-facts")).thenReturn(Optional.of(entity));

        RuleDefinition definition = service.get("split-facts").versions().get(0).definition();

        assertThat(definition.then()).hasSize(2);
        ThenBlock customer = definition.then().get(0);
        assertThat(customer.assignments()).extracting(assignment -> assignment.getField()).containsExactly("nickname");
        assertThat(customer.branches()).hasSize(1);
        assertThat(customer.branches().get(0).ifArm().when().getField()).isEqualTo("$order.total");
        assertThat(customer.branches().get(0).ifArm().assignments().get(0).getValue().textValue()).isEqualTo("BIG");
        ThenBlock order = definition.then().get(1);
        assertThat(order.binding()).isEqualTo("$order");
        assertThat(order.assignments()).isEmpty();
        assertThat(order.branches().get(0).ifArm().assignments().get(0).getField()).isEqualTo("priority");
        assertThat(order.branches().get(0).ifArm().when().getValue().asInt()).isEqualTo(100);
        assertThat(entity.getDefinition()).isEqualTo(json);
    }

    @Test
    void aLegacyBranchWithNoStoredSchemaJoinsTheComputedSchemas() {
        String json = """
                {"when":[{"binding":"$customer","type":"Customer","conditions":null}],"then":[],
                 "branches":[{"when":{"field":"$customer.age","operator":">=","value":65},
                   "then":[{"binding":"$customer","assignments":[{"field":"discount","value":0.2}]}]}]}
                """;
        AuthoredRule entity = rule("schema-from-branch", json);
        when(repository.findByCode("schema-from-branch")).thenReturn(Optional.of(entity));
        when(typeService.catalog()).thenReturn(catalog());

        RuleDefinition definition = service.get("schema-from-branch").versions().get(0).definition();

        assertThat(definition.inputSchema()).containsExactly(customer("age"));
        assertThat(definition.outputSchema()).containsExactly(customer("age", "discount"));
        assertThat(entity.getDefinition()).isEqualTo(json);
    }

    @Test
    void aTopLevelBranchListThatIsNotAnIndependentIfDoesNotEraseTheThenBlock() {
        String empty = """
                {"inputSchema":[],"outputSchema":[],
                 "when":[{"binding":"$customer","type":"Customer","conditions":{"field":"age","operator":">=","value":65}}],
                 "then":[{"binding":"$customer","assignments":[{"field":"segment","value":"SENIOR"}]}],
                 "branches":[]}
                """;
        String junk = """
                {"inputSchema":[],"outputSchema":[],
                 "when":[{"binding":"$customer","type":"Customer","conditions":{"field":"age","operator":">=","value":65}}],
                 "then":[{"binding":"$customer","assignments":[{"field":"segment","value":"SENIOR"}]}],
                 "branches":{}}
                """;
        AuthoredRule emptyRule = rule("empty-branches", empty);
        AuthoredRule junkRule = rule("junk-branches", junk);
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(emptyRule, junkRule));

        List<RuleResponse> responses = service.list();

        for (RuleResponse response : responses) {
            ThenBlock block = response.versions().get(0).definition().then().get(0);
            assertThat(block.branches()).isNull();
            assertThat(block.assignments().get(0).getValue().textValue()).isEqualTo("SENIOR");
        }
        assertThat(emptyRule.getDefinition()).isEqualTo(empty);
        assertThat(junkRule.getDefinition()).isEqualTo(junk);
    }

    private static AuthoredRule rule(String code, String json) {
        AuthoredRule entity = new AuthoredRule();
        entity.setCode(code);
        entity.setName("Stored rule");
        entity.setDefinition(json);
        entity.setDrl("package rules.dynamic\n");
        entity.setCreatedAt(Instant.parse("2026-10-17T00:00:00Z"));
        return entity;
    }

    private static FactDeclaration customer(String... names) {
        List<FieldDeclaration> fields = new java.util.ArrayList<>();
        Map<String, String> types = Map.of("age", "int", "discount", "double", "segment", "String", "name", "String");
        for (String name : names) {
            fields.add(new FieldDeclaration(name, types.get(name)));
        }
        return new FactDeclaration("Customer", fields);
    }

    private static Map<String, FactDeclaration> catalog() {
        Map<String, FactDeclaration> types = new LinkedHashMap<>();
        types.put("Customer", new FactDeclaration("Customer", List.of(
                new FieldDeclaration("name", "String"),
                new FieldDeclaration("age", "int"),
                new FieldDeclaration("discount", "double"),
                new FieldDeclaration("segment", "String"))));
        return types;
    }
}
