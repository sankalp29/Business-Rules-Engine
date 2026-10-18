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

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class KieBaseCache {

    private static final int MAX_COMPILED_RULES = 128;

    private final RuleCompiler compiler;
    private final Map<String, CompiledRule> cache = new LinkedHashMap<>(16, 0.75f, true);

    public KieBaseCache(RuleCompiler compiler) {
        this.compiler = compiler;
    }

    public CompiledRule get(String drl) {
        synchronized (this) {
            CompiledRule cached = cache.get(drl);
            if (cached != null) {
                return cached;
            }
        }
        CompiledRule compiled = compiler.compile(drl);
        synchronized (this) {
            CompiledRule cached = cache.get(drl);
            if (cached != null) {
                compiled.dispose();
                return cached;
            }
            cache.put(drl, compiled);
            if (cache.size() > MAX_COMPILED_RULES) {
                String eldestKey = cache.keySet().iterator().next();
                CompiledRule eldest = cache.remove(eldestKey);
                if (eldest == compiled) {
                    cache.put(drl, compiled);
                } else if (eldest != null) {
                    eldest.dispose();
                }
            }
            return compiled;
        }
    }
}
