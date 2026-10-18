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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.drools.ruleserver.model.FactDeclaration;
import org.drools.ruleserver.model.FieldDeclaration;
import org.drools.ruleserver.model.RuleDefinition;
import org.drools.ruleserver.model.WhenBlock;
import org.springframework.stereotype.Component;

@Component
public class TypeExpander {

    /**
     * Full type declarations for the DRL, in an order where a nested type is declared before its parent.
     * The list is not stored on the rule.
     */
    public List<FactDeclaration> expand(RuleDefinition rule, Map<String, FactDeclaration> catalog) {
        Map<String, FactDeclaration> flat = new LinkedHashMap<>();
        if (rule != null && rule.when() != null) {
            for (WhenBlock when : rule.when()) {
                if (when == null || when.type() == null) {
                    continue;
                }
                FactDeclaration source = catalog.get(when.type());
                if (source != null) {
                    flatten(source, catalog, flat, new ArrayDeque<>());
                }
            }
        }
        return new ArrayList<>(flat.values());
    }

    private static void flatten(FactDeclaration source,
                                Map<String, FactDeclaration> catalog,
                                Map<String, FactDeclaration> flat,
                                Deque<String> stack) {
        if (source == null || source.type() == null || flat.containsKey(source.type()) || stack.contains(source.type())) {
            return;
        }
        stack.addLast(source.type());
        List<FieldDeclaration> fields = new ArrayList<>();
        if (source.fields() != null) {
            for (FieldDeclaration field : source.fields()) {
                if (field == null || field.name() == null) {
                    continue;
                }
                if (field.fields() != null && !field.fields().isEmpty()) {
                    String synthetic = source.type() + "_" + field.name();
                    flatten(new FactDeclaration(synthetic, field.fields()), catalog, flat, stack);
                    fields.add(new FieldDeclaration(field.name(), synthetic));
                } else if (field.type() != null && FieldKind.fromName(field.type()) == null) {
                    flatten(catalog.get(field.type()), catalog, flat, stack);
                    fields.add(new FieldDeclaration(field.name(), field.type()));
                } else {
                    fields.add(new FieldDeclaration(field.name(), field.type()));
                }
            }
        }
        stack.removeLast();
        flat.put(source.type(), new FactDeclaration(source.type(), fields));
    }
}
