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
package org.drools.ruleserver.model;

import java.time.Instant;
import java.util.List;

/**
 * One version of a rule. {@code version} is the token the server assigned, such as {@code v2}.
 * {@code segments} are the minutes in which this version runs. {@code effectiveFrom} is the start
 * of the first segment and {@code effectiveUntil} is the end of the last. {@code active} is true
 * when the current minute falls inside a segment, including when a later segment resumes after a
 * gap. {@code effectiveUntil} is null only for a rule stored before versions existed.
 * {@code stage} is the current approval step. {@code stages} is the path this version follows,
 * marked only for the latest pass from draft. {@code editable} is true in draft.
 * {@code returnedFrom} is the rejection that sent the version back, while it is still in draft.
 * Code and name belong to the rule and are not repeated here.
 */
public record RuleVersionResponse(
        Long id,
        int versionNumber,
        String version,
        String description,
        Instant effectiveFrom,
        Instant effectiveUntil,
        List<EffectiveSegment> segments,
        boolean active,
        RuleDefinition definition,
        String drl,
        Instant createdAt,
        String stage,
        String stageLabel,
        boolean editable,
        String workflowVersion,
        List<StageMark> stages,
        RejectionNote returnedFrom) {
}
