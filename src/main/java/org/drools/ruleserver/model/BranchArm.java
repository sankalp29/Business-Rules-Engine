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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One arm of an if / else-if / else chain. {@code when} is omitted on the else arm.
 * Assignments are fields of the then block that owns the chain.
 */
public record BranchArm(
        @JsonInclude(JsonInclude.Include.NON_NULL) Condition when,
        List<FieldAssignment> assignments) {
}
