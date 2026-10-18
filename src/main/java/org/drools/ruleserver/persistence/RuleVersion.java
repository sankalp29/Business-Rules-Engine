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
package org.drools.ruleserver.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One authored body of a rule. {@code versionNumber} starts at 1 and is assigned by the server.
 * The rule's code and name live on {@link AuthoredRule} and are the same for every version.
 */
@Entity
@Table(
        name = "rule_versions",
        uniqueConstraints = @UniqueConstraint(name = "uk_rule_versions_rule_number", columnNames = {"rule_id", "version_number"}))
public class RuleVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_id", nullable = false)
    private AuthoredRule rule;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "definition", nullable = false, columnDefinition = "text")
    private String definition;

    @Column(name = "drl", nullable = false, columnDefinition = "text")
    private String drl;

    /**
     * Start of the first segment. Activity uses {@link #effectiveSegments} when that JSON is set.
     */
    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    /**
     * End of the last segment. A version with a gap is not active for the whole span.
     */
    @Column(name = "effective_until", nullable = false)
    private Instant effectiveUntil;

    /**
     * JSON array of {@code {effectiveFrom, effectiveUntil}} segments. Null on a row stored before
     * segments existed; that row has the single window in the columns above.
     */
    @Column(name = "effective_segments", columnDefinition = "text")
    private String effectiveSegments;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public AuthoredRule getRule() {
        return rule;
    }

    public void setRule(AuthoredRule rule) {
        this.rule = rule;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(int versionNumber) {
        this.versionNumber = versionNumber;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDefinition() {
        return definition;
    }

    public void setDefinition(String definition) {
        this.definition = definition;
    }

    public String getDrl() {
        return drl;
    }

    public void setDrl(String drl) {
        this.drl = drl;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(Instant effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public Instant getEffectiveUntil() {
        return effectiveUntil;
    }

    public void setEffectiveUntil(Instant effectiveUntil) {
        this.effectiveUntil = effectiveUntil;
    }

    public String getEffectiveSegments() {
        return effectiveSegments;
    }

    public void setEffectiveSegments(String effectiveSegments) {
        this.effectiveSegments = effectiveSegments;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
