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
import java.util.List;

import org.junit.jupiter.api.Test;

class EffectiveScheduleTest {

    private static final Instant SEPTEMBER = day(2026, 9, 15);

    @Test
    void aNewBoundCannotBeBeforeTheCurrentMinuteOrFinerThanAMinute() {
        EffectiveSchedule.Proposal pastFrom = propose(null, SEPTEMBER, "2026-09-14T00:00:00+05:30", "2026-10-01T00:00:00+05:30");
        EffectiveSchedule.Proposal seconds = propose(null, SEPTEMBER, "2026-09-15T00:00:30+05:30", "2026-10-01T00:00:00+05:30");
        EffectiveSchedule.Proposal currentMinute = propose(null, SEPTEMBER.plusSeconds(30), "2026-09-15T00:00:00+05:30", "2026-10-01T00:00:00+05:30");

        assertThat(pastFrom.errors()).contains("effectiveFrom cannot be before the current time");
        assertThat(seconds.errors()).contains("effectiveFrom must be a whole minute");
        assertThat(currentMinute.errors()).isEmpty();
        assertThat(currentMinute.schedule().contains(SEPTEMBER.plusSeconds(45))).isTrue();
    }

    @Test
    void aBoundThatIsAlreadyBeforeTheCurrentMinuteCannotChange() {
        EffectiveSchedule stored = propose(null, SEPTEMBER, "2026-10-01", "2026-10-10").schedule();
        Instant octoberFifth = day(2026, 10, 5);

        EffectiveSchedule.Proposal movedStart = propose(stored, octoberFifth, "2026-10-02", "2026-10-10");
        EffectiveSchedule.Proposal movedEnd = propose(stored, day(2026, 10, 11), "2026-10-01", "2026-10-12");
        EffectiveSchedule.Proposal shortened = propose(stored, octoberFifth, "2026-10-01", "2026-10-08");
        EffectiveSchedule.Proposal endedInThePast = propose(stored, octoberFifth, "2026-10-01", "2026-10-04");

        assertThat(movedStart.errors()).contains("effectiveFrom cannot change because it is already before the current time");
        assertThat(movedEnd.errors()).contains("effectiveUntil cannot change because it is already before the current time");
        assertThat(shortened.errors()).isEmpty();
        assertThat(shortened.schedule().segments()).hasSize(1);
        assertThat(endedInThePast.errors()).contains("effectiveUntil cannot be before the current time");
    }

    @Test
    void anEchoedStoredInstantKeepsSubMinutePrecision() {
        Instant from = Instant.parse("2026-10-01T18:30:00Z");
        Instant until = Instant.parse("2026-10-10T18:29:59.999Z");
        EffectiveSchedule stored = new EffectiveSchedule(List.of(new EffectiveWindow(from, until)));

        EffectiveSchedule.Proposal echoed = EffectiveSchedule.propose(
                List.of(new EffectiveSchedule.RawSegment("2026-10-01T18:30:00Z", "2026-10-10T18:29:59.999Z")),
                stored,
                day(2026, 10, 5));

        assertThat(echoed.errors()).isEmpty();
        assertThat(echoed.schedule().segments().get(0).until()).isEqualTo(until);
    }

    @Test
    void touchingSegmentsOfOneVersionBecomeOneSegmentAndAGapStays() {
        EffectiveSchedule.Proposal continuous = EffectiveSchedule.propose(List.of(
                new EffectiveSchedule.RawSegment("2026-10-01", "2026-10-01"),
                new EffectiveSchedule.RawSegment("2026-10-02", "2026-10-10")), null, SEPTEMBER);
        EffectiveSchedule.Proposal gap = EffectiveSchedule.propose(List.of(
                new EffectiveSchedule.RawSegment("2026-10-01", "2026-10-01"),
                new EffectiveSchedule.RawSegment("2026-10-03", "2026-10-10")), null, SEPTEMBER);
        EffectiveSchedule.Proposal overlap = EffectiveSchedule.propose(List.of(
                new EffectiveSchedule.RawSegment("2026-10-01", "2026-10-05"),
                new EffectiveSchedule.RawSegment("2026-10-05", "2026-10-10")), null, SEPTEMBER);

        assertThat(continuous.errors()).isEmpty();
        assertThat(continuous.schedule().segments()).hasSize(1);
        assertThat(gap.errors()).isEmpty();
        assertThat(gap.schedule().segments()).hasSize(2);
        assertThat(gap.schedule().contains(day(2026, 10, 2))).isFalse();
        assertThat(gap.schedule().contains(day(2026, 10, 3))).isTrue();
        assertThat(overlap.errors()).contains("effective segments overlap each other");
    }

    @Test
    void aFlashPeriodSplitsTheVersionAroundItAndThatVersionResumes() {
        EffectiveSchedule festival = propose(null, SEPTEMBER, "2026-10-01", "2026-10-10").schedule();
        EffectiveSchedule jayanti = propose(null, SEPTEMBER, "2026-10-02", "2026-10-02").schedule();

        EffectiveSchedule.Carve carved = EffectiveSchedule.carve(festival, jayanti, SEPTEMBER);

        assertThat(carved.error()).isNull();
        assertThat(carved.schedule().segments()).hasSize(2);
        assertThat(carved.schedule().contains(day(2026, 10, 1))).isTrue();
        assertThat(carved.schedule().contains(day(2026, 10, 2))).isFalse();
        assertThat(carved.schedule().contains(day(2026, 10, 2).plusSeconds(23 * 3600L))).isFalse();
        assertThat(carved.schedule().contains(day(2026, 10, 3))).isTrue();
        assertThat(carved.schedule().contains(day(2026, 10, 10))).isTrue();
        assertThat(carved.schedule().overlaps(jayanti)).isFalse();
        assertThat(jayanti.contains(day(2026, 10, 2).plusSeconds(12 * 3600L))).isTrue();
    }

    @Test
    void aPartialOverlapShortensOrShiftsTheCoveredVersion() {
        EffectiveSchedule festival = propose(null, SEPTEMBER, "2026-10-01", "2026-10-10").schedule();
        EffectiveSchedule tail = propose(null, SEPTEMBER, "2026-10-08", "2026-10-12").schedule();
        EffectiveSchedule.Carve partial = EffectiveSchedule.carve(festival, tail, SEPTEMBER);

        assertThat(partial.error()).isNull();
        assertThat(partial.schedule().overlaps(tail)).isFalse();
        assertThat(partial.schedule().contains(day(2026, 10, 7))).isTrue();
        assertThat(partial.schedule().contains(day(2026, 10, 8))).isFalse();
        assertThat(partial.schedule().segments()).hasSize(1);
        assertThat(partial.schedule().segments().get(0).until()).isEqualTo(day(2026, 10, 7).plusSeconds(23 * 3600L + 59 * 60L));

        EffectiveSchedule later = propose(null, SEPTEMBER, "2026-10-08", "2026-10-12").schedule();
        EffectiveSchedule head = propose(null, SEPTEMBER, "2026-10-01", "2026-10-09").schedule();
        EffectiveSchedule.Carve shifted = EffectiveSchedule.carve(later, head, SEPTEMBER);
        assertThat(shifted.error()).isNull();
        assertThat(shifted.schedule().overlaps(head)).isFalse();
        assertThat(shifted.schedule().contains(day(2026, 10, 9))).isFalse();
        assertThat(shifted.schedule().contains(day(2026, 10, 10))).isTrue();
        assertThat(shifted.schedule().segments().get(0).from()).isEqualTo(day(2026, 10, 10));

        EffectiveSchedule started = propose(null, SEPTEMBER, "2026-10-01", "2026-10-10").schedule();
        EffectiveSchedule tomorrow = propose(null, day(2026, 10, 5), "2026-10-06", "2026-10-06").schedule();
        EffectiveSchedule.Carve during = EffectiveSchedule.carve(started, tomorrow, day(2026, 10, 5));
        assertThat(during.error()).isNull();
        assertThat(during.schedule().overlaps(tomorrow)).isFalse();
        assertThat(during.schedule().segments().get(0).from()).isEqualTo(started.segments().get(0).from());

        EffectiveSchedule finished = propose(null, SEPTEMBER, "2026-10-01", "2026-10-03").schedule();
        EffectiveSchedule inside = propose(null, SEPTEMBER, "2026-10-02", "2026-10-02").schedule();
        EffectiveSchedule.Carve tooLate = EffectiveSchedule.carve(finished, inside, day(2026, 10, 5));
        assertThat(tooLate.error()).isEqualTo("a segment that is already before the current time cannot change");
    }

    @Test
    void coveringEveryMinuteOfAVersionLeavesItWithNoDates() {
        EffectiveSchedule host = propose(null, SEPTEMBER, "2026-11-01", "2026-11-10").schedule();
        EffectiveSchedule cover = propose(null, SEPTEMBER, "2026-10-15", "2026-11-20").schedule();

        EffectiveSchedule.Carve carved = EffectiveSchedule.carve(host, cover, SEPTEMBER);

        assertThat(carved.schedule()).isNull();
        assertThat(carved.error()).isEqualTo("it would have no effective dates left");
    }

    @Test
    void aOneMinuteRemainderStaysWithTheCoveredVersion() {
        Instant from = day(2026, 11, 1);
        Instant until = from.plus(2, java.time.temporal.ChronoUnit.MINUTES);
        EffectiveSchedule host = new EffectiveSchedule(List.of(new EffectiveWindow(from, until)));
        EffectiveSchedule hole = new EffectiveSchedule(List.of(new EffectiveWindow(from.plus(1, java.time.temporal.ChronoUnit.MINUTES),
                from.plus(1, java.time.temporal.ChronoUnit.MINUTES))));

        EffectiveSchedule.Carve carved = EffectiveSchedule.carve(host, hole, SEPTEMBER);

        assertThat(carved.error()).isNull();
        assertThat(carved.schedule().overlaps(hole)).isFalse();
        assertThat(carved.schedule().segments()).hasSize(2);
        assertThat(carved.schedule().contains(from)).isTrue();
        assertThat(carved.schedule().contains(until)).isTrue();
        assertThat(carved.schedule().contains(from.plus(1, java.time.temporal.ChronoUnit.MINUTES))).isFalse();
    }

    @Test
    void aSplitThatWouldEndTheEarlierPartInThePastIsRefused() {
        EffectiveSchedule started = propose(null, SEPTEMBER, "2026-10-01", "2026-10-10").schedule();
        Instant now = day(2026, 10, 5).plusSeconds(15 * 3600L);
        EffectiveSchedule immediate = propose(null, now, "2026-10-05T15:00:00+05:30", "2026-10-05T18:00:00+05:30").schedule();

        EffectiveSchedule.Carve carved = EffectiveSchedule.carve(started, immediate, now);

        assertThat(carved.error()).isEqualTo("the earlier part would end before the current time");
    }

    private static EffectiveSchedule.Proposal propose(EffectiveSchedule existing, Instant now, String from, String until) {
        return EffectiveSchedule.propose(List.of(new EffectiveSchedule.RawSegment(from, until)), existing, now);
    }

    private static Instant day(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(EffectiveWindow.IST).toInstant();
    }
}
