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

import java.util.regex.Pattern;

final class ConditionPaths {

    private static final Pattern LOCAL = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final Pattern BOUND = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");

    private ConditionPaths() {
    }

    static boolean local(String path) {
        return path != null && LOCAL.matcher(path).matches();
    }

    static boolean bound(String path) {
        return path != null && BOUND.matcher(path).matches();
    }

    static String binding(String path) {
        return path.substring(0, path.indexOf('.'));
    }

    static String field(String path) {
        return path.substring(path.indexOf('.') + 1);
    }
}
