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

import java.util.ArrayList;
import java.util.List;

import org.drools.ruleserver.model.FieldDeclaration;
import org.drools.ruleserver.model.TypeDefinition;
import org.springframework.stereotype.Component;

@Component
public class TypeNormalizer {

    public TypeDefinition normalize(TypeDefinition input) {
        if (input == null) {
            return new TypeDefinition(null, null);
        }
        return new TypeDefinition(trimToNull(input.name()), normalizeFields(input.fields()));
    }

    private static List<FieldDeclaration> normalizeFields(List<FieldDeclaration> fields) {
        if (fields == null) {
            return null;
        }
        List<FieldDeclaration> normalized = new ArrayList<>();
        for (FieldDeclaration field : fields) {
            if (field == null) {
                normalized.add(null);
                continue;
            }
            normalized.add(new FieldDeclaration(trimToNull(field.name()), trimToNull(field.type()), normalizeFields(field.fields())));
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
