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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.drools.ruleserver.model.RejectionNote;
import org.drools.ruleserver.model.StageMark;

/**
 * Draft is always first and Approved is always last. The names between them belong to one
 * immutable workflow version. The progress marks describe only the latest pass: a rejection
 * puts the version back on Draft and clears every earlier step.
 */
public final class ApprovalStages {

    public static final String DRAFT = "DRAFT";
    public static final String APPROVED = "APPROVED";
    public static final int MAX_MIDDLE = 8;

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9 ._-]*");

    private ApprovalStages() {
    }

    public static boolean isDraft(String stage) {
        return DRAFT.equals(stage);
    }

    /**
     * A missing stage is a version stored before approval existed. It already runs.
     */
    public static boolean isApproved(String stage) {
        return stage == null || stage.isBlank() || APPROVED.equals(stage);
    }

    public static String canonical(String stage) {
        if (stage == null || stage.isBlank()) {
            return APPROVED;
        }
        return stage;
    }

    public static String label(String stage) {
        if (stage == null || stage.isBlank() || APPROVED.equals(stage)) {
            return "Approved";
        }
        if (DRAFT.equals(stage)) {
            return "Draft";
        }
        return stage;
    }

    public static Normalized normalize(List<String> raw) {
        List<String> errors = new ArrayList<>();
        List<String> stages = new ArrayList<>();
        if (raw == null) {
            return new Normalized(List.of(), List.of());
        }
        if (raw.size() > MAX_MIDDLE) {
            errors.add("An approval path can have at most 8 stages between draft and approved.");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String item = raw.get(i);
            String label = "Stage " + (i + 1);
            if (item == null || item.isBlank()) {
                errors.add(label + " needs a name.");
                continue;
            }
            String name = item.trim().replaceAll("\\s+", " ");
            if (name.length() > 40) {
                errors.add(label + " must be 40 characters or fewer.");
                continue;
            }
            if (!NAME.matcher(name).matches()) {
                errors.add(label + " must start with a letter and use only letters, digits, spaces, periods, underscores, or hyphens.");
                continue;
            }
            if ("draft".equals(name.toLowerCase(Locale.ROOT)) || "approved".equals(name.toLowerCase(Locale.ROOT))) {
                errors.add(label + " cannot be named Draft or Approved.");
                continue;
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                errors.add(label + " repeats " + name + ".");
                continue;
            }
            stages.add(name);
        }
        if (!errors.isEmpty()) {
            return new Normalized(List.of(), List.copyOf(errors));
        }
        return new Normalized(List.copyOf(stages), List.of());
    }

    /**
     * Draft, then the configured names, then Approved. A current stage that is not on the path is
     * inserted before Approved so a stored version can still be drawn.
     */
    public static List<String> path(List<String> middle, String stage) {
        List<String> path = new ArrayList<>();
        path.add(DRAFT);
        if (middle != null) {
            for (String name : middle) {
                if (name == null || name.isBlank() || DRAFT.equals(name) || APPROVED.equals(name) || path.contains(name)) {
                    continue;
                }
                path.add(name);
            }
        }
        String current = canonical(stage);
        if (!path.contains(current) && !APPROVED.equals(current)) {
            path.add(current);
        }
        path.add(APPROVED);
        return List.copyOf(path);
    }

    public static Presentation present(String stage,
                                       List<String> middle,
                                       String workflowVersion,
                                       String returnedStage,
                                       String returnedComment,
                                       Instant returnedAt) {
        String current = canonical(stage);
        List<String> path = path(middle, current);
        int index = path.indexOf(current);
        if (index < 0) {
            index = path.size() - 1;
        }
        List<StageMark> marks = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            String name = path.get(i);
            String status = i < index ? "complete" : i == index ? "current" : "upcoming";
            marks.add(new StageMark(name, label(name), status));
        }
        RejectionNote note = null;
        if (DRAFT.equals(current) && returnedComment != null && !returnedComment.isBlank()) {
            String from = returnedStage == null ? "" : returnedStage;
            note = new RejectionNote(from, label(from), returnedComment.trim(), returnedAt);
        }
        return new Presentation(
                current,
                label(current),
                DRAFT.equals(current),
                workflowVersion,
                List.copyOf(marks),
                note);
    }

    public record Normalized(List<String> stages, List<String> errors) {

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public record Presentation(
            String stage,
            String stageLabel,
            boolean editable,
            String workflowVersion,
            List<StageMark> stages,
            RejectionNote returnedFrom) {
    }
}
