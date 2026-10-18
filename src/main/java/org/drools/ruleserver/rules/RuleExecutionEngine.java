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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.drools.ruleserver.model.ExecuteRequest;
import org.drools.ruleserver.model.ExecutionResponse;
import org.drools.ruleserver.model.FactDeclaration;
import org.drools.ruleserver.model.FactInput;
import org.drools.ruleserver.model.FactResult;
import org.drools.ruleserver.model.FieldDeclaration;
import org.drools.ruleserver.model.RuleDefinition;
import org.kie.api.KieBase;
import org.kie.api.definition.type.FactType;
import org.kie.api.runtime.KieSession;
import org.springframework.stereotype.Component;

@Component
public class RuleExecutionEngine {

    private final KieBaseCache cache;
    private final ReasoningBuilder reasoningBuilder;

    public RuleExecutionEngine(KieBaseCache cache, ReasoningBuilder reasoningBuilder) {
        this.cache = cache;
        this.reasoningBuilder = reasoningBuilder;
    }

    public Map<String, FactDeclaration> declaredTypes(String drl) {
        return DeclaredTypes.from(cache.get(drl).kieBase());
    }

    public ExecutionResponse execute(long ruleId, String code, RuleDefinition definition, String drl, ExecuteRequest request) {
        return execute(ruleId, code, null, definition, drl, request);
    }

    public ExecutionResponse execute(long ruleId, String code, String version, RuleDefinition definition, String drl, ExecuteRequest request) {
        KieBase kieBase = cache.get(drl).kieBase();
        Map<String, FactDeclaration> types = DeclaredTypes.from(kieBase);
        List<InsertedFact> inserted = new ArrayList<>();
        KieSession session = kieBase.newKieSession();
        try {
            List<FactInput> inputs = request.getFacts() == null ? List.of() : request.getFacts();
            for (FactInput input : inputs) {
                inserted.add(insert(kieBase, session, types, input));
            }
            List<FactSnapshot> before = snapshot(kieBase, types, inserted);
            // Rules stored before branches moved into then declared this global and counted a fire
            // only when an arm ran. New rules leave the global out, so a matching when is a fire.
            boolean tracksBranches = drl.contains("global java.util.List branchHits;");
            List<Integer> branchHits = new ArrayList<>();
            if (tracksBranches) {
                session.setGlobal("branchHits", branchHits);
            }
            boolean activated = session.fireAllRules(1) > 0;
            boolean fired = activated && (!tracksBranches || !branchHits.isEmpty());
            List<FactSnapshot> after = snapshot(kieBase, types, inserted);
            Reasoning reasoning = reasoningBuilder.describe(definition.name(), fired, before, after);
            return new ExecutionResponse(
                    ruleId,
                    code,
                    version,
                    fired,
                    reasoning.text(),
                    toResults(before),
                    toResults(after),
                    reasoning.changes());
        } finally {
            session.dispose();
        }
    }

    private static InsertedFact insert(KieBase kieBase,
                                       KieSession session,
                                       Map<String, FactDeclaration> types,
                                       FactInput input) {
        FactDeclaration declaration = types.get(input.getType().trim());
        FactType factType = kieBase.getFactType(DrlGenerator.PACKAGE, declaration.type());
        if (factType == null) {
            throw new IllegalStateException("Compiled rule is missing declared type " + declaration.type());
        }
        final Object bean;
        try {
            bean = factType.newInstance();
        } catch (InstantiationException | IllegalAccessException ex) {
            throw new IllegalStateException("Could not create fact " + declaration.type(), ex);
        }
        fill(kieBase, types, factType, bean, declaration, input.getValues() == null ? Map.of() : input.getValues());
        session.insert(bean);
        return new InsertedFact(declaration, factType, bean);
    }

    private static void fill(KieBase kieBase,
                             Map<String, FactDeclaration> types,
                             FactType factType,
                             Object bean,
                             FactDeclaration declaration,
                             Map<String, JsonNode> values) {
        for (FieldDeclaration field : declaration.fields()) {
            JsonNode node = values.get(field.name());
            if (node == null || node.isNull() || node.isMissingNode()) {
                continue;
            }
            FieldKind kind = FieldKind.fromName(field.type());
            if (kind != null) {
                factType.set(bean, field.name(), JsonValues.toJava(kind, node));
                continue;
            }
            FactDeclaration nested = types.get(field.type());
            FactType nestedType = kieBase.getFactType(DrlGenerator.PACKAGE, field.type());
            Object child = newBean(nestedType, field.type());
            fill(kieBase, types, nestedType, child, nested, objectValues(node));
            factType.set(bean, field.name(), child);
        }
    }

    private static Map<String, JsonNode> objectValues(JsonNode node) {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        node.fieldNames().forEachRemaining(name -> values.put(name, node.get(name)));
        return values;
    }

    private static List<FactResult> toResults(List<FactSnapshot> snapshots) {
        List<FactResult> results = new ArrayList<>();
        for (FactSnapshot snapshot : snapshots) {
            results.add(new FactResult(snapshot.type(), snapshot.values()));
        }
        return results;
    }

    private static List<FactSnapshot> snapshot(KieBase kieBase, Map<String, FactDeclaration> types, List<InsertedFact> inserted) {
        List<FactSnapshot> snapshots = new ArrayList<>();
        for (InsertedFact fact : inserted) {
            snapshots.add(new FactSnapshot(fact.declaration.type(), readValues(kieBase, types, fact.factType, fact.bean, fact.declaration)));
        }
        return snapshots;
    }

    private static Map<String, Object> readValues(KieBase kieBase,
                                                  Map<String, FactDeclaration> types,
                                                  FactType factType,
                                                  Object bean,
                                                  FactDeclaration declaration) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (FieldDeclaration field : declaration.fields()) {
            Object value = factType.get(bean, field.name());
            if (FieldKind.fromName(field.type()) != null || value == null) {
                values.put(field.name(), value);
                continue;
            }
            FactDeclaration nested = types.get(field.type());
            FactType nestedType = kieBase.getFactType(DrlGenerator.PACKAGE, field.type());
            values.put(field.name(), readValues(kieBase, types, nestedType, value, nested));
        }
        return values;
    }

    private static Object newBean(FactType factType, String type) {
        try {
            return factType.newInstance();
        } catch (InstantiationException | IllegalAccessException ex) {
            throw new IllegalStateException("Could not create fact " + type, ex);
        }
    }

    private record InsertedFact(FactDeclaration declaration, FactType factType, Object bean) {
    }
}
