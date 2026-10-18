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
package org.drools.ruleserver.api;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * A clock whose instant tests can move. Production uses {@link Clock#system(ZoneId)}.
 */
public final class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = Objects.requireNonNull(instant);
        this.zone = Objects.requireNonNull(zone);
    }

    public void set(Instant instant) {
        this.instant = Objects.requireNonNull(instant);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
