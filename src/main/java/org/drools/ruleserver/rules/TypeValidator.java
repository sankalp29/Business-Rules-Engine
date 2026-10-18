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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.drools.ruleserver.model.FactDeclaration;
import org.drools.ruleserver.model.FieldDeclaration;
import org.drools.ruleserver.model.TypeDefinition;
import org.springframework.stereotype.Component;

@Component
public class TypeValidator {

    static final int MAX_FIELDS = 50;
    static final int MAX_TOTAL_FIELDS = 200;

    public List<String> validate(TypeDefinition definition, Map<String, FactDeclaration> existing) {
        List<String> errors = new ArrayList<>();
        if (definition == null) {
            errors.add("request body is required");
            return errors;
        }
        if (definition.name() == null) {
            errors.add("name is required");
        } else if (!Identifiers.isIdentifier(definition.name())) {
            errors.add("name must be a Java identifier that is not a reserved word");
        } else if (existing.containsKey(definition.name())) {
            errors.add("type " + definition.name() + " is already defined");
        }
        int total = validateFields("fields", definition.fields(), existing, errors);
        if (total > MAX_TOTAL_FIELDS) {
            errors.add("fields cannot contain more than " + MAX_TOTAL_FIELDS + " fields in total");
        }
        if (definition.name() != null && definition.fields() != null) {
            TypeSchemas.Depth depth = TypeSchemas.measure(definition.name(), definition.fields(), existing);
            if (depth.cycle() != null) {
                errors.add("type " + definition.name() + " cycles through " + depth.cycle());
            } else if (depth.value() > TypeSchemas.MAX_NESTING_DEPTH) {
                errors.add("nesting depth is " + depth.value() + "; the maximum is " + TypeSchemas.MAX_NESTING_DEPTH);
            }
            validateSyntheticNames(definition.name(), definition.fields(), existing, errors);
        }
        return errors;
    }

    private static int validateFields(String path,
                                      List<FieldDeclaration> fields,
                                      Map<String, FactDeclaration> existing,
                                      List<String> errors) {
        if (fields == null || fields.isEmpty()) {
            errors.add(path + " must contain at least one field");
            return 0;
        }
        if (fields.size() > MAX_FIELDS) {
            errors.add(path + " cannot contain more than " + MAX_FIELDS + " fields");
        }
        Set<String> names = new HashSet<>();
        int total = 0;
        int limit = Math.min(fields.size(), MAX_FIELDS);
        for (int i = 0; i < limit; i++) {
            FieldDeclaration field = fields.get(i);
            String where = path + "[" + i + "]";
            if (field == null || field.name() == null) {
                errors.add(where + ".name is required");
                continue;
            }
            total++;
            if (!Identifiers.isIdentifier(field.name())) {
                errors.add(where + ".name must be a Java identifier that is not a reserved word");
            } else if (!names.add(field.name())) {
                errors.add(where + ".name " + field.name() + " is declared more than once");
            }
            boolean inline = field.fields() != null && !field.fields().isEmpty();
            boolean typed = field.type() != null;
            if (inline == typed) {
                errors.add(where + " must include either type or fields");
                continue;
            }
            if (inline) {
                total += validateFields(where + ".fields", field.fields(), existing, errors);
                continue;
            }
            if (FieldKind.fromName(field.type()) == null && !existing.containsKey(field.type())) {
                errors.add(where + ".type " + field.type() + " is not a defined type or one of: " + FieldKind.ALLOWED);
            }
        }
        return total;
    }

    private static void validateSyntheticNames(String parent,
                                               List<FieldDeclaration> fields,
                                               Map<String, FactDeclaration> existing,
                                               List<String> errors) {
        if (fields == null) {
            return;
        }
        for (FieldDeclaration field : fields) {
            if (field == null || field.name() == null || field.fields() == null || field.fields().isEmpty()) {
                continue;
            }
            String synthetic = parent + "_" + field.name();
            if (existing.containsKey(synthetic) || synthetic.equals(parent)) {
                errors.add("nested field " + field.name() + " would declare " + synthetic + ", which is already a type");
            }
            validateSyntheticNames(synthetic, field.fields(), existing, errors);
        }
    }
}
