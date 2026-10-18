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
package org.drools.ruleserver.service;

import java.util.List;
import java.util.Map;

import org.drools.ruleserver.ApiRequestException;
import org.drools.ruleserver.model.ExecuteRequest;
import org.drools.ruleserver.model.ExecutionResponse;
import org.drools.ruleserver.model.ExecutionTarget;
import org.drools.ruleserver.model.FactDeclaration;
import org.drools.ruleserver.rules.RuleExecutionEngine;
import org.drools.ruleserver.rules.RuleValidator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RuleExecutionService {

    private final RuleAuthoringService authoringService;
    private final RuleValidator validator;
    private final RuleExecutionEngine engine;

    public RuleExecutionService(RuleAuthoringService authoringService,
                                RuleValidator validator,
                                RuleExecutionEngine engine) {
        this.authoringService = authoringService;
        this.validator = validator;
        this.engine = engine;
    }

    public ExecutionResponse execute(String key, ExecuteRequest request) {
        return run(authoringService.activeTarget(key), request);
    }

    private ExecutionResponse run(ExecutionTarget target, ExecuteRequest request) {
        ExecuteRequest body = request == null ? new ExecuteRequest() : request;
        Map<String, FactDeclaration> types = engine.declaredTypes(target.drl());
        List<String> errors = validator.validateExecution(types, body);
        if (!errors.isEmpty()) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid execution request", errors);
        }
        return engine.execute(target.ruleId(), target.code(), target.version(), target.definition(), target.drl(), body);
    }
}
