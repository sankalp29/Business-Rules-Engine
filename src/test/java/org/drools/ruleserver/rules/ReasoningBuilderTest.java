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

import org.drools.ruleserver.model.ValueChange;
import org.junit.jupiter.api.Test;

class ReasoningBuilderTest {

    private final ReasoningBuilder builder = new ReasoningBuilder();

    @Test
    void describesARuleThatDidNotFire() {
        Reasoning reasoning = builder.describe("Apply senior discount", false, List.of(customer("STANDARD", 0.0)), List.of(customer("STANDARD", 0.0)));

        assertThat(reasoning.changes()).hasSize(1);
        assertThat(reasoning.changes().get(0).type()).isEqualTo("Customer");
        assertThat(reasoning.changes().get(0).values()).isEmpty();
        assertThat(reasoning.text()).isEqualTo("Rule Apply senior discount did not fire. No fields were changed.");
        assertThat(reasoning.text()).doesNotContain("\\", "/");
    }

    @Test
    void describesChangedFieldsAndIndexesRepeatedFacts() {
        FactSnapshot firstBefore = customer("STANDARD", 0.0);
        FactSnapshot secondBefore = customer("STANDARD", 0.0);
        FactSnapshot firstAfter = customer("SENIOR", 0.2);
        FactSnapshot secondAfter = customer("STANDARD", 0.0);

        Reasoning reasoning = builder.describe("Apply senior discount", true,
                List.of(firstBefore, secondBefore), List.of(firstAfter, secondAfter));

        assertThat(reasoning.text()).isEqualTo(
                "Rule Apply senior discount fired. Set Customer[1].segment to SENIOR (was STANDARD). Set Customer[1].discount to 0.2 (was 0.0).");
        assertThat(reasoning.text()).doesNotContain("\\", "/");
        assertThat(reasoning.changes()).hasSize(2);
        assertThat(reasoning.changes().get(0).type()).isEqualTo("Customer");
        assertThat(reasoning.changes().get(0).values()).containsOnlyKeys("segment", "discount");
        assertThat(((ValueChange) reasoning.changes().get(0).values().get("segment")).newValue()).isEqualTo("SENIOR");
        assertThat(((ValueChange) reasoning.changes().get(0).values().get("discount")).newValue()).isEqualTo(0.2);
        assertThat(reasoning.changes().get(1).type()).isEqualTo("Customer");
        assertThat(reasoning.changes().get(1).values()).isEmpty();
    }

    @Test
    void omitsSlashesBackslashesAndQuotesFromTheReasoningText() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("note", "a/b\\c\"d");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("note", "x\\y/z");

        Reasoning reasoning = builder.describe("Path/A\\B \"C\"", true,
                List.of(new FactSnapshot("Customer", before)),
                List.of(new FactSnapshot("Customer", after)));

        assertThat(reasoning.text()).isEqualTo(
                "Rule PathAB C fired. Set Customer.note to xyz (was abcd).");
        assertThat(reasoning.text()).doesNotContain("\\", "/");
        ValueChange note = (ValueChange) reasoning.changes().get(0).values().get("note");
        assertThat(note.previousValue()).isEqualTo("a/b\\c\"d");
        assertThat(note.newValue()).isEqualTo("x\\y/z");
    }

    private static FactSnapshot customer(String segment, double discount) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("segment", segment);
        values.put("discount", discount);
        return new FactSnapshot("Customer", values);
    }
}
