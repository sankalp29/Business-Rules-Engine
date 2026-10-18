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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

class EffectiveWindowTest {

    @Test
    void aCalendarDayIsInclusiveInIndiaStandardTimeAndTheNextDayDoesNotOverlap() {
        EffectiveWindow may = window("2026-05-01", "2026-05-31");
        EffectiveWindow june = window("2026-06-01", "2026-06-30");
        Instant mayStart = LocalDate.of(2026, 5, 1).atStartOfDay(EffectiveWindow.IST).toInstant();
        Instant juneStart = LocalDate.of(2026, 6, 1).atStartOfDay(EffectiveWindow.IST).toInstant();

        Instant mayEnd = juneStart.minus(1, ChronoUnit.MINUTES);

        assertThat(may.from()).isEqualTo(mayStart);
        assertThat(may.until()).isEqualTo(mayEnd);
        assertThat(may.contains(mayStart)).isTrue();
        assertThat(may.contains(Instant.parse("2026-05-01T00:00:00Z"))).isTrue();
        assertThat(may.contains(mayEnd)).isTrue();
        assertThat(may.contains(mayEnd.plusSeconds(59))).isTrue();
        assertThat(may.contains(juneStart)).isFalse();
        assertThat(may.overlaps(june)).isFalse();
        assertThat(june.contains(juneStart)).isTrue();
        assertThat(window("2026-05-01", "2026-05-01").until()).isEqualTo(mayStart.plus(1, ChronoUnit.DAYS).minus(1, ChronoUnit.MINUTES));
    }

    @Test
    void theSameInstantCannotBelongToTwoVersions() {
        EffectiveWindow left = EffectiveWindow.interpret("2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z").window();
        EffectiveWindow right = EffectiveWindow.interpret("2026-06-01T00:00:00Z", "2026-12-31T00:00:00Z").window();

        assertThat(left.overlaps(right)).isTrue();
        assertThat(left.contains(Instant.parse("2026-06-01T00:00:00Z"))).isTrue();
    }

    @Test
    void anOpenLegacyWindowStaysActiveAndOverlapsALaterRange() {
        EffectiveWindow legacy = new EffectiveWindow(Instant.EPOCH, null);
        EffectiveWindow later = window("2026-01-01", "2026-12-31");

        assertThat(legacy.contains(Instant.parse("2026-10-17T12:00:00Z"))).isTrue();
        assertThat(legacy.contains(Instant.parse("1969-12-31T23:59:59Z"))).isFalse();
        assertThat(legacy.overlaps(later)).isTrue();
    }

    @Test
    void aWindowThatEndsBeforeItStartsIsRejected() {
        EffectiveWindow.Interpretation backwards = EffectiveWindow.interpret("2026-12-31", "2026-01-01");
        EffectiveWindow.Interpretation missing = EffectiveWindow.interpret(" ", null);
        EffectiveWindow.Interpretation garbled = EffectiveWindow.interpret("tomorrow", "2026-12-31");

        assertThat(backwards.window()).isNull();
        assertThat(backwards.errors()).contains("effectiveUntil must be after effectiveFrom");
        assertThat(EffectiveWindow.interpret("2026-05-01T00:00:00Z", "2026-05-01T00:00:00Z").errors())
                .contains("effectiveUntil must be after effectiveFrom");
        assertThat(missing.errors()).contains("effectiveFrom is required", "effectiveUntil is required");
        assertThat(garbled.errors()).contains("effectiveFrom must be a date (YYYY-MM-DD) or an ISO-8601 timestamp");
        assertThat(EffectiveWindow.interpret("2026-05-01T00:00:30Z", "2026-05-02T00:00:00Z").errors())
                .contains("effectiveFrom must be a whole minute");
        assertThat(EffectiveWindow.interpret("2026-05-01T00:00:00Z", "2026-05-02T00:00:01+05:30").errors())
                .contains("effectiveUntil must be a whole minute");
        assertThat(window("2026-05-01T00:00+05:30", "2026-05-01T23:59").from())
                .isEqualTo(LocalDate.of(2026, 5, 1).atStartOfDay(EffectiveWindow.IST).toInstant());
    }

    @Test
    void anOffsetTimestampIsAccepted() {
        EffectiveWindow window = EffectiveWindow.interpret("2026-01-01T05:30:00+05:30", "2026-01-02T00:00:00Z").window();

        assertThat(window.from()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(window.contains(Instant.parse("2026-01-01T00:00:00Z"))).isTrue();
        assertThat(window("2026-01-01T00:00:00", "2026-01-01T05:30:00").from())
                .isEqualTo(LocalDate.of(2026, 1, 1).atStartOfDay(EffectiveWindow.IST).toInstant());
    }

    @Test
    void versionTokensStartAtV1() {
        assertThat(VersionLabels.of(1)).isEqualTo("v1");
        assertThat(VersionLabels.of(12)).isEqualTo("v12");
        assertThat(VersionLabels.number("v2")).isEqualTo(2);
        assertThat(VersionLabels.number("V2")).isEqualTo(2);
        assertThat(VersionLabels.number("2")).isEqualTo(2);
        assertThat(VersionLabels.number("v0")).isNull();
        assertThat(VersionLabels.number("v01")).isNull();
        assertThat(VersionLabels.number("version2")).isNull();
        assertThat(VersionLabels.number("")).isNull();
    }

    private static EffectiveWindow window(String from, String until) {
        EffectiveWindow.Interpretation interpretation = EffectiveWindow.interpret(from, until);
        assertThat(interpretation.errors()).isEmpty();
        return interpretation.window();
    }
}
