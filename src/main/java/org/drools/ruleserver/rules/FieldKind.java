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

public enum FieldKind {
    STRING("String", false),
    INT("int", true),
    LONG("long", true),
    DOUBLE("double", true),
    BOOLEAN("boolean", true),
    INTEGER("Integer", false),
    LONG_OBJECT("Long", false),
    DOUBLE_OBJECT("Double", false),
    BOOLEAN_OBJECT("Boolean", false);

    public static final String ALLOWED = "String, int, long, double, boolean, Integer, Long, Double, Boolean";

    private final String drlName;
    private final boolean primitive;

    FieldKind(String drlName, boolean primitive) {
        this.drlName = drlName;
        this.primitive = primitive;
    }

    public String drlName() {
        return drlName;
    }

    public boolean primitive() {
        return primitive;
    }

    public static FieldKind fromName(String name) {
        if (name == null) {
            return null;
        }
        for (FieldKind kind : values()) {
            if (kind.drlName.equals(name)) {
                return kind;
            }
        }
        return null;
    }
}
