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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Body of {@code POST /api/rules} and {@code POST /api/rules/{key}/versions}.
 * {@code facts}, {@code inputSchema}, and {@code outputSchema} are accepted and ignored.
 * The server assigns the version token. {@code effectiveFrom} and {@code effectiveUntil} are an
 * India Standard Time calendar date ({@code YYYY-MM-DD}) or an ISO-8601 timestamp at a whole
 * minute. Neither bound can be before the current minute, and {@code effectiveUntil} is after
 * {@code effectiveFrom}. {@code segments} replaces that pair when it is present, so one version
 * can run in several separate periods.
 */
@JsonIgnoreProperties("facts")
public record RuleSubmission(
        String code,
        String name,
        String description,
        Integer salience,
        List<FactDeclaration> inputSchema,
        List<FactDeclaration> outputSchema,
        List<WhenBlock> when,
        List<ThenBlock> then,
        String effectiveFrom,
        String effectiveUntil,
        List<EffectiveSegmentRequest> segments) {

    public RuleDefinition definition() {
        return new RuleDefinition(code, name, description, salience, inputSchema, outputSchema, when, then);
    }
}
