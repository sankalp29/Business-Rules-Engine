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
package org.drools.ruleserver.api;

import java.net.URI;
import java.util.List;

import org.drools.ruleserver.model.DecisionRequest;
import org.drools.ruleserver.model.EffectiveDates;
import org.drools.ruleserver.model.ExecuteRequest;
import org.drools.ruleserver.model.ExecutionResponse;
import org.drools.ruleserver.model.RuleResponse;
import org.drools.ruleserver.model.RuleSubmission;
import org.drools.ruleserver.model.RuleVersionResponse;
import org.drools.ruleserver.service.RuleAuthoringService;
import org.drools.ruleserver.service.RuleExecutionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/rules", produces = MediaType.APPLICATION_JSON_VALUE)
public class RuleController {

    private final RuleAuthoringService authoringService;
    private final RuleExecutionService executionService;

    public RuleController(RuleAuthoringService authoringService, RuleExecutionService executionService) {
        this.authoringService = authoringService;
        this.executionService = executionService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RuleResponse> create(@RequestBody RuleSubmission submission) {
        RuleResponse created = authoringService.create(submission);
        return ResponseEntity.created(URI.create("/api/rules/" + created.code())).body(created);
    }

    @GetMapping
    public List<RuleResponse> list() {
        return authoringService.list();
    }

    @GetMapping("/{key}")
    public RuleResponse get(@PathVariable String key) {
        return authoringService.get(key);
    }

    @GetMapping("/{key}/versions")
    public List<RuleVersionResponse> listVersions(@PathVariable String key) {
        return authoringService.listVersions(key);
    }

    @GetMapping("/{key}/versions/{version}")
    public RuleVersionResponse getVersion(@PathVariable String key, @PathVariable String version) {
        return authoringService.getVersion(key, version);
    }

    @PutMapping(path = "/{key}/versions/{version}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RuleVersionResponse updateVersion(@PathVariable String key,
                                             @PathVariable String version,
                                             @RequestBody RuleSubmission submission) {
        return authoringService.updateVersion(key, version, submission);
    }

    @PutMapping(path = "/{key}/versions/{version}/dates", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RuleVersionResponse changeDates(@PathVariable String key,
                                           @PathVariable String version,
                                           @RequestBody EffectiveDates dates) {
        return authoringService.changeDates(key, version, dates);
    }

    @PostMapping(path = "/{key}/versions/{version}/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RuleVersionResponse decide(@PathVariable String key,
                                      @PathVariable String version,
                                      @RequestBody DecisionRequest request) {
        return authoringService.decide(key, version, request);
    }

    @PostMapping(path = "/{key}/versions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RuleVersionResponse> addVersion(@PathVariable String key, @RequestBody RuleSubmission submission) {
        RuleVersionResponse created = authoringService.addVersion(key, submission);
        return ResponseEntity.created(URI.create("/api/rules/" + key + "/versions/" + created.version())).body(created);
    }

    @PostMapping(path = "/{key}/execute", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ExecutionResponse execute(@PathVariable String key, @RequestBody(required = false) ExecuteRequest request) {
        return executionService.execute(key, request);
    }
}
