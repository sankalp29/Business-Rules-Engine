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
package org.drools.ruleserver.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads a condition object. A quoted constraint or a list of constraints is remembered as a shape
 * error so authoring can reject it with the other rule errors. The original text is kept so schema
 * computation can still see the fields a stored rule reads.
 */
public class ConditionDeserializer extends JsonDeserializer<Condition> {

    static final int MAX_DEPTH = 10;

    private static final Set<String> PROPERTIES = Set.of("field", "operator", "value", "valueField", "conditions");

    @Override
    public Condition deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        return read(parser.readValueAsTree(), 1);
    }

    private static Condition read(JsonNode node, int depth) {
        if (node == null || node.isNull()) {
            return null;
        }
        Condition condition = new Condition();
        if (!node.isObject()) {
            condition.setShapeError("must be an object with field, operator, and value or valueField");
            condition.setLegacyConstraints(legacyText(node));
            return condition;
        }
        if (depth > MAX_DEPTH) {
            condition.setShapeError("is nested more than " + MAX_DEPTH + " levels deep");
            return condition;
        }
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            if (!PROPERTIES.contains(name)) {
                condition.setShapeError("contains unknown property " + name);
                return condition;
            }
        }
        readText(condition, node, "field", condition::setField, "field must be a string");
        readText(condition, node, "operator", condition::setOperator, "operator must be a string");
        readText(condition, node, "valueField", condition::setValueField, "valueField must be a string");
        if (node.has("value")) {
            condition.setValue(node.get("value"));
        }
        if (node.has("conditions")) {
            JsonNode children = node.get("conditions");
            if (children.isNull()) {
                condition.setConditions(null);
            } else if (!children.isArray()) {
                condition.setShapeError("conditions must be an array");
            } else {
                List<Condition> parsed = new ArrayList<>();
                children.forEach(child -> parsed.add(read(child, depth + 1)));
                condition.setConditions(parsed);
            }
        }
        return condition;
    }

    private static List<String> legacyText(JsonNode node) {
        if (node.isTextual()) {
            return List.of(node.asText());
        }
        if (!node.isArray()) {
            return null;
        }
        List<String> constraints = new ArrayList<>();
        for (JsonNode child : node) {
            if (child != null && child.isTextual()) {
                constraints.add(child.asText());
            }
        }
        return constraints.isEmpty() ? null : constraints;
    }

    private static void readText(Condition condition, JsonNode node, String property, Consumer<String> setter, String error) {
        if (!node.has(property) || node.get(property).isNull()) {
            return;
        }
        JsonNode raw = node.get(property);
        if (!raw.isTextual()) {
            condition.setShapeError(error);
            return;
        }
        setter.accept(raw.asText());
    }
}
