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
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The when/then body stored on a rule version. {@code code}, {@code name}, and {@code description}
 * are accepted on create. Code and name belong to the rule and are the same on every version.
 * Description belongs to the version. None of the three is written into the definition JSON.
 * {@code facts} is ignored. {@code inputSchema} and {@code outputSchema} sent by a client are
 * ignored and replaced at authoring time. A stored version with a null schema is given one when it
 * is read; a schema already stored, including an empty list, is returned unchanged.
 * Effective dates and segments are stored on the version, not in this document. They are ignored
 * here so a definition file that also carries them can still be read as a rule body.
 * {@code when} is the gate. {@code then} runs only after that gate matches. A then block may hold
 * assignments and if / else-if / else branches for its fact.
 */
@JsonIgnoreProperties({ "facts", "effectiveFrom", "effectiveUntil", "segments" })
public record RuleDefinition(
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String code,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String name,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String description,
        Integer salience,
        List<FactDeclaration> inputSchema,
        List<FactDeclaration> outputSchema,
        List<WhenBlock> when,
        List<ThenBlock> then) {
}
