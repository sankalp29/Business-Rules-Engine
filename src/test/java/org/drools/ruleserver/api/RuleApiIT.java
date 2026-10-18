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
package org.drools.ruleserver.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.drools.ruleserver.persistence.AuthoredRule;
import org.drools.ruleserver.persistence.AuthoredRuleRepository;
import org.drools.ruleserver.persistence.RuleVersionTransition;
import org.drools.ruleserver.persistence.RuleVersionTransitionRepository;
import org.drools.ruleserver.rules.EffectiveWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(RuleApiIT.FixedClockConfig.class)
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class RuleApiIT {

    static final Instant OPENING = LocalDate.of(2026, 10, 18).atStartOfDay(EffectiveWindow.IST).toInstant();

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthoredRuleRepository repository;

    @Autowired
    private RuleVersionTransitionRepository transitions;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(OPENING);
    }

    @Test
    void createStoresTheRuleAndExecuteReturnsReasoning() throws Exception {
        postType("examples/create-address-type.json", "/api/types/Address");
        postType("examples/create-customer-type.json", "/api/types/Customer");

        long before = repository.count();
        MvcResult createdResult = mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Files.readString(Path.of("examples/create-senior-discount.json"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/rules/senior-discount-without-types"))
                .andReturn();
        JsonNode created = objectMapper.readTree(createdResult.getResponse().getContentAsString());
        long id = created.get("id").asLong();

        assertThat(id).isPositive();
        assertThat(created.get("code").asText()).isEqualTo("senior-discount-without-types");
        assertThat(created.get("name").asText()).isEqualTo("Apply senior discount");
        assertThat(created.get("description").asText()).isEqualTo("Customers aged 65 or older receive a senior discount");
        assertThat(created.get("definition").has("code")).isFalse();
        assertThat(created.get("definition").has("name")).isFalse();
        assertThat(created.get("definition").has("description")).isFalse();
        assertThat(created.get("drl").asText()).contains("rule \"Apply senior discount\"");
        assertThat(created.get("drl").asText()).contains("declare Address");
        assertThat(created.get("drl").asText()).contains("address : Address");
        assertThat(created.get("drl").asText()).contains("setDiscount( 0.2 )");
        assertThat(created.get("activeVersion").asText()).isEqualTo("v1");
        assertThat(created.get("versions")).hasSize(1);
        assertThat(created.get("versions").get(0).get("version").asText()).isEqualTo("v1");
        assertThat(created.get("versions").get(0).get("versionNumber").asInt()).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(before + 1);
        AuthoredRule stored = repository.findById(id).orElseThrow();
        assertThat(stored.getCode()).isEqualTo("senior-discount-without-types");
        assertThat(stored.getName()).isEqualTo("Apply senior discount");
        assertThat(stored.getDefinition()).doesNotContain("\"facts\"");
        assertThat(stored.getDefinition()).doesNotContain("senior-discount-without-types");
        assertThat(stored.getDefinition()).doesNotContain("Apply senior discount");
        assertThat(stored.getDefinition()).doesNotContain("address");
        assertThat(stored.getDefinition()).contains("\"inputSchema\"");
        assertThat(stored.getDefinition()).doesNotContain("effectiveFrom");
        assertThat(stored.getDrl()).contains("declare Customer");
        assertThat(stored.getDrl()).contains("address : Address");
        approve("senior-discount-without-types", "v1");

        mockMvc.perform(get("/api/rules/senior-discount-without-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.code").value("senior-discount-without-types"))
                .andExpect(jsonPath("$.name").value("Apply senior discount"))
                .andExpect(jsonPath("$.definition.code").doesNotExist())
                .andExpect(jsonPath("$.definition.name").doesNotExist())
                .andExpect(jsonPath("$.definition.description").doesNotExist())
                .andExpect(jsonPath("$.definition.facts").doesNotExist())
                .andExpect(jsonPath("$.definition.inputSchema[0].type").value("Customer"))
                .andExpect(jsonPath("$.definition.inputSchema[0].fields[0].name").value("age"))
                .andExpect(jsonPath("$.definition.inputSchema[0].fields.length()").value(1))
                .andExpect(jsonPath("$.definition.outputSchema[0].fields[0].name").value("age"))
                .andExpect(jsonPath("$.definition.outputSchema[0].fields[1].name").value("discount"))
                .andExpect(jsonPath("$.definition.outputSchema[0].fields[2].name").value("segment"));
        mockMvc.perform(get("/api/rules/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("senior-discount-without-types"));

        MvcResult list = mockMvc.perform(get("/api/rules")).andExpect(status().isOk()).andReturn();
        JsonNode rules = objectMapper.readTree(list.getResponse().getContentAsString());
        assertThat(rules).anyMatch(rule -> rule.get("id").asLong() == id);

        JsonNode fired = postJson(
                "/api/rules/senior-discount-without-types/execute",
                Files.readString(Path.of("examples/execute-senior-discount.json")),
                200);
        assertThat(fired.get("fired").asBoolean()).isTrue();
        assertThat(fired.get("ruleId").asLong()).isEqualTo(id);
        assertThat(fired.get("code").asText()).isEqualTo("senior-discount-without-types");
        assertThat(fired.get("version").asText()).isEqualTo("v1");
        assertThat(fired.get("reasoning").asText()).isEqualTo(
                "Rule Apply senior discount fired. Set Customer.discount to 0.2 (was 0.0). Set Customer.segment to SENIOR (was STANDARD).");
        assertThat(fired.get("reasoning").asText()).doesNotContain("\\", "/");
        assertThat(fired.has("ruleName")).isFalse();
        assertThat(fired.has("fireCount")).isFalse();
        assertThat(fired.has("firedRules")).isFalse();
        assertThat(fired.get("inputData").get(0).get("type").asText()).isEqualTo("Customer");
        assertThat(fired.get("outputData").get(0).get("type").asText()).isEqualTo("Customer");
        assertThat(fired.get("inputData").get(0).get("values").get("segment").asText()).isEqualTo("STANDARD");
        assertThat(fired.get("inputData").get(0).get("values").get("address").get("city").asText()).isEqualTo("London");
        assertThat(fired.get("outputData").get(0).get("values").get("segment").asText()).isEqualTo("SENIOR");
        assertThat(fired.get("outputData").get(0).get("values").get("address").get("city").asText()).isEqualTo("London");
        assertThat(fired.get("modifiedData")).hasSize(1);
        assertThat(fired.get("modifiedData").get(0).get("type").asText()).isEqualTo("Customer");
        assertThat(fired.get("modifiedData").get(0).get("values").has("address")).isFalse();
        assertThat(fired.get("modifiedData").get(0).get("values").get("discount").get("previousValue").asDouble()).isEqualTo(0.0);
        assertThat(fired.get("modifiedData").get(0).get("values").get("discount").get("newValue").asDouble()).isEqualTo(0.2);
        assertThat(fired.get("modifiedData").get(0).get("values").get("segment").get("newValue").asText()).isEqualTo("SENIOR");

        JsonNode missed = postJson(
                "/api/rules/" + id + "/execute",
                Files.readString(Path.of("examples/execute-no-match.json")),
                200);
        assertThat(missed.get("fired").asBoolean()).isFalse();
        assertThat(missed.get("code").asText()).isEqualTo("senior-discount-without-types");
        assertThat(missed.get("reasoning").asText()).isEqualTo(
                "Rule Apply senior discount did not fire. No fields were changed.");
        assertThat(missed.get("inputData").get(0).get("type").asText()).isEqualTo("Customer");
        assertThat(missed.get("outputData").get(0).get("type").asText()).isEqualTo("Customer");
        assertThat(missed.get("modifiedData").get(0).get("type").asText()).isEqualTo("Customer");
        assertThat(missed.get("inputData").get(0).get("values").get("segment").asText()).isEqualTo("STANDARD");
        assertThat(missed.get("outputData").get(0).get("values").get("segment").asText()).isEqualTo("STANDARD");
        assertThat(missed.get("modifiedData").get(0).get("values")).isEmpty();

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Files.readString(Path.of("examples/create-move-city.json"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/rules/move-city"));
        approve("move-city", "v1");
        JsonNode moved = postJson(
                "/api/rules/move-city/execute",
                Files.readString(Path.of("examples/execute-move-city.json")),
                200);
        assertThat(moved.get("fired").asBoolean()).isTrue();
        assertThat(moved.get("reasoning").asText()).isEqualTo(
                "Rule Move customer to Pune fired. Set Customer.address.city to Pune (was London).");
        assertThat(moved.get("outputData").get(0).get("values").get("address").get("city").asText()).isEqualTo("Pune");
        assertThat(moved.get("modifiedData").get(0).get("values").get("address").get("city").get("previousValue").asText()).isEqualTo("London");
        assertThat(moved.get("modifiedData").get(0).get("values").get("address").get("city").get("newValue").asText()).isEqualTo("Pune");

        mockMvc.perform(post("/api/types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Files.readString(Path.of("examples/create-order-type.json"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Files.readString(Path.of("examples/create-large-adult-order.json"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/rules/large-adult-order-without-types"));
        approve("large-adult-order-without-types", "v1");
        JsonNode approved = postJson(
                "/api/rules/large-adult-order-without-types/execute",
                Files.readString(Path.of("examples/execute-large-adult-order.json")),
                200);
        assertThat(approved.get("fired").asBoolean()).isTrue();
        assertThat(approved.get("outputData").get(1).get("values").get("approved").asBoolean()).isTrue();
        assertThat(approved.get("outputData").get(1).get("values").get("priority").asText()).isEqualTo("HIGH");

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Files.readString(Path.of("examples/create-priority-customer.json"))))
                .andExpect(status().isCreated());
        approve("priority-customer-without-types", "v1");
        JsonNode priority = postJson(
                "/api/rules/priority-customer-without-types/execute",
                Files.readString(Path.of("examples/execute-priority-customer.json")),
                200);
        assertThat(priority.get("fired").asBoolean()).isTrue();
        assertThat(priority.get("outputData").get(0).get("values").get("segment").asText()).isEqualTo("PRIORITY");
        JsonNode priorityMiss = postJson(
                "/api/rules/priority-customer-without-types/execute",
                Files.readString(Path.of("examples/execute-no-match.json")),
                200);
        assertThat(priorityMiss.get("fired").asBoolean()).isFalse();

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"bad-segment","name":"Bad segment","facts":[{"type":"Customer"}],
                                 "when":[{"binding":"$c","type":"Customer","conditions":{"field":"segment","operator":">=","value":65}}],
                                 "then":[{"binding":"$c","assignments":[{"field":"segment","value":"SENIOR"}]}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("not allowed for String")));

        assertThat(postJson(
                "/api/rules/senior-discount-without-types/execute",
                "{\"facts\":[{\"type\":\"Customer\",\"values\":{\"age\":\"seventy\"}}]}",
                400).get("details").toString()).contains("must be a JSON number");
        assertThat(postJson(
                "/api/rules/senior-discount-without-types/execute",
                "{\"facts\":[{\"type\":\"Customer\",\"values\":{\"address\":{\"city\":12}}}]}",
                400).get("details").toString()).contains("must be a JSON string");
        assertThat(postJson(
                "/api/rules/senior-discount-without-types/execute",
                "{\"facts\":[{\"type\":\"Customer\",\"values\":{\"salary\":1}}]}",
                400).get("details").toString()).contains("is not a field of Customer");

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"facts-ignored","name":"Facts ignored",
                                 "facts":[{"type":"Customer","fields":[{"name":"bogus","type":"String"}]}],
                                 "inputSchema":[{"type":"Nope","fields":[{"name":"nope","type":"String"}]}],
                                 "outputSchema":[{"type":"Nope","fields":[{"name":"nope","type":"String"}]}],
                                 "effectiveFrom":"2026-10-18","effectiveUntil":"2099-12-31",
                                 "when":[{"binding":"$c","type":"Customer","conditions":{"field":"age","operator":">=","value":1}}],
                                 "then":[{"binding":"$c","assignments":[{"field":"segment","value":"OK"}]}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.definition.facts").doesNotExist())
                .andExpect(jsonPath("$.definition.inputSchema[0].type").value("Customer"))
                .andExpect(jsonPath("$.definition.inputSchema[0].fields[0].name").value("age"))
                .andExpect(jsonPath("$.definition.inputSchema[0].fields.length()").value(1))
                .andExpect(jsonPath("$.definition.outputSchema[0].fields[0].name").value("age"))
                .andExpect(jsonPath("$.definition.outputSchema[0].fields[1].name").value("segment"))
                .andExpect(jsonPath("$.definition.outputSchema[0].fields.length()").value(2))
                .andExpect(jsonPath("$.drl").value(org.hamcrest.Matchers.containsString("address : Address")))
                .andExpect(jsonPath("$.drl").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("bogus"))));

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Files.readString(Path.of("examples/create-senior-discount.json"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("code senior-discount-without-types is already used"));
    }

    @Test
    void aRuleStoredWithoutACodeStillExecutesById() throws Exception {
        AuthoredRule legacy = new AuthoredRule();
        legacy.setName("Legacy rule");
        legacy.setDescription("Stored before codes existed");
        legacy.setDefinition("""
                {"name":"Legacy rule","description":"Stored before codes existed","facts":[{"type":"Person","fields":[{"name":"age","type":"int"}]}],"when":[{"binding":"$p","type":"Person","conditions":{"field":"age","operator":">=","value":1}}],"then":[{"binding":"$p","assignments":[{"field":"age","value":2}]}]}""");
        legacy.setDrl("""
                package rules.dynamic

                dialect "java"

                declare Person
                    age : int
                end

                rule "Legacy rule"
                    no-loop true
                when
                    $p : Person( age >= 1 )
                then
                    modify( $p ) { setAge( 2 ) };
                end
                """);
        long id = repository.save(legacy).getId();

        mockMvc.perform(get("/api/rules/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.nullValue()));

        JsonNode fired = postJson(
                "/api/rules/" + id + "/execute",
                "{\"facts\":[{\"type\":\"Person\",\"values\":{\"age\":5}}]}",
                200);
        assertThat(fired.get("fired").asBoolean()).isTrue();
        assertThat(fired.get("ruleId").asLong()).isEqualTo(id);
        assertThat(fired.get("code").isNull()).isTrue();
        assertThat(fired.get("version").asText()).isEqualTo("v1");
        assertThat(fired.get("outputData").get(0).get("values").get("age").asInt()).isEqualTo(2);
        assertThat(fired.get("reasoning").asText()).isEqualTo("Rule Legacy rule fired. Set Person.age to 2 (was 5).");

        mockMvc.perform(put("/api/rules/" + id + "/versions/v1/dates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dates("2026-10-18", "2099-12-31")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("Logic and dates can be changed only while the version is in draft."));

        reject(String.valueOf(id), "v1", "Give the original version a real end.");

        mockMvc.perform(put("/api/rules/" + id + "/versions/v1/dates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dates("2026-10-18", "2099-12-31")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("effectiveFrom cannot change because it is already before the current time"));

        mockMvc.perform(put("/api/rules/" + id + "/versions/v1/dates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dates("1970-01-01T00:00:00Z", "2099-12-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("v1"))
                .andExpect(jsonPath("$.effectiveFrom").value("1970-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.effectiveUntil").value("2099-12-31T18:29:00Z"))
                .andExpect(jsonPath("$.segments.length()").value(1))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.definition.then[0].assignments[0].value").value(2));

        AuthoredRule storedLegacy = repository.findById(id).orElseThrow();
        assertThat(storedLegacy.getDefinition()).contains("\"facts\"");
        assertThat(storedLegacy.getDescription()).isEqualTo("Stored before codes existed");
        approve(String.valueOf(id), "v1");

        JsonNode stillLegacy = postJson(
                "/api/rules/" + id + "/execute",
                "{\"facts\":[{\"type\":\"Person\",\"values\":{\"age\":5}}]}",
                200);
        assertThat(stillLegacy.get("version").asText()).isEqualTo("v1");
        assertThat(stillLegacy.get("fired").asBoolean()).isTrue();
    }

    @Test
    void rejectsAnInvalidRuleAndAnUnknownId() throws Exception {
        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad\",\"facts\":[],\"when\":[],\"then\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid rule"));

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"missing-type","name":"Missing","facts":[{"type":"Missing"}],
                                 "when":[{"binding":"$m","type":"Missing","conditions":[]}],
                                 "then":[{"binding":"$m","assignments":[{"field":"age","value":1}]}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid rule"))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("is not defined")));

        mockMvc.perform(post("/api/types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Person\",\"fields\":[{\"name\":\"age\",\"type\":\"int\"}]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"unknown-field","name":"Unknown field","facts":[{"type":"Person"}],
                                 "when":[{"binding":"$c","type":"Person","conditions":{"field":"salary","operator":">","value":1}}],
                                 "then":[{"binding":"$c","assignments":[{"field":"age","value":1}]}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("salary is not a field of Person")));

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"quoted-condition","name":"Quoted","facts":[{"type":"Person"}],
                                 "when":[{"binding":"$c","type":"Person","conditions":"age >= 1"}],
                                 "then":[{"binding":"$c","assignments":[{"field":"age","value":1}]}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("must be an object")));

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"broken-age","name":"broken","facts":[{"type":"Person"}],
                                 "effectiveFrom":"2026-10-18","effectiveUntil":"2099-12-31",
                                 "when":[{"binding":"$c","type":"Person","conditions":{"field":"age","operator":">=","value":1}}],
                                 "then":[{"binding":"$c","assignments":[{"field":"age","expression":"noSuchMethod()"}]}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Rule compilation failed"));

        mockMvc.perform(post("/api/types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Person\",\"fields\":[{\"name\":\"age\",\"type\":\"int\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("already defined")));

        mockMvc.perform(get("/api/rules/missing-rule"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]").value("No rule exists with code or id missing-rule"));

        mockMvc.perform(post("/api/rules/999999/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"facts\":[]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Rule not found"))
                .andExpect(jsonPath("$.details[0]").value("No rule exists with code or id 999999"));
    }

    @Test
    void versionsKeepTheSameCodeAndNameAndExecutionUsesTheActiveWindow() throws Exception {
        mockMvc.perform(post("/api/types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"TierHolder","fields":[{"name":"age","type":"int"},{"name":"segment","type":"String"}]}
                                """))
                .andExpect(status().isCreated());

        LocalDate today = LocalDate.of(2026, 10, 18);
        String currentFrom = today.toString();
        String currentUntil = today.plusYears(1).minusDays(1).toString();
        String futureFrom = today.plusYears(1).toString();
        String futureUntil = today.plusYears(2).minusDays(1).toString();
        String laterFrom = today.plusYears(2).toString();
        String laterUntil = today.plusYears(3).minusDays(1).toString();

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tier("too-early", "Too early", "Starts in the past", "NO", "2020-01-01", "2099-12-31")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("effectiveFrom cannot be before the current time"));

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tier("too-precise", "Too precise", "Seconds", "NO",
                                "2026-10-18T00:00:30+05:30", "2027-10-18T00:00:00+05:30")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("effectiveFrom must be a whole minute"));

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tier("same-instant", "Same instant", "End equals start", "NO",
                                "2026-10-18T00:00:00+05:30", "2026-10-18T00:00:00+05:30")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("effectiveUntil must be after effectiveFrom"));

        MvcResult created = mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tier("tier-rule", "Assign tier", "Current tier", "NOW", currentFrom, currentUntil)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/rules/tier-rule"))
                .andReturn();
        JsonNode rule = objectMapper.readTree(created.getResponse().getContentAsString());
        assertThat(rule.get("activeVersion").asText()).isEqualTo("v1");
        assertThat(rule.get("versions").get(0).get("version").asText()).isEqualTo("v1");
        assertThat(rule.get("versions").get(0).get("active").asBoolean()).isTrue();
        assertThat(rule.get("name").asText()).isEqualTo("Assign tier");
        assertThat(rule.get("versions").get(0).get("effectiveFrom").asText()).isEqualTo("2026-10-17T18:30:00Z");
        assertThat(rule.get("versions").get(0).get("stage").asText()).isEqualTo("DRAFT");
        approve("tier-rule", "v1");

        JsonNode now = postJson("/api/rules/tier-rule/execute", tierFact(40), 200);
        assertThat(now.get("version").asText()).isEqualTo("v1");
        assertThat(now.get("fired").asBoolean()).isTrue();
        assertThat(now.get("outputData").get(0).get("values").get("segment").asText()).isEqualTo("NOW");

        mockMvc.perform(post("/api/rules/tier-rule/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tier(null, null, "Future tier", "NEXT", futureFrom, futureUntil)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/rules/tier-rule/versions/v2"))
                .andExpect(jsonPath("$.version").value("v2"))
                .andExpect(jsonPath("$.versionNumber").value(2))
                .andExpect(jsonPath("$.description").value("Future tier"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.definition.then[0].assignments[0].value").value("NEXT"));

        mockMvc.perform(get("/api/rules/tier-rule/versions/v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("v2"))
                .andExpect(jsonPath("$.definition.when[0].conditions.value").value(1));
        mockMvc.perform(get("/api/rules/tier-rule/versions/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("v2"));

        JsonNode stillCurrent = postJson("/api/rules/tier-rule/execute", tierFact(40), 200);
        assertThat(stillCurrent.get("version").asText()).isEqualTo("v1");
        assertThat(stillCurrent.get("outputData").get(0).get("values").get("segment").asText()).isEqualTo("NOW");

        mockMvc.perform(post("/api/rules/tier-rule/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tier(null, null, "Overlap", "NO", currentFrom, currentUntil)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("v1 cannot be adjusted")))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("no effective dates left")));

        mockMvc.perform(post("/api/rules/tier-rule/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tier(null, "A different name", "Renamed", "NO", laterFrom, laterUntil)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("name stays the same on every version of a rule"));

        mockMvc.perform(post("/api/rules/tier-rule/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tier("other-code", null, "Recoded", "NO", laterFrom, laterUntil)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("code stays the same on every version of a rule"));

        mockMvc.perform(post("/api/rules/tier-rule/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tier("tier-rule", "Assign tier", "Later tier", "LATER", laterFrom, laterUntil)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("v3"))
                .andExpect(jsonPath("$.active").value(false));

        JsonNode stillNow = postJson("/api/rules/tier-rule/execute", tierFact(40), 200);
        assertThat(stillNow.get("version").asText()).isEqualTo("v1");
        assertThat(stillNow.get("outputData").get(0).get("values").get("segment").asText()).isEqualTo("NOW");

        clock.set(LocalDate.of(2026, 10, 19).atStartOfDay(EffectiveWindow.IST).toInstant());
        mockMvc.perform(post("/api/rules/tier-rule/versions/v1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"comment\":\"Shorten the window.\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("An approved version cannot be sent back to draft."));
        mockMvc.perform(put("/api/rules/tier-rule/versions/v1/dates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dates("2026-10-19", currentUntil)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("Logic and dates can be changed only while the version is in draft."));

        mockMvc.perform(put("/api/rules/tier-rule/versions/v2/dates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dates("2027-06-01", futureUntil)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("v2"))
                .andExpect(jsonPath("$.effectiveFrom").value("2027-05-31T18:30:00Z"));
        mockMvc.perform(get("/api/rules/tier-rule/versions/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveFrom").value("2026-10-17T18:30:00Z"))
                .andExpect(jsonPath("$.effectiveUntil").value("2027-05-31T18:29:00Z"))
                .andExpect(jsonPath("$.stage").value("APPROVED"));

        mockMvc.perform(put("/api/rules/tier-rule/versions/v2/dates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dates("2027-11-01", "2028-01-01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(put("/api/rules/tier-rule/versions/v2/dates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dates("2026-10-18", "2027-01-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("effectiveFrom cannot be before the current time"));

        clock.set(LocalDate.of(2029, 10, 18).atStartOfDay(EffectiveWindow.IST).toInstant());

        mockMvc.perform(put("/api/rules/tier-rule/versions/v2/dates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dates("2027-11-01", "2028-06-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("effectiveUntil cannot change because it is already before the current time"));

        mockMvc.perform(put("/api/rules/tier-rule/versions/v2/dates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dates("2027-11-01", "2028-01-01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        JsonNode none = postJson("/api/rules/tier-rule/execute", tierFact(40), 409);
        assertThat(none.get("details").get(0).asText()).contains("No version of tier-rule is active");

        mockMvc.perform(get("/api/rules/tier-rule/versions/v9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]").value("No version v9 exists for rule tier-rule"));
        mockMvc.perform(get("/api/rules/tier-rule/versions/nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("version must look like v1"));

        mockMvc.perform(get("/api/rules/tier-rule/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].version").value("v1"))
                .andExpect(jsonPath("$[1].version").value("v2"))
                .andExpect(jsonPath("$[2].version").value("v3"))
                .andExpect(jsonPath("$[1].definition.then[0].assignments[0].value").value("NEXT"));
    }

    @Test
    void aFlashVersionSplitsTheCoveredVersionAndThenThatVersionResumes() throws Exception {
        mockMvc.perform(post("/api/types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"FestivalHolder","fields":[{"name":"age","type":"int"},{"name":"segment","type":"String"}]}
                                """))
                .andExpect(status().isCreated());

        clock.set(LocalDate.of(2026, 9, 15).atStartOfDay(EffectiveWindow.IST).toInstant());
        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(festival("SEP", "2026-09-15", "2026-09-30")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/rules/festival-rule/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(festival("FESTIVAL", "2026-10-01", "2026-10-10")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("v2"));

        clock.set(LocalDate.of(2026, 9, 20).atStartOfDay(EffectiveWindow.IST).toInstant());
        mockMvc.perform(put("/api/rules/festival-rule/versions/v1/dates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dates("2026-09-15", "2026-09-19")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("effectiveUntil cannot be before the current time"));

        mockMvc.perform(post("/api/rules/festival-rule/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(festival("JAYANTI", "2026-10-02", "2026-10-02")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("v3"))
                .andExpect(jsonPath("$.segments.length()").value(1));

        mockMvc.perform(get("/api/rules/festival-rule/versions/v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(2))
                .andExpect(jsonPath("$.segments[0].effectiveFrom").value("2026-09-30T18:30:00Z"))
                .andExpect(jsonPath("$.segments[0].effectiveUntil").value("2026-10-01T18:29:00Z"))
                .andExpect(jsonPath("$.segments[1].effectiveFrom").value("2026-10-02T18:30:00Z"))
                .andExpect(jsonPath("$.segments[1].effectiveUntil").value("2026-10-10T18:29:00Z"))
                .andExpect(jsonPath("$.effectiveFrom").value("2026-09-30T18:30:00Z"))
                .andExpect(jsonPath("$.effectiveUntil").value("2026-10-10T18:29:00Z"));

        mockMvc.perform(put("/api/rules/festival-rule/versions/v3/dates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(segments("2026-10-02", "2026-10-02", "2026-10-05", "2026-10-05")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("v3"))
                .andExpect(jsonPath("$.segments.length()").value(2))
                .andExpect(jsonPath("$.segments[0].effectiveFrom").value("2026-10-01T18:30:00Z"))
                .andExpect(jsonPath("$.segments[0].effectiveUntil").value("2026-10-02T18:29:00Z"))
                .andExpect(jsonPath("$.segments[1].effectiveFrom").value("2026-10-04T18:30:00Z"))
                .andExpect(jsonPath("$.segments[1].effectiveUntil").value("2026-10-05T18:29:00Z"))
                .andExpect(jsonPath("$.definition.then[0].assignments[0].value").value("JAYANTI"));

        mockMvc.perform(get("/api/rules/festival-rule/versions/v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(3))
                .andExpect(jsonPath("$.segments[0].effectiveUntil").value("2026-10-01T18:29:00Z"))
                .andExpect(jsonPath("$.segments[1].effectiveFrom").value("2026-10-02T18:30:00Z"))
                .andExpect(jsonPath("$.segments[1].effectiveUntil").value("2026-10-04T18:29:00Z"))
                .andExpect(jsonPath("$.segments[2].effectiveFrom").value("2026-10-05T18:30:00Z"))
                .andExpect(jsonPath("$.segments[2].effectiveUntil").value("2026-10-10T18:29:00Z"));

        approve("festival-rule", "v2");
        approve("festival-rule", "v3");
        clock.set(LocalDate.of(2026, 10, 1).atTime(10, 0).atZone(EffectiveWindow.IST).toInstant());
        assertThat(postJson("/api/rules/festival-rule/execute", festivalFact(), 200).get("version").asText()).isEqualTo("v2");

        clock.set(LocalDate.of(2026, 10, 2).atTime(10, 15, 45).atZone(EffectiveWindow.IST).toInstant());
        JsonNode jayanti = postJson("/api/rules/festival-rule/execute", festivalFact(), 200);
        assertThat(jayanti.get("version").asText()).isEqualTo("v3");
        assertThat(jayanti.get("outputData").get(0).get("values").get("segment").asText()).isEqualTo("JAYANTI");

        clock.set(LocalDate.of(2026, 10, 2).atTime(23, 59, 30).atZone(EffectiveWindow.IST).toInstant());
        assertThat(postJson("/api/rules/festival-rule/execute", festivalFact(), 200).get("version").asText()).isEqualTo("v3");

        clock.set(LocalDate.of(2026, 10, 3).atStartOfDay(EffectiveWindow.IST).toInstant());
        JsonNode resumed = postJson("/api/rules/festival-rule/execute", festivalFact(), 200);
        assertThat(resumed.get("version").asText()).isEqualTo("v2");
        assertThat(resumed.get("outputData").get(0).get("values").get("segment").asText()).isEqualTo("FESTIVAL");

        mockMvc.perform(post("/api/rules/festival-rule/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(festival("SECOND", "2026-10-08", "2026-10-08")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("v4"));
        approve("festival-rule", "v4");

        mockMvc.perform(get("/api/rules/festival-rule/versions/v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(4))
                .andExpect(jsonPath("$.segments[1].effectiveUntil").value("2026-10-04T18:29:00Z"))
                .andExpect(jsonPath("$.segments[2].effectiveFrom").value("2026-10-05T18:30:00Z"))
                .andExpect(jsonPath("$.segments[2].effectiveUntil").value("2026-10-07T18:29:00Z"))
                .andExpect(jsonPath("$.segments[3].effectiveFrom").value("2026-10-08T18:30:00Z"));

        clock.set(LocalDate.of(2026, 10, 5).atTime(12, 0).atZone(EffectiveWindow.IST).toInstant());
        JsonNode secondFlash = postJson("/api/rules/festival-rule/execute", festivalFact(), 200);
        assertThat(secondFlash.get("version").asText()).isEqualTo("v3");
        assertThat(secondFlash.get("outputData").get(0).get("values").get("segment").asText()).isEqualTo("JAYANTI");
        clock.set(LocalDate.of(2026, 10, 6).atStartOfDay(EffectiveWindow.IST).toInstant());
        assertThat(postJson("/api/rules/festival-rule/execute", festivalFact(), 200).get("version").asText()).isEqualTo("v2");
        clock.set(LocalDate.of(2026, 10, 8).atTime(12, 0).atZone(EffectiveWindow.IST).toInstant());
        assertThat(postJson("/api/rules/festival-rule/execute", festivalFact(), 200).get("version").asText()).isEqualTo("v4");
        clock.set(LocalDate.of(2026, 10, 9).atStartOfDay(EffectiveWindow.IST).toInstant());
        assertThat(postJson("/api/rules/festival-rule/execute", festivalFact(), 200).get("version").asText()).isEqualTo("v2");

        clock.set(LocalDate.of(2026, 10, 4).atStartOfDay(EffectiveWindow.IST).toInstant());
        mockMvc.perform(post("/api/rules/festival-rule/versions/v3/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"comment\":\"The flash starts too early.\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("An approved version cannot be sent back to draft."));
        mockMvc.perform(put("/api/rules/festival-rule/versions/v3/dates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dates("2026-10-03", "2026-10-03")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("Logic and dates can be changed only while the version is in draft."));
    }

    @Test
    void changingDatesAfterAReturnToDraftAdjustsTheOverlappedVersion() throws Exception {
        mockMvc.perform(post("/api/types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"OverlapHolder","fields":[{"name":"age","type":"int"},{"name":"segment","type":"String"}]}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/approval-workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stages\":[\"Checker\"]}"))
                .andExpect(status().isCreated());

        String logic = """
                "when":[{"binding":"$holder","type":"OverlapHolder","conditions":{"field":"age","operator":">=","value":1}}],
                "then":[{"binding":"$holder","assignments":[{"field":"segment","value":"%s"}]}]
                """;
        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"overlap-adjust","name":"Overlap adjust","description":"First",
                                 "effectiveFrom":"2026-11-01","effectiveUntil":"2026-12-31",
                                 %s}
                                """.formatted(logic.formatted("FIRST"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versions[0].stage").value("DRAFT"));
        mockMvc.perform(post("/api/rules/overlap-adjust/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Second","effectiveFrom":"2027-01-01","effectiveUntil":"2027-03-31",
                                 %s}
                                """.formatted(logic.formatted("SECOND"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("v2"));
        approve("overlap-adjust", "v2");

        mockMvc.perform(post("/api/rules/overlap-adjust/versions/v1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("Checker"));
        reject("overlap-adjust", "v1", "Extend it into January.");

        mockMvc.perform(put("/api/rules/overlap-adjust/versions/v1/dates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dates("2026-11-01", "2027-01-20")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("v1"))
                .andExpect(jsonPath("$.stage").value("DRAFT"))
                .andExpect(jsonPath("$.effectiveUntil").value("2027-01-20T18:29:00Z"));
        mockMvc.perform(get("/api/rules/overlap-adjust/versions/v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("APPROVED"))
                .andExpect(jsonPath("$.effectiveFrom").value("2027-01-20T18:30:00Z"))
                .andExpect(jsonPath("$.effectiveUntil").value("2027-03-31T18:29:00Z"));

        mockMvc.perform(put("/api/rules/overlap-adjust/versions/v1/dates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(segments("2026-11-01", "2027-01-20", "2027-02-01", "2027-02-01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(2));
        mockMvc.perform(get("/api/rules/overlap-adjust/versions/v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(2))
                .andExpect(jsonPath("$.segments[0].effectiveUntil").value("2027-01-31T18:29:00Z"))
                .andExpect(jsonPath("$.segments[1].effectiveFrom").value("2027-02-01T18:30:00Z"))
                .andExpect(jsonPath("$.segments[1].effectiveUntil").value("2027-03-31T18:29:00Z"));

        approve("overlap-adjust", "v1");
        mockMvc.perform(post("/api/rules/overlap-adjust/versions/v1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("This version is already approved."));
        mockMvc.perform(post("/api/rules/overlap-adjust/versions/v1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"comment\":\"Approved is the last stage\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("An approved version cannot be sent back to draft."));
    }

    @Test
    void aVersionCanDeclareSeveralSegmentsAndAnotherVersionCanFillTheGap() throws Exception {
        mockMvc.perform(post("/api/types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"GapHolder","fields":[{"name":"age","type":"int"},{"name":"segment","type":"String"}]}
                                """))
                .andExpect(status().isCreated());

        clock.set(LocalDate.of(2026, 10, 18).atStartOfDay(EffectiveWindow.IST).toInstant());
        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"gap-rule","name":"Gap rule","description":"Two periods",
                                 "segments":[
                                   {"effectiveFrom":"2026-11-01","effectiveUntil":"2026-11-01"},
                                   {"effectiveFrom":"2026-11-03","effectiveUntil":"2026-11-10"}
                                 ],
                                 "when":[{"binding":"$holder","type":"GapHolder","conditions":{"field":"age","operator":">=","value":1}}],
                                 "then":[{"binding":"$holder","assignments":[{"field":"segment","value":"OUTER"}]}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versions[0].segments.length()").value(2));
        approve("gap-rule", "v1");

        mockMvc.perform(post("/api/rules/gap-rule/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"The gap","effectiveFrom":"2026-11-02","effectiveUntil":"2026-11-02",
                                 "when":[{"binding":"$holder","type":"GapHolder","conditions":{"field":"age","operator":">=","value":1}}],
                                 "then":[{"binding":"$holder","assignments":[{"field":"segment","value":"GAP"}]}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("v2"));
        approve("gap-rule", "v2");

        mockMvc.perform(get("/api/rules/gap-rule/versions/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(2));

        String fact = "{\"facts\":[{\"type\":\"GapHolder\",\"values\":{\"age\":40,\"segment\":\"NONE\"}}]}";
        clock.set(LocalDate.of(2026, 11, 1).atTime(8, 0).atZone(EffectiveWindow.IST).toInstant());
        assertThat(postJson("/api/rules/gap-rule/execute", fact, 200).get("version").asText()).isEqualTo("v1");
        clock.set(LocalDate.of(2026, 11, 2).atTime(23, 59).atZone(EffectiveWindow.IST).toInstant());
        assertThat(postJson("/api/rules/gap-rule/execute", fact, 200).get("outputData").get(0).get("values").get("segment").asText())
                .isEqualTo("GAP");
        clock.set(LocalDate.of(2026, 11, 3).atStartOfDay(EffectiveWindow.IST).toInstant());
        assertThat(postJson("/api/rules/gap-rule/execute", fact, 200).get("outputData").get(0).get("values").get("segment").asText())
                .isEqualTo("OUTER");
    }

    @Test
    void approvalFollowsTheActivePathAndARejectionStartsAgainAtDraft() throws Exception {
        mockMvc.perform(post("/api/types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"ApprovalHolder","fields":[{"name":"age","type":"int"},{"name":"segment","type":"String"}]}
                                """))
                .andExpect(status().isCreated());

        MvcResult published = mockMvc.perform(post("/api/approval-workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stages\":[\"Checker\",\"Sign-off\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.stages[0]").value("Draft"))
                .andExpect(jsonPath("$.stages[1]").value("Checker"))
                .andExpect(jsonPath("$.stages[2]").value("Sign-off"))
                .andExpect(jsonPath("$.stages[3]").value("Approved"))
                .andReturn();
        String pathVersion = objectMapper.readTree(published.getResponse().getContentAsString()).get("version").asText();

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"approval-rule","name":"Approval rule","description":"First cut",
                                 "effectiveFrom":"2026-10-18","effectiveUntil":"2026-12-31",
                                 "when":[{"binding":"$holder","type":"ApprovalHolder","conditions":{"field":"age","operator":">=","value":1}}],
                                 "then":[{"binding":"$holder","assignments":[{"field":"segment","value":"FIRST"}]}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versions[0].stage").value("DRAFT"))
                .andExpect(jsonPath("$.versions[0].editable").value(true))
                .andExpect(jsonPath("$.versions[0].workflowVersion").value(pathVersion))
                .andExpect(jsonPath("$.versions[0].stages.length()").value(4))
                .andExpect(jsonPath("$.versions[0].stages[0].status").value("current"))
                .andExpect(jsonPath("$.versions[0].stages[1].status").value("upcoming"));

        mockMvc.perform(put("/api/rules/approval-rule/versions/v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Revised while draft","salience":4,
                                 "when":[{"binding":"$holder","type":"ApprovalHolder","conditions":{"field":"age","operator":">=","value":18}}],
                                 "then":[{"binding":"$holder","assignments":[{"field":"segment","value":"REVISED"}]}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Revised while draft"))
                .andExpect(jsonPath("$.definition.salience").value(4))
                .andExpect(jsonPath("$.definition.then[0].assignments[0].value").value("REVISED"))
                .andExpect(jsonPath("$.stage").value("DRAFT"));

        mockMvc.perform(post("/api/rules/approval-rule/versions/v1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("Checker"))
                .andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.stages[0].status").value("complete"))
                .andExpect(jsonPath("$.stages[1].status").value("current"))
                .andExpect(jsonPath("$.returnedFrom").value(org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(put("/api/rules/approval-rule/versions/v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Too late",
                                 "when":[{"binding":"$holder","type":"ApprovalHolder","conditions":{"field":"age","operator":">=","value":18}}],
                                 "then":[{"binding":"$holder","assignments":[{"field":"segment","value":"NO"}]}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("Logic and dates can be changed only while the version is in draft."));
        mockMvc.perform(put("/api/rules/approval-rule/versions/v1/dates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dates("2026-10-18", "2026-11-30")))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/rules/approval-rule/versions/v1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("A comment is required when sending a version back to draft."));

        mockMvc.perform(post("/api/rules/approval-rule/versions/v1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"comment\":\"Age threshold is wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("DRAFT"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.returnedFrom.stage").value("Checker"))
                .andExpect(jsonPath("$.returnedFrom.comment").value("Age threshold is wrong"))
                .andExpect(jsonPath("$.stages[0].status").value("current"))
                .andExpect(jsonPath("$.stages[1].status").value("upcoming"))
                .andExpect(jsonPath("$.stages[2].status").value("upcoming"))
                .andExpect(jsonPath("$.stages[3].status").value("upcoming"));

        approve("approval-rule", "v1");
        mockMvc.perform(get("/api/rules/approval-rule/versions/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("APPROVED"))
                .andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.returnedFrom").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.stages[0].status").value("complete"))
                .andExpect(jsonPath("$.stages[1].status").value("complete"))
                .andExpect(jsonPath("$.stages[2].status").value("complete"))
                .andExpect(jsonPath("$.stages[3].status").value("current"));

        mockMvc.perform(post("/api/rules/approval-rule/versions/v1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("This version is already approved."));
        mockMvc.perform(post("/api/rules/approval-rule/versions/v1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"comment\":\"Too late to send it back\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("An approved version cannot be sent back to draft."));
        mockMvc.perform(get("/api/rules/approval-rule/versions/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("APPROVED"));

        JsonNode fired = postJson(
                "/api/rules/approval-rule/execute",
                "{\"facts\":[{\"type\":\"ApprovalHolder\",\"values\":{\"age\":40,\"segment\":\"NONE\"}}]}",
                200);
        assertThat(fired.get("version").asText()).isEqualTo("v1");
        assertThat(fired.get("outputData").get(0).get("values").get("segment").asText()).isEqualTo("REVISED");

        mockMvc.perform(post("/api/approval-workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stages\":[\"Risk\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.middleStages[0]").value("Risk"));

        mockMvc.perform(post("/api/rules/approval-rule/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Next path","effectiveFrom":"2027-01-01","effectiveUntil":"2027-02-01",
                                 "when":[{"binding":"$holder","type":"ApprovalHolder","conditions":{"field":"age","operator":">=","value":1}}],
                                 "then":[{"binding":"$holder","assignments":[{"field":"segment","value":"NEXT"}]}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("v2"))
                .andExpect(jsonPath("$.stage").value("DRAFT"))
                .andExpect(jsonPath("$.stages.length()").value(3))
                .andExpect(jsonPath("$.stages[1].label").value("Risk"));

        mockMvc.perform(get("/api/rules/approval-rule/versions/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowVersion").value(pathVersion))
                .andExpect(jsonPath("$.stages.length()").value(4))
                .andExpect(jsonPath("$.stages[1].label").value("Checker"))
                .andExpect(jsonPath("$.stage").value("APPROVED"));

        mockMvc.perform(post("/api/rules/approval-rule/versions/v2/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"comment\":\"It is still a draft\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0]").value("This version is already in draft."));

        mockMvc.perform(post("/api/rules/approval-rule/versions/v2/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("Risk"));
        mockMvc.perform(post("/api/rules/approval-rule/versions/v2/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"comment\":\"Risk name is unclear\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("DRAFT"))
                .andExpect(jsonPath("$.stages.length()").value(3))
                .andExpect(jsonPath("$.stages[0].status").value("current"))
                .andExpect(jsonPath("$.stages[1].status").value("upcoming"))
                .andExpect(jsonPath("$.returnedFrom.comment").value("Risk name is unclear"));

        assertThat(postJson(
                "/api/rules/approval-rule/execute",
                "{\"facts\":[{\"type\":\"ApprovalHolder\",\"values\":{\"age\":40,\"segment\":\"NONE\"}}]}",
                200).get("version").asText()).isEqualTo("v1");

        assertThat(transitions.findAll())
                .anyMatch(row -> "REJECT".equals(row.getAction()) && "Age threshold is wrong".equals(row.getComment()))
                .anyMatch(row -> "REJECT".equals(row.getAction()) && "Risk name is unclear".equals(row.getComment()));
        assertThat(transitions.findAll().stream().map(RuleVersionTransition::getRuleVersionId).distinct().count()).isGreaterThan(1);
    }

    @TestConfiguration
    static class FixedClockConfig {

        @Bean(name = "testClock")
        @Primary
        MutableClock testClock() {
            return new MutableClock(OPENING, EffectiveWindow.IST);
        }
    }

    private static String tier(String code, String name, String description, String segment, String from, String until) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        if (code != null) {
            json.append("\"code\":\"").append(code).append("\",");
        }
        if (name != null) {
            json.append("\"name\":\"").append(name).append("\",");
        }
        json.append("\"description\":\"").append(description).append("\",");
        json.append("\"effectiveFrom\":\"").append(from).append("\",");
        json.append("\"effectiveUntil\":\"").append(until).append("\",");
        json.append("""
                "when":[{"binding":"$holder","type":"TierHolder","conditions":{"field":"age","operator":">=","value":1}}],
                "then":[{"binding":"$holder","assignments":[{"field":"segment","value":"%s"}]}]}
                """.formatted(segment));
        return json.toString();
    }

    private static String dates(String from, String until) {
        return "{\"effectiveFrom\":\"" + from + "\",\"effectiveUntil\":\"" + until + "\"}";
    }

    private static String segments(String... bounds) {
        StringBuilder json = new StringBuilder("{\"segments\":[");
        for (int i = 0; i < bounds.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"effectiveFrom\":\"").append(bounds[i])
                    .append("\",\"effectiveUntil\":\"").append(bounds[i + 1]).append("\"}");
        }
        json.append("]}");
        return json.toString();
    }

    private static String tierFact(int age) {
        return "{\"facts\":[{\"type\":\"TierHolder\",\"values\":{\"age\":" + age + ",\"segment\":\"NONE\"}}]}";
    }

    private static String festival(String segment, String from, String until) {
        return """
                {"code":"festival-rule","name":"Festival rule","description":"%s",
                 "effectiveFrom":"%s","effectiveUntil":"%s",
                 "when":[{"binding":"$holder","type":"FestivalHolder","conditions":{"field":"age","operator":">=","value":1}}],
                 "then":[{"binding":"$holder","assignments":[{"field":"segment","value":"%s"}]}]}
                """.formatted(segment, from, until, segment);
    }

    private static String festivalFact() {
        return "{\"facts\":[{\"type\":\"FestivalHolder\",\"values\":{\"age\":40,\"segment\":\"NONE\"}}]}";
    }

    private void postType(String path, String location) throws Exception {
        mockMvc.perform(post("/api/types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Files.readString(Path.of(path))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", location));
    }

    private JsonNode postJson(String path, String json, int expectedStatus) throws Exception {
        ResultActions actions = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().is(expectedStatus));
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private void approve(String key, String version) throws Exception {
        for (int step = 0; step < 12; step++) {
            MvcResult current = mockMvc.perform(get("/api/rules/" + key + "/versions/" + version))
                    .andExpect(status().isOk())
                    .andReturn();
            String stage = objectMapper.readTree(current.getResponse().getContentAsString()).path("stage").asText();
            if ("APPROVED".equals(stage)) {
                return;
            }
            mockMvc.perform(post("/api/rules/" + key + "/versions/" + version + "/decision")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"APPROVE\"}"))
                    .andExpect(status().isOk());
        }
        throw new AssertionError(version + " of " + key + " did not reach APPROVED");
    }

    private void reject(String key, String version, String comment) throws Exception {
        mockMvc.perform(post("/api/rules/" + key + "/versions/" + version + "/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"comment\":" + objectMapper.writeValueAsString(comment) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("DRAFT"));
    }
}
