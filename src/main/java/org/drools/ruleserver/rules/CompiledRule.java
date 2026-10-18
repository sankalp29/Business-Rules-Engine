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

import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.ReleaseId;
import org.kie.api.runtime.KieContainer;

public final class CompiledRule {

    private final ReleaseId releaseId;
    private final KieContainer container;
    private final KieBase kieBase;
    private boolean disposed;

    CompiledRule(ReleaseId releaseId, KieContainer container, KieBase kieBase) {
        this.releaseId = releaseId;
        this.container = container;
        this.kieBase = kieBase;
    }

    public KieBase kieBase() {
        return kieBase;
    }

    public synchronized void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        try {
            container.dispose();
        } finally {
            try {
                KieServices.Factory.get().getRepository().removeKieModule(releaseId);
            } catch (RuntimeException ignored) {
                // The repository entry is only a cache. Execution does not need it after dispose.
            }
        }
    }
}
