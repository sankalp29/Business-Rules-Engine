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

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Inclusive minute range in which a rule version may run. {@code until} is null only for a rule
 * that was stored before versions existed: that original version stays active until another
 * version starts. A date without a time is an India Standard Time calendar day.
 * {@code effectiveFrom} is 00:00 of that day and {@code effectiveUntil} is 23:59 of that day.
 * Both instants are a whole minute. {@code effectiveUntil} is after {@code effectiveFrom}.
 * A timestamp belongs to the window when its minute does.
 */
public record EffectiveWindow(Instant from, Instant until) {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final DateTimeFormatter IST_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(IST);
    private static final DateTimeFormatter MINUTE_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXXX");
    private static final DateTimeFormatter MINUTE_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    public EffectiveWindow {
        if (from == null) {
            throw new IllegalArgumentException("effectiveFrom is required");
        }
        if (until != null && !until.isAfter(from)) {
            throw new IllegalArgumentException("effectiveUntil must be after effectiveFrom");
        }
    }

    public boolean contains(Instant at) {
        Instant minute = at.truncatedTo(ChronoUnit.MINUTES);
        if (minute.isBefore(from.truncatedTo(ChronoUnit.MINUTES))) {
            return false;
        }
        return until == null || !minute.isAfter(until.truncatedTo(ChronoUnit.MINUTES));
    }

    /**
     * Shares a minute with {@code other}. The next minute does not overlap.
     */
    public boolean overlaps(EffectiveWindow other) {
        Instant thisFrom = from.truncatedTo(ChronoUnit.MINUTES);
        Instant otherFrom = other.from.truncatedTo(ChronoUnit.MINUTES);
        if (until != null && until.truncatedTo(ChronoUnit.MINUTES).isBefore(otherFrom)) {
            return false;
        }
        return other.until == null || !other.until.truncatedTo(ChronoUnit.MINUTES).isBefore(thisFrom);
    }

    public static boolean isWholeMinute(Instant instant) {
        return instant.equals(instant.truncatedTo(ChronoUnit.MINUTES));
    }

    /**
     * A minute that has already finished. The current minute is still the present.
     */
    public static boolean isBeforeCurrent(Instant value, Instant now) {
        return value.truncatedTo(ChronoUnit.MINUTES).isBefore(now.truncatedTo(ChronoUnit.MINUTES));
    }

    public static Interpretation interpret(String fromRaw, String untilRaw) {
        List<String> errors = new ArrayList<>();
        Instant from = read(fromRaw, false, "effectiveFrom", errors);
        Instant until = read(untilRaw, true, "effectiveUntil", errors);
        if (from != null && !isWholeMinute(from)) {
            errors.add("effectiveFrom must be a whole minute");
        }
        if (until != null && !isWholeMinute(until)) {
            errors.add("effectiveUntil must be a whole minute");
        }
        if (from != null && until != null && !until.isAfter(from)) {
            errors.add("effectiveUntil must be after effectiveFrom");
        }
        EffectiveWindow window = errors.isEmpty() ? new EffectiveWindow(from, until) : null;
        return new Interpretation(window, List.copyOf(errors));
    }

    public static String format(Instant instant) {
        return IST_FORMAT.format(instant);
    }

    public String describe() {
        if (until == null) {
            return format(from) + " with no end date";
        }
        return format(from) + " until " + format(until);
    }

    static Instant read(String raw, boolean endOfDay, String field, List<String> errors) {
        if (raw == null || raw.isBlank()) {
            errors.add(field + " is required");
            return null;
        }
        String text = raw.trim();
        try {
            if (DATE.matcher(text).matches()) {
                LocalDate date = LocalDate.parse(text);
                if (endOfDay) {
                    return date.atTime(23, 59).atZone(IST).toInstant();
                }
                return date.atStartOfDay(IST).toInstant();
            }
            Instant instant = parseTimestamp(text);
            if (instant == null) {
                errors.add(field + " must be a date (YYYY-MM-DD) or an ISO-8601 timestamp");
            }
            return instant;
        } catch (DateTimeException ex) {
            errors.add(field + " must be a date (YYYY-MM-DD) or an ISO-8601 timestamp");
            return null;
        }
    }

    private static Instant parseTimestamp(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // A minute without seconds is accepted below.
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // A local date-time is India Standard Time.
        }
        try {
            return LocalDateTime.parse(text).atZone(IST).toInstant();
        } catch (DateTimeParseException ignored) {
            // Minute precision may omit seconds.
        }
        try {
            return OffsetDateTime.parse(text, MINUTE_OFFSET).toInstant();
        } catch (DateTimeParseException ignored) {
            // A local minute is India Standard Time.
        }
        try {
            return LocalDateTime.parse(text, MINUTE_LOCAL).atZone(IST).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    public record Interpretation(EffectiveWindow window, List<String> errors) {
    }
}
