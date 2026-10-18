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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.drools.ruleserver.model.RuleDefinition;

/**
 * Reads a stored rule body. Documents saved while a branch sat beside {@code when} and {@code then}
 * still carry a top-level {@code branches} array. Each entry was an independent if: its {@code when}
 * chose that entry's {@code then} blocks, and every matching entry ran. Reading folds each entry
 * into a single-if chain on the then block of the same fact. The stored text and the stored DRL
 * are left as they were.
 */
public final class LegacyBranches {

    private LegacyBranches() {
    }

    public static RuleDefinition read(ObjectMapper mapper, String json) throws JsonProcessingException {
        JsonNode root = mapper.readTree(json);
        if (root instanceof ObjectNode object && object.has("branches")) {
            fold(object);
        }
        return mapper.treeToValue(root, RuleDefinition.class);
    }

    private static void fold(ObjectNode root) {
        JsonNode branches = root.get("branches");
        root.remove("branches");
        if (!(branches instanceof ArrayNode list)) {
            return;
        }
        ArrayNode then = thenArray(root);
        for (JsonNode branch : list) {
            if (!(branch instanceof ObjectNode block)) {
                continue;
            }
            if (block.has("then") || (block.has("when") && !block.has("if"))) {
                foldIndependent(then, block);
            } else if (block.has("if")) {
                attachChain(root, then, block);
            }
        }
    }

    /**
     * One old branch can act on more than one fact. Each of those actions becomes its own if on
     * that fact, all of them testing the same condition, so every matching action still runs.
     */
    private static void foldIndependent(ArrayNode then, ObjectNode branch) {
        JsonNode condition = branch.get("when");
        JsonNode actions = branch.get("then");
        if (!(actions instanceof ArrayNode blocks)) {
            return;
        }
        for (JsonNode action : blocks) {
            if (!(action instanceof ObjectNode block)) {
                continue;
            }
            ObjectNode target = findOrCreate(then, text(block.get("binding")));
            ObjectNode arm = target.objectNode();
            if (condition != null && !condition.isNull()) {
                arm.set("when", condition.deepCopy());
            }
            JsonNode assignments = block.get("assignments");
            arm.set("assignments", assignments == null || assignments.isNull() ? arm.arrayNode() : assignments.deepCopy());
            ObjectNode chain = target.objectNode();
            chain.set("if", arm);
            branchesOf(target).add(chain);
            JsonNode nested = block.get("branches");
            if (nested instanceof ArrayNode nestedBranches) {
                for (JsonNode nestedBranch : nestedBranches) {
                    if (nestedBranch != null && !nestedBranch.isNull()) {
                        branchesOf(target).add(nestedBranch.deepCopy());
                    }
                }
            }
        }
    }

    private static void attachChain(ObjectNode root, ArrayNode then, ObjectNode chain) {
        String binding = then.size() == 1 && then.get(0) instanceof ObjectNode only
                ? text(only.get("binding"))
                : soleWhenBinding(root);
        branchesOf(findOrCreate(then, binding)).add(chain.deepCopy());
    }

    private static ArrayNode thenArray(ObjectNode root) {
        JsonNode then = root.get("then");
        if (then instanceof ArrayNode array) {
            return array;
        }
        ArrayNode created = root.arrayNode();
        root.set("then", created);
        return created;
    }

    private static ObjectNode findOrCreate(ArrayNode then, String binding) {
        for (JsonNode node : then) {
            if (node instanceof ObjectNode block && same(text(block.get("binding")), binding)) {
                return block;
            }
        }
        ObjectNode created = then.objectNode();
        if (binding != null) {
            created.put("binding", binding);
        }
        created.set("assignments", created.arrayNode());
        then.add(created);
        return created;
    }

    private static ArrayNode branchesOf(ObjectNode block) {
        JsonNode existing = block.get("branches");
        if (existing instanceof ArrayNode array) {
            return array;
        }
        ArrayNode created = block.arrayNode();
        block.set("branches", created);
        return created;
    }

    private static String soleWhenBinding(ObjectNode root) {
        JsonNode when = root.get("when");
        if (!(when instanceof ArrayNode facts) || facts.size() != 1 || !(facts.get(0) instanceof ObjectNode fact)) {
            return null;
        }
        return text(fact.get("binding"));
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
