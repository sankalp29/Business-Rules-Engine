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

import java.time.Instant;
import java.util.List;

import org.drools.ruleserver.model.StageMark;
import org.junit.jupiter.api.Test;

class ApprovalStagesTest {

    @Test
    void draftAndApprovedBracketTheConfiguredStages() {
        ApprovalStages.Normalized normalized = ApprovalStages.normalize(List.of(" Checker ", "Sign-off"));

        assertThat(normalized.ok()).isTrue();
        assertThat(normalized.stages()).containsExactly("Checker", "Sign-off");
        assertThat(ApprovalStages.path(normalized.stages(), ApprovalStages.DRAFT))
                .containsExactly("DRAFT", "Checker", "Sign-off", "APPROVED");
    }

    @Test
    void namesThatRepeatOrUseTheFixedEndsAreRejected() {
        ApprovalStages.Normalized reserved = ApprovalStages.normalize(List.of("draft", "Approved"));
        ApprovalStages.Normalized repeated = ApprovalStages.normalize(List.of("Checker", "checker"));
        ApprovalStages.Normalized blank = ApprovalStages.normalize(List.of("  "));

        assertThat(reserved.ok()).isFalse();
        assertThat(reserved.errors()).anyMatch(error -> error.contains("cannot be named Draft or Approved"));
        assertThat(repeated.errors()).anyMatch(error -> error.contains("repeats"));
        assertThat(blank.errors()).anyMatch(error -> error.contains("needs a name"));
    }

    @Test
    void theBarShowsOnlyTheLatestPassFromDraft() {
        List<String> middle = List.of("Checker", "Sign-off");
        ApprovalStages.Presentation reached = ApprovalStages.present(
                "Checker", middle, "v3", null, null, null);

        assertThat(reached.editable()).isFalse();
        assertThat(statuses(reached)).containsExactly("complete", "current", "upcoming", "upcoming");

        ApprovalStages.Presentation returned = ApprovalStages.present(
                "DRAFT",
                middle,
                "v3",
                "Checker",
                "Age threshold is wrong",
                Instant.parse("2026-10-18T00:00:00Z"));

        assertThat(returned.editable()).isTrue();
        assertThat(returned.stages()).hasSize(4);
        assertThat(statuses(returned)).containsExactly("current", "upcoming", "upcoming", "upcoming");
        assertThat(returned.returnedFrom().label()).isEqualTo("Checker");
        assertThat(returned.returnedFrom().comment()).isEqualTo("Age threshold is wrong");

        ApprovalStages.Presentation again = ApprovalStages.present("Sign-off", middle, "v3", null, null, null);
        assertThat(statuses(again)).containsExactly("complete", "complete", "current", "upcoming");
        assertThat(again.returnedFrom()).isNull();
    }

    @Test
    void aVersionStoredBeforeApprovalIsApprovedAndNotEditable() {
        ApprovalStages.Presentation legacy = ApprovalStages.present(null, List.of(), null, null, null, null);

        assertThat(legacy.stage()).isEqualTo("APPROVED");
        assertThat(legacy.editable()).isFalse();
        assertThat(statuses(legacy)).containsExactly("complete", "current");
        assertThat(ApprovalStages.isApproved(null)).isTrue();
    }

    private static List<String> statuses(ApprovalStages.Presentation presentation) {
        return presentation.stages().stream().map(StageMark::status).toList();
    }
}
