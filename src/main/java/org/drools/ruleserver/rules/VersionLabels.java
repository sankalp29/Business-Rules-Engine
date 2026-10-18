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

/**
 * Version tokens assigned by the server: {@code v1}, {@code v2}, through {@code vN}.
 * A request may name a version as {@code v2} or {@code 2}.
 */
public final class VersionLabels {

    private VersionLabels() {
    }

    public static String of(int number) {
        return "v" + number;
    }

    /**
     * @return the version number, or null when {@code raw} is not {@code v1} or {@code 1}
     */
    public static Integer number(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.length() >= 2 && (text.charAt(0) == 'v' || text.charAt(0) == 'V')) {
            text = text.substring(1);
        }
        if (text.isEmpty() || text.length() > 9) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return null;
            }
        }
        if (text.length() > 1 && text.charAt(0) == '0') {
            return null;
        }
        int number = Integer.parseInt(text);
        return number >= 1 ? number : null;
    }
}
