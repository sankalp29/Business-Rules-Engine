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
import java.util.UUID;

import org.drools.compiler.kie.builder.impl.DrlProject;
import org.drools.ruleserver.ApiRequestException;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.builder.Results;
import org.kie.api.runtime.KieContainer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class RuleCompiler {

    public CompiledRule compile(String drl) {
        KieServices services = KieServices.Factory.get();
        ReleaseId releaseId = services.newReleaseId("org.drools.ruleserver", "dynamic", UUID.randomUUID().toString());
        KieFileSystem files = services.newKieFileSystem();
        files.generateAndWritePomXML(releaseId);
        files.write("src/main/resources/rules/dynamic/rule.drl", drl);
        KieBuilder builder = services.newKieBuilder(files);
        builder.buildAll(DrlProject.class);
        Results results = builder.getResults();
        if (results.hasMessages(Message.Level.ERROR)) {
            removeQuietly(services, releaseId);
            List<String> details = new ArrayList<>();
            results.getMessages(Message.Level.ERROR).forEach(message -> details.add(message.getText()));
            details.add(drl);
            throw new ApiRequestException(HttpStatus.UNPROCESSABLE_ENTITY, "Rule compilation failed", details);
        }
        KieContainer container = services.newKieContainer(releaseId);
        return new CompiledRule(releaseId, container, container.getKieBase());
    }

    private static void removeQuietly(KieServices services, ReleaseId releaseId) {
        try {
            services.getRepository().removeKieModule(releaseId);
        } catch (RuntimeException ignored) {
            // A failed build may not have registered a module.
        }
    }
}
