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

/**
 * Body of {@code PUT /api/rules/{key}/versions/{version}/dates}. Each value is an India Standard
 * Time calendar date ({@code YYYY-MM-DD}) or an ISO-8601 timestamp at a whole minute.
 * {@code segments} replaces the single pair when it is present. One version can run in several
 * separate periods, with a gap between them where another version runs.
 */
public record EffectiveDates(String effectiveFrom, String effectiveUntil, List<EffectiveSegmentRequest> segments) {
}
