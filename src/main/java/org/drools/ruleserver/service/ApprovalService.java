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

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.drools.ruleserver.ApiRequestException;
import org.drools.ruleserver.model.ApprovalWorkflowRequest;
import org.drools.ruleserver.model.ApprovalWorkflowResponse;
import org.drools.ruleserver.model.DecisionRequest;
import org.drools.ruleserver.persistence.ApprovalWorkflow;
import org.drools.ruleserver.persistence.ApprovalWorkflowRepository;
import org.drools.ruleserver.persistence.RuleVersion;
import org.drools.ruleserver.persistence.RuleVersionTransition;
import org.drools.ruleserver.persistence.RuleVersionTransitionRepository;
import org.drools.ruleserver.rules.ApprovalStages;
import org.drools.ruleserver.rules.VersionLabels;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalService {

    private final ApprovalWorkflowRepository workflows;
    private final RuleVersionTransitionRepository transitions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ApprovalService(ApprovalWorkflowRepository workflows,
                           RuleVersionTransitionRepository transitions,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.workflows = workflows;
        this.transitions = transitions;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public List<ApprovalWorkflowResponse> list() {
        if (workflows.count() == 0) {
            activeWorkflow();
        }
        List<ApprovalWorkflow> stored = workflows.findAllByOrderByVersionNumberDesc();
        List<ApprovalWorkflowResponse> responses = new ArrayList<>(stored.size());
        for (ApprovalWorkflow workflow : stored) {
            responses.add(toResponse(workflow));
        }
        return responses;
    }

    /**
     * Publishes a new path and makes it the one later rule versions follow. Versions already
     * saved keep the path they were given.
     */
    @Transactional
    public ApprovalWorkflowResponse publish(ApprovalWorkflowRequest request) {
        ApprovalStages.Normalized normalized = ApprovalStages.normalize(request == null ? null : request.stages());
        if (!normalized.ok()) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid approval path", normalized.errors());
        }
        workflows.findByActiveTrue().ifPresent(current -> {
            current.setActive(false);
            workflows.saveAndFlush(current);
        });
        int number = workflows.findTopByOrderByVersionNumberDesc().map(workflow -> workflow.getVersionNumber() + 1).orElse(1);
        ApprovalWorkflow created = new ApprovalWorkflow();
        created.setVersionNumber(number);
        created.setActive(true);
        created.setStages(writeStages(normalized.stages()));
        created.setCreatedAt(clock.instant());
        return toResponse(workflows.saveAndFlush(created));
    }

    /**
     * The path a new rule version follows. The first call stores Draft then Approved when nothing
     * has been published yet.
     */
    @Transactional
    public ApprovalWorkflow activeWorkflow() {
        return workflows.findByActiveTrue().orElseGet(this::seed);
    }

    public void markDraft(RuleVersion version) {
        ApprovalWorkflow workflow = activeWorkflow();
        version.setWorkflowId(workflow.getId());
        version.setStage(ApprovalStages.DRAFT);
        clearReturn(version);
    }

    public void markApproved(RuleVersion version) {
        version.setStage(ApprovalStages.APPROVED);
        clearReturn(version);
    }

    /**
     * Approve moves to the next stage on the path this version was created with. Reject, with a
     * comment, returns a version that is not yet approved to draft. Approved is the last stage:
     * it cannot advance and it cannot be sent back. The version must already have an id.
     */
    @Transactional
    public void decide(RuleVersion version, DecisionRequest request) {
        if (version.getId() == null) {
            throw new IllegalStateException("Save the version before recording a decision");
        }
        String action = request == null || request.action() == null ? "" : request.action().trim();
        String stored = version.getStage();
        String stage = stored == null || stored.isBlank() ? ApprovalStages.APPROVED : stored;
        if ("APPROVE".equals(action)) {
            approve(version, stage);
            return;
        }
        if ("REJECT".equals(action)) {
            if (ApprovalStages.APPROVED.equals(stored)) {
                throw new ApiRequestException(HttpStatus.CONFLICT, "Invalid decision",
                        List.of("An approved version cannot be sent back to draft."));
            }
            reject(version, stage, request.comment());
            return;
        }
        throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid decision", List.of("action must be APPROVE or REJECT"));
    }

    @Transactional(readOnly = true)
    public Map<Long, WorkflowSnapshot> snapshots(Collection<Long> ids) {
        Map<Long, WorkflowSnapshot> snapshots = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return snapshots;
        }
        for (ApprovalWorkflow workflow : workflows.findAllById(ids)) {
            snapshots.put(workflow.getId(), new WorkflowSnapshot(
                    workflow.getId(),
                    VersionLabels.of(workflow.getVersionNumber()),
                    readStages(workflow)));
        }
        return snapshots;
    }

    private void approve(RuleVersion version, String stage) {
        List<String> path = ApprovalStages.path(middleOf(version.getWorkflowId()), stage);
        int index = path.indexOf(stage);
        if (index < 0 || index >= path.size() - 1) {
            throw new ApiRequestException(HttpStatus.CONFLICT, "Invalid decision", List.of("This version is already approved."));
        }
        String next = path.get(index + 1);
        record(version, stage, next, "APPROVE", null);
        version.setStage(next);
        clearReturn(version);
    }

    private void reject(RuleVersion version, String stage, String comment) {
        if (ApprovalStages.DRAFT.equals(stage)) {
            throw new ApiRequestException(HttpStatus.CONFLICT, "Invalid decision", List.of("This version is already in draft."));
        }
        String text = comment == null ? "" : comment.trim();
        if (text.isEmpty()) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid decision",
                    List.of("A comment is required when sending a version back to draft."));
        }
        if (text.length() > 2000) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid decision",
                    List.of("Comment must be 2000 characters or fewer."));
        }
        if (version.getWorkflowId() == null) {
            version.setWorkflowId(activeWorkflow().getId());
        }
        record(version, stage, ApprovalStages.DRAFT, "REJECT", text);
        version.setReturnedFromStage(stage);
        version.setReturnedComment(text);
        version.setReturnedAt(clock.instant());
        version.setStage(ApprovalStages.DRAFT);
    }

    private void record(RuleVersion version, String from, String to, String action, String comment) {
        RuleVersionTransition transition = new RuleVersionTransition();
        transition.setRuleVersionId(version.getId());
        transition.setFromStage(from);
        transition.setToStage(to);
        transition.setAction(action);
        transition.setComment(comment);
        transition.setCreatedAt(clock.instant());
        transitions.save(transition);
    }

    private List<String> middleOf(Long workflowId) {
        if (workflowId == null) {
            return List.of();
        }
        return workflows.findById(workflowId).map(this::readStages).orElse(List.of());
    }

    private ApprovalWorkflow seed() {
        int number = workflows.findTopByOrderByVersionNumberDesc().map(workflow -> workflow.getVersionNumber() + 1).orElse(1);
        ApprovalWorkflow created = new ApprovalWorkflow();
        created.setVersionNumber(number);
        created.setActive(true);
        created.setStages(writeStages(List.of()));
        created.setCreatedAt(clock.instant());
        return workflows.saveAndFlush(created);
    }

    private ApprovalWorkflowResponse toResponse(ApprovalWorkflow workflow) {
        List<String> middle = readStages(workflow);
        List<String> labels = new ArrayList<>();
        for (String name : ApprovalStages.path(middle, ApprovalStages.DRAFT)) {
            labels.add(ApprovalStages.label(name));
        }
        return new ApprovalWorkflowResponse(
                workflow.getId(),
                workflow.getVersionNumber(),
                VersionLabels.of(workflow.getVersionNumber()),
                workflow.isActive(),
                List.copyOf(labels),
                middle,
                workflow.getCreatedAt());
    }

    private List<String> readStages(ApprovalWorkflow workflow) {
        String json = workflow.getStages();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> stored = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return stored == null ? List.of() : List.copyOf(stored);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored approval path " + workflow.getId() + " is unreadable", ex);
        }
    }

    private String writeStages(List<String> stages) {
        try {
            return objectMapper.writeValueAsString(stages);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not store the approval path", ex);
        }
    }

    private static void clearReturn(RuleVersion version) {
        version.setReturnedFromStage(null);
        version.setReturnedComment(null);
        version.setReturnedAt(null);
    }

    public record WorkflowSnapshot(Long id, String version, List<String> middle) {
    }
}
