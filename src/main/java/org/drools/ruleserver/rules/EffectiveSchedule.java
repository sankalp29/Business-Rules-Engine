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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The minutes in which one rule version may run. Segments are ordered, do not overlap, and keep a
 * gap where another version runs. Touching minutes of the same version are one segment.
 */
public record EffectiveSchedule(List<EffectiveWindow> segments) {

    public EffectiveSchedule {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("at least one effective segment is required");
        }
        List<EffectiveWindow> ordered = new ArrayList<>(segments);
        ordered.sort(Comparator.comparing(EffectiveWindow::from));
        segments = List.copyOf(ordered);
    }

    public boolean contains(Instant at) {
        for (EffectiveWindow segment : segments) {
            if (segment.contains(at)) {
                return true;
            }
        }
        return false;
    }

    public boolean overlaps(EffectiveSchedule other) {
        for (EffectiveWindow segment : segments) {
            for (EffectiveWindow candidate : other.segments) {
                if (segment.overlaps(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    public String describe() {
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                message.append("; ");
            }
            message.append(segments.get(i).describe());
        }
        return message.toString();
    }

    /**
     * Reads one or more segments. {@code existing} is null when the version is new. A stored
     * instant may be echoed even when it is finer than a minute. A new instant is a whole minute,
     * and a new bound is not before the current minute. A bound that is already before the current
     * minute stays as it is.
     */
    public static Proposal propose(List<RawSegment> raw, EffectiveSchedule existing, Instant now) {
        List<String> errors = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return new Proposal(null, List.of("at least one effective segment is required"));
        }
        boolean single = raw.size() == 1;
        List<EffectiveWindow> windows = new ArrayList<>();
        List<String> minuteErrors = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            RawSegment segment = raw.get(i);
            String fromField = field(single, i, "effectiveFrom");
            String untilField = field(single, i, "effectiveUntil");
            List<String> local = new ArrayList<>();
            Instant from = segment == null ? null : EffectiveWindow.read(segment.from(), false, fromField, local);
            Instant until = segment == null ? null : EffectiveWindow.read(segment.until(), true, untilField, local);
            if (segment == null) {
                local.add(fromField + " is required");
                local.add(untilField + " is required");
            }
            if (from != null && !EffectiveWindow.isWholeMinute(from) && !storedBound(existing, from)) {
                minuteErrors.add(fromField + " must be a whole minute");
            }
            if (until != null && !EffectiveWindow.isWholeMinute(until) && !storedBound(existing, until)) {
                minuteErrors.add(untilField + " must be a whole minute");
            }
            if (from != null && until != null && !until.isAfter(from)) {
                local.add(single
                        ? "effectiveUntil must be after effectiveFrom"
                        : untilField + " must be after " + fromField);
            }
            errors.addAll(local);
            if (local.isEmpty() && from != null && until != null) {
                windows.add(new EffectiveWindow(from, until));
            }
        }
        if (!errors.isEmpty()) {
            errors.addAll(minuteErrors);
            return new Proposal(null, unique(errors));
        }
        List<EffectiveWindow> normalized = normalize(windows, errors);
        if (!errors.isEmpty()) {
            return new Proposal(null, unique(errors));
        }
        EffectiveSchedule schedule = new EffectiveSchedule(normalized);
        if (existing == null) {
            errors.addAll(validateNew(schedule, now));
        } else {
            errors.addAll(validateChange(existing, schedule, now));
        }
        errors.addAll(minuteErrors);
        if (!errors.isEmpty()) {
            return new Proposal(null, unique(errors));
        }
        return new Proposal(schedule, List.of());
    }

    /**
     * Removes {@code incoming} from the middle of {@code host}. Each incoming segment has to leave
     * a minute on both sides. The earlier side cannot end before the current minute, and a segment
     * that has already ended is left as it is.
     */
    public static Carve carve(EffectiveSchedule host, EffectiveSchedule incoming, Instant now) {
        List<EffectiveWindow> remaining = new ArrayList<>(host.segments);
        for (EffectiveWindow hole : incoming.segments) {
            int index = splitIndex(remaining, hole);
            if (index < 0) {
                continue;
            }
            EffectiveWindow covered = remaining.get(index);
            if (covered.until() != null && EffectiveWindow.isBeforeCurrent(covered.until(), now)) {
                return new Carve(null, "a segment that is already before the current time cannot change");
            }
            Instant leftUntil = hole.from().minus(1, ChronoUnit.MINUTES);
            Instant rightFrom = hole.until().plus(1, ChronoUnit.MINUTES);
            if (EffectiveWindow.isBeforeCurrent(leftUntil, now)) {
                return new Carve(null, "the earlier part would end before the current time");
            }
            if (EffectiveWindow.isBeforeCurrent(rightFrom, now)) {
                return new Carve(null, "the later part would start before the current time");
            }
            remaining.set(index, new EffectiveWindow(covered.from(), leftUntil));
            remaining.add(index + 1, new EffectiveWindow(rightFrom, covered.until()));
        }
        return new Carve(new EffectiveSchedule(remaining), null);
    }

    private static List<String> validateNew(EffectiveSchedule schedule, Instant now) {
        List<String> errors = new ArrayList<>();
        for (EffectiveWindow window : schedule.segments) {
            if (EffectiveWindow.isBeforeCurrent(window.from(), now)) {
                errors.add("effectiveFrom cannot be before the current time");
            }
            if (window.until() != null && EffectiveWindow.isBeforeCurrent(window.until(), now)) {
                errors.add("effectiveUntil cannot be before the current time");
            }
        }
        return unique(errors);
    }

    public static List<String> validateChange(EffectiveSchedule existing, EffectiveSchedule proposed, Instant now) {
        List<String> errors = new ArrayList<>();
        for (EffectiveWindow stored : existing.segments) {
            boolean fromLocked = EffectiveWindow.isBeforeCurrent(stored.from(), now);
            boolean untilLocked = stored.until() != null && EffectiveWindow.isBeforeCurrent(stored.until(), now);
            if (!fromLocked && !untilLocked) {
                continue;
            }
            EffectiveWindow match = sameFrom(proposed.segments, stored.from());
            if (match == null || !match.from().equals(stored.from())) {
                errors.add("effectiveFrom cannot change because it is already before the current time");
            }
            if (untilLocked && (match == null || match.until() == null || !match.until().equals(stored.until()))) {
                errors.add("effectiveUntil cannot change because it is already before the current time");
            }
        }
        for (EffectiveWindow next : proposed.segments) {
            EffectiveWindow stored = sameFrom(existing.segments, next.from());
            if (EffectiveWindow.isBeforeCurrent(next.from(), now) && (stored == null || !stored.from().equals(next.from()))) {
                errors.add("effectiveFrom cannot be before the current time");
            }
            if (next.until() != null && EffectiveWindow.isBeforeCurrent(next.until(), now)) {
                boolean unchanged = stored != null && stored.until() != null && stored.until().equals(next.until());
                if (!unchanged) {
                    if (stored != null && stored.until() != null && EffectiveWindow.isBeforeCurrent(stored.until(), now)) {
                        errors.add("effectiveUntil cannot change because it is already before the current time");
                    } else {
                        errors.add("effectiveUntil cannot be before the current time");
                    }
                }
            }
        }
        return unique(errors);
    }

    private static List<EffectiveWindow> normalize(List<EffectiveWindow> windows, List<String> errors) {
        List<EffectiveWindow> ordered = new ArrayList<>(windows);
        ordered.sort(Comparator.comparing(EffectiveWindow::from));
        List<EffectiveWindow> merged = new ArrayList<>();
        for (EffectiveWindow window : ordered) {
            if (merged.isEmpty()) {
                merged.add(window);
                continue;
            }
            EffectiveWindow previous = merged.get(merged.size() - 1);
            if (continues(previous, window)) {
                merged.set(merged.size() - 1, new EffectiveWindow(previous.from(), window.until()));
                continue;
            }
            if (previous.overlaps(window)) {
                errors.add("effective segments overlap each other");
                return List.of();
            }
            merged.add(window);
        }
        return merged;
    }

    private static boolean continues(EffectiveWindow previous, EffectiveWindow next) {
        return previous.until() != null && previous.until().plus(1, ChronoUnit.MINUTES).equals(next.from());
    }

    private static int splitIndex(List<EffectiveWindow> windows, EffectiveWindow hole) {
        if (hole.until() == null) {
            return -1;
        }
        Instant leftUntil = hole.from().minus(1, ChronoUnit.MINUTES);
        Instant rightFrom = hole.until().plus(1, ChronoUnit.MINUTES);
        for (int i = 0; i < windows.size(); i++) {
            EffectiveWindow host = windows.get(i);
            if (host.until() == null) {
                continue;
            }
            if (leftUntil.isAfter(host.from()) && host.until().isAfter(rightFrom)) {
                return i;
            }
        }
        return -1;
    }

    private static EffectiveWindow sameFrom(List<EffectiveWindow> windows, Instant from) {
        for (EffectiveWindow window : windows) {
            if (window.from().equals(from)) {
                return window;
            }
        }
        return null;
    }

    private static boolean storedBound(EffectiveSchedule existing, Instant instant) {
        if (existing == null || instant == null) {
            return false;
        }
        for (EffectiveWindow window : existing.segments) {
            if (instant.equals(window.from()) || (window.until() != null && instant.equals(window.until()))) {
                return true;
            }
        }
        return false;
    }

    private static String field(boolean single, int index, String name) {
        if (single) {
            return name;
        }
        return "segment " + (index + 1) + " " + name;
    }

    private static List<String> unique(List<String> errors) {
        List<String> unique = new ArrayList<>();
        for (String error : errors) {
            if (!unique.contains(error)) {
                unique.add(error);
            }
        }
        return List.copyOf(unique);
    }

    public record RawSegment(String from, String until) {
    }

    public record Proposal(EffectiveSchedule schedule, List<String> errors) {
    }

    public record Carve(EffectiveSchedule schedule, String error) {
    }
}
