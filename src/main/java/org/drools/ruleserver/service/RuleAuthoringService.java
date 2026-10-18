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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.drools.ruleserver.ApiRequestException;
import org.drools.ruleserver.model.DecisionRequest;
import org.drools.ruleserver.model.EffectiveDates;
import org.drools.ruleserver.model.EffectiveSegment;
import org.drools.ruleserver.model.EffectiveSegmentRequest;
import org.drools.ruleserver.model.ExecutionTarget;
import org.drools.ruleserver.model.FactDeclaration;
import org.drools.ruleserver.model.RuleDefinition;
import org.drools.ruleserver.model.RuleResponse;
import org.drools.ruleserver.model.RuleSubmission;
import org.drools.ruleserver.model.RuleVersionResponse;
import org.drools.ruleserver.rules.ApprovalStages;
import org.drools.ruleserver.persistence.AuthoredRule;
import org.drools.ruleserver.persistence.AuthoredRuleRepository;
import org.drools.ruleserver.persistence.RuleVersion;
import org.drools.ruleserver.persistence.RuleVersionRepository;
import org.drools.ruleserver.rules.DrlGenerator;
import org.drools.ruleserver.rules.EffectiveSchedule;
import org.drools.ruleserver.rules.EffectiveWindow;
import org.drools.ruleserver.rules.KieBaseCache;
import org.drools.ruleserver.rules.LegacyBranches;
import org.drools.ruleserver.rules.RuleNormalizer;
import org.drools.ruleserver.rules.RuleSchemas;
import org.drools.ruleserver.rules.RuleValidator;
import org.drools.ruleserver.rules.TypeExpander;
import org.drools.ruleserver.rules.VersionLabels;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuleAuthoringService {

    private static final Instant LEGACY_FROM = Instant.EPOCH;

    private final AuthoredRuleRepository repository;
    private final RuleVersionRepository versionRepository;
    private final FactTypeService typeService;
    private final RuleNormalizer normalizer;
    private final RuleValidator validator;
    private final TypeExpander expander;
    private final DrlGenerator generator;
    private final KieBaseCache cache;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ApprovalService approvalService;

    public RuleAuthoringService(AuthoredRuleRepository repository,
                                RuleVersionRepository versionRepository,
                                FactTypeService typeService,
                                RuleNormalizer normalizer,
                                RuleValidator validator,
                                TypeExpander expander,
                                DrlGenerator generator,
                                KieBaseCache cache,
                                ObjectMapper objectMapper,
                                Clock clock,
                                ApprovalService approvalService) {
        this.repository = repository;
        this.versionRepository = versionRepository;
        this.typeService = typeService;
        this.normalizer = normalizer;
        this.validator = validator;
        this.expander = expander;
        this.generator = generator;
        this.cache = cache;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.approvalService = approvalService;
    }

    @Transactional
    public RuleResponse create(RuleSubmission request) {
        RuleDefinition definition = normalizer.normalize(request == null ? null : request.definition());
        Checked checked = check(definition, request, List.of());
        if (repository.findByCode(definition.code()).isPresent()) {
            throw duplicate(definition.code());
        }
        Prepared prepared = compile(checked);
        AuthoredRule entity = new AuthoredRule();
        entity.setCode(prepared.stored().code());
        entity.setName(prepared.stored().name());
        entity.setDescription(prepared.stored().description());
        entity.setDefinition(prepared.json());
        entity.setDrl(prepared.drl());
        entity.setCreatedAt(clock.instant());
        try {
            AuthoredRule saved = repository.saveAndFlush(entity);
            RuleVersion version = saveVersion(saved, 1, prepared);
            return respond(saved, List.of(filled(version, prepared.stored())));
        } catch (DataIntegrityViolationException ex) {
            throw duplicate(definition.code());
        }
    }

    /**
     * Adds the next version. Code and name stay on the rule. Description, salience, when, then,
     * and the effective dates belong to this version and may differ from the versions before it.
     */
    @Transactional
    public RuleVersionResponse addVersion(String key, RuleSubmission request) {
        AuthoredRule rule = require(key);
        rejectIdentityChange(rule, request);
        RuleSubmission body = request == null
                ? new RuleSubmission(null, null, null, null, null, null, null, null, null, null, null)
                : request;
        RuleDefinition definition = normalizer.normalize(new RuleDefinition(
                codeForValidation(rule),
                rule.getName(),
                body.description(),
                body.salience(),
                null,
                null,
                body.when(),
                body.then()));
        List<FilledVersion> existing = load(rule);
        Checked checked = check(definition, body, existing);
        Prepared prepared = compile(checked);
        int next = existing.stream().mapToInt(FilledVersion::number).max().orElse(0) + 1;
        if (existing.size() == 1 && existing.get(0).legacyOpen()) {
            closeLegacy(rule, existing.get(0), prepared.schedule());
            next = 2;
        }
        for (Rewrite rewrite : checked.rewrites()) {
            RuleVersion entity = versionRepository.findByRule_IdAndVersionNumber(rule.getId(), rewrite.number())
                    .orElseThrow(() -> new IllegalStateException("Version " + rewrite.number() + " disappeared during save"));
            applySchedule(entity, rewrite.schedule());
            versionRepository.saveAndFlush(entity);
        }
        try {
            RuleVersion saved = saveVersion(rule, next, prepared);
            rule.setDescription(prepared.stored().description());
            rule.setDefinition(prepared.json());
            rule.setDrl(prepared.drl());
            repository.saveAndFlush(rule);
            return render(filled(saved, prepared.stored()));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid rule",
                    List.of("that version could not be saved because it conflicts with another save"));
        }
    }

    /**
     * Replaces the description, salience, when, and then of one version. Dates stay on
     * {@link #changeDates}. Both are refused unless the version is in draft.
     */
    @Transactional
    public RuleVersionResponse updateVersion(String key, String token, RuleSubmission request) {
        AuthoredRule rule = require(key);
        FilledVersion current = requireVersion(rule, token);
        requireDraft(current);
        rejectIdentityChange(rule, request);
        RuleSubmission body = request == null
                ? new RuleSubmission(null, null, null, null, null, null, null, null, null, null, null)
                : request;
        RuleDefinition definition = normalizer.normalize(new RuleDefinition(
                codeForValidation(rule),
                rule.getName(),
                body.description(),
                body.salience(),
                null,
                null,
                body.when(),
                body.then()));
        Map<String, FactDeclaration> catalog = typeService.catalog();
        List<String> errors = new ArrayList<>(validator.validateForAuthoring(definition, catalog));
        if (!errors.isEmpty()) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid rule", errors);
        }
        Prepared prepared = compile(new Checked(definition, current.schedule(), catalog, List.of()));
        RuleVersion entity = entityOf(rule, current);
        entity.setDescription(prepared.stored().description());
        entity.setDefinition(prepared.json());
        entity.setDrl(prepared.drl());
        try {
            RuleVersion saved = versionRepository.saveAndFlush(entity);
            if (current.number() == maxNumber(load(rule))) {
                rule.setDescription(prepared.stored().description());
                rule.setDefinition(prepared.json());
                rule.setDrl(prepared.drl());
                repository.saveAndFlush(rule);
            }
            return render(filled(saved, prepared.stored()));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid rule",
                    List.of("that version could not be saved because it conflicts with another save"));
        }
    }

    /**
     * Approve advances one stage on the path this version was created with. Reject, with a
     * comment, returns a version that is not yet approved to draft. Approved is the last stage,
     * so it cannot advance or be sent back. A version stored before approval counts as approved
     * for execution and can still be sent back to draft so its dates can be set.
     */
    @Transactional
    public RuleVersionResponse decide(String key, String token, DecisionRequest request) {
        AuthoredRule rule = require(key);
        FilledVersion current = requireVersion(rule, token);
        RuleVersion entity = entityOf(rule, current);
        if (entity.getId() == null) {
            entity = versionRepository.saveAndFlush(entity);
        }
        approvalService.decide(entity, request);
        try {
            RuleVersion saved = versionRepository.saveAndFlush(entity);
            return render(filled(saved, current.definition()));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid decision",
                    List.of("that decision could not be saved because it conflicts with another save"));
        }
    }

    @Transactional(readOnly = true)
    public RuleResponse get(String key) {
        AuthoredRule rule = require(key);
        return respond(rule, load(rule));
    }

    @Transactional(readOnly = true)
    public List<RuleResponse> list() {
        List<AuthoredRule> rules = repository.findAllByOrderByIdAsc();
        Map<Long, List<RuleVersion>> stored = fetchVersions(rules);
        List<List<RawVersion>> raw = new ArrayList<>(rules.size());
        boolean missing = false;
        for (AuthoredRule rule : rules) {
            List<RawVersion> versions = rawVersions(rule, stored);
            raw.add(versions);
            for (RawVersion version : versions) {
                missing = missing || missingSchema(version.definition());
            }
        }
        Map<String, FactDeclaration> catalog = missing ? typeService.catalog() : Map.of();
        List<RuleResponse> responses = new ArrayList<>(rules.size());
        for (int i = 0; i < rules.size(); i++) {
            responses.add(respond(rules.get(i), fill(raw.get(i), catalog)));
        }
        return responses;
    }

    @Transactional(readOnly = true)
    public List<RuleVersionResponse> listVersions(String key) {
        AuthoredRule rule = require(key);
        Instant now = clock.instant();
        List<FilledVersion> loaded = load(rule);
        Map<Long, ApprovalService.WorkflowSnapshot> workflows = snapshots(loaded);
        List<RuleVersionResponse> versions = new ArrayList<>();
        for (FilledVersion version : loaded) {
            versions.add(toVersionResponse(version, now, workflows));
        }
        return versions;
    }

    @Transactional(readOnly = true)
    public RuleVersionResponse getVersion(String key, String token) {
        AuthoredRule rule = require(key);
        return render(requireVersion(rule, token));
    }

    /**
     * Replaces the effective segments of one version. A start or end that is already before the
     * current minute stays as it is. A new bound is a whole minute and is not before the current
     * minute. When the new dates overlap another version, that version loses the shared minutes.
     * A period strictly inside it splits it, so it runs again afterwards. A partial overlap
     * shortens or shifts it. A change that would erase the other version, or move a minute that
     * is already before the current minute, is rejected.
     */
    @Transactional
    public RuleVersionResponse changeDates(String key, String token, EffectiveDates request) {
        AuthoredRule rule = require(key);
        Integer number = VersionLabels.number(token);
        if (number == null) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid version", List.of("version must look like v1"));
        }
        List<FilledVersion> versions = load(rule);
        FilledVersion current = null;
        for (FilledVersion version : versions) {
            if (version.number() == number) {
                current = version;
                break;
            }
        }
        if (current == null) {
            throw new ApiRequestException(HttpStatus.NOT_FOUND, "Rule version not found",
                    List.of("No version " + VersionLabels.of(number) + " exists for rule " + ruleLabel(rule)));
        }
        requireDraft(current);
        Instant now = clock.instant();
        EffectiveSchedule.Proposal proposal = EffectiveSchedule.propose(
                rawSegments(request == null ? null : request.effectiveFrom(),
                        request == null ? null : request.effectiveUntil(),
                        request == null ? null : request.segments()),
                current.schedule(),
                now);
        List<String> errors = new ArrayList<>(proposal.errors());
        EffectiveSchedule proposed = proposal.schedule();
        List<Rewrite> rewrites = List.of();
        if (proposed != null) {
            List<FilledVersion> others = new ArrayList<>();
            for (FilledVersion other : versions) {
                if (other.number() != current.number()) {
                    others.add(other);
                }
            }
            Split split = splitAround(proposed, others, now);
            errors.addAll(split.errors());
            rewrites = split.rewrites();
        }
        if (!errors.isEmpty() || proposed == null) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid rule", errors);
        }
        for (Rewrite rewrite : rewrites) {
            RuleVersion rewritten = versionRepository.findByRule_IdAndVersionNumber(rule.getId(), rewrite.number())
                    .orElseThrow(() -> new IllegalStateException("Version " + rewrite.number() + " disappeared during save"));
            applySchedule(rewritten, rewrite.schedule());
            versionRepository.saveAndFlush(rewritten);
        }
        RuleVersion entity = entityOf(rule, current);
        applySchedule(entity, proposed);
        try {
            RuleVersion saved = versionRepository.saveAndFlush(entity);
            return render(filled(saved, current.definition()));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid rule",
                    List.of("that version could not be saved because it conflicts with another save"));
        }
    }

    /**
     * The approved version whose effective dates contain the current timestamp. A version still
     * in draft or in a middle stage does not run, even when its dates include this minute.
     * A version stored before approval existed counts as approved.
     */
    @Transactional(readOnly = true)
    public ExecutionTarget activeTarget(String key) {
        AuthoredRule rule = require(key);
        List<FilledVersion> versions = load(rule);
        Instant now = clock.instant();
        List<FilledVersion> inWindow = new ArrayList<>();
        for (FilledVersion version : versions) {
            if (version.schedule().contains(now)) {
                inWindow.add(version);
            }
        }
        List<FilledVersion> approved = new ArrayList<>();
        for (FilledVersion version : inWindow) {
            if (ApprovalStages.isApproved(version.stage())) {
                approved.add(version);
            }
        }
        if (approved.isEmpty()) {
            if (!inWindow.isEmpty()) {
                List<String> details = new ArrayList<>();
                for (FilledVersion version : inWindow) {
                    details.add(notApproved(rule, version));
                }
                throw new ApiRequestException(HttpStatus.CONFLICT, "Rule version is not approved", details);
            }
            throw new ApiRequestException(HttpStatus.CONFLICT, "Rule version is not active",
                    List.of(noneActive(rule, now, versions)));
        }
        if (approved.size() > 1) {
            throw new ApiRequestException(HttpStatus.CONFLICT, "Rule version is not active",
                    List.of(severalActive(rule, now, approved)));
        }
        return target(rule, approved.get(0));
    }

    /**
     * Resolves a rule by its code, then by numeric id. A code never matches an all-digit key,
     * so an older row whose code is null remains reachable at {@code /api/rules/{id}}.
     */
    @Transactional(readOnly = true)
    public AuthoredRule require(String key) {
        if (key == null || key.isBlank()) {
            throw notFound(key);
        }
        return repository.findByCode(key).orElseGet(() -> {
            if (!digits(key)) {
                throw notFound(key);
            }
            try {
                return repository.findById(Long.parseLong(key)).orElseThrow(() -> notFound(key));
            } catch (NumberFormatException ex) {
                throw notFound(key);
            }
        });
    }

    private Checked check(RuleDefinition definition, RuleSubmission request, List<FilledVersion> existing) {
        Map<String, FactDeclaration> catalog = typeService.catalog();
        List<String> errors = new ArrayList<>(validator.validateForAuthoring(definition, catalog));
        Instant now = clock.instant();
        EffectiveSchedule.Proposal proposal = EffectiveSchedule.propose(
                rawSegments(request == null ? null : request.effectiveFrom(),
                        request == null ? null : request.effectiveUntil(),
                        request == null ? null : request.segments()),
                null,
                now);
        errors.addAll(proposal.errors());
        List<Rewrite> rewrites = List.of();
        if (proposal.schedule() != null) {
            Split split = splitAround(proposal.schedule(), existing, now);
            errors.addAll(split.errors());
            rewrites = split.rewrites();
        }
        if (!errors.isEmpty()) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid rule", errors);
        }
        return new Checked(definition, proposal.schedule(), catalog, rewrites);
    }

    /**
     * Gives the incoming minutes to this version. Each other version loses the minutes it shared,
     * including a split that lets it resume afterwards and a partial overlap that shortens or
     * shifts it. A cut that would change a minute already before the current minute is rejected.
     */
    private static Split splitAround(EffectiveSchedule incoming, List<FilledVersion> existing, Instant now) {
        List<String> errors = new ArrayList<>();
        List<Rewrite> rewrites = new ArrayList<>();
        for (FilledVersion other : existing) {
            if (other.legacyOpen() || !incoming.overlaps(other.schedule())) {
                continue;
            }
            EffectiveSchedule.Carve carve = EffectiveSchedule.carve(other.schedule(), incoming, now);
            if (carve.error() != null) {
                errors.add(VersionLabels.of(other.number()) + " cannot be adjusted around this version because " + carve.error());
                continue;
            }
            if (carve.schedule().overlaps(incoming)) {
                errors.add("effective dates overlap " + VersionLabels.of(other.number()) + " (" + other.schedule().describe() + ")");
                continue;
            }
            List<String> locks = EffectiveSchedule.validateChange(other.schedule(), carve.schedule(), now);
            if (!locks.isEmpty()) {
                errors.addAll(locks);
                continue;
            }
            rewrites.add(new Rewrite(other.number(), carve.schedule()));
        }
        return new Split(List.copyOf(rewrites), List.copyOf(errors));
    }

    private Prepared compile(Checked checked) {
        RuleDefinition definition = checked.definition();
        RuleSchemas.Schemas schemas = RuleSchemas.build(definition, checked.catalog());
        RuleDefinition stored = new RuleDefinition(
                definition.code(),
                definition.name(),
                definition.description(),
                definition.salience(),
                schemas.input(),
                schemas.output(),
                definition.when(),
                definition.then());
        String drl = generator.generate(stored, expander.expand(definition, checked.catalog()));
        cache.get(drl);
        return new Prepared(stored, writeJson(stored), drl, checked.schedule());
    }

    private void rejectIdentityChange(AuthoredRule rule, RuleSubmission request) {
        if (request == null) {
            return;
        }
        List<String> errors = new ArrayList<>();
        String code = trimToNull(request.code());
        String name = trimToNull(request.name());
        if (code != null && (rule.getCode() == null || !code.equals(rule.getCode()))) {
            errors.add("code stays the same on every version of a rule");
        }
        if (name != null && (rule.getName() == null || !name.equals(rule.getName()))) {
            errors.add("name stays the same on every version of a rule");
        }
        if (!errors.isEmpty()) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid rule", errors);
        }
    }

    /**
     * A rule saved before versions existed is v1 with no end date. The new version has to start
     * after that open version, and v1 then ends at the minute before the new version starts.
     */
    private void closeLegacy(AuthoredRule rule, FilledVersion legacy, EffectiveSchedule incoming) {
        Instant start = incoming.segments().get(0).from();
        Instant legacyFrom = legacy.schedule().segments().get(0).from();
        if (!start.isAfter(legacyFrom)) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid rule",
                    List.of("effective dates overlap v1 (" + legacy.schedule().describe() + ")"));
        }
        RuleVersion closed = legacy.id() == null
                ? null
                : versionRepository.findByRule_IdAndVersionNumber(rule.getId(), legacy.number()).orElse(null);
        if (closed == null) {
            closed = new RuleVersion();
            closed.setRule(rule);
            closed.setVersionNumber(legacy.number());
            closed.setDescription(rule.getDescription());
            closed.setDefinition(rule.getDefinition());
            closed.setDrl(rule.getDrl());
            closed.setCreatedAt(rule.getCreatedAt() == null ? clock.instant() : rule.getCreatedAt());
            approvalService.markApproved(closed);
        }
        applySchedule(closed, new EffectiveSchedule(List.of(
                new EffectiveWindow(legacyFrom, start.minus(1, ChronoUnit.MINUTES)))));
        versionRepository.saveAndFlush(closed);
    }

    private RuleVersion saveVersion(AuthoredRule rule, int number, Prepared prepared) {
        RuleVersion version = new RuleVersion();
        version.setRule(rule);
        version.setVersionNumber(number);
        version.setDescription(prepared.stored().description());
        version.setDefinition(prepared.json());
        version.setDrl(prepared.drl());
        version.setCreatedAt(clock.instant());
        applySchedule(version, prepared.schedule());
        approvalService.markDraft(version);
        return versionRepository.saveAndFlush(version);
    }

    private List<FilledVersion> load(AuthoredRule rule) {
        Map<Long, List<RuleVersion>> stored = fetchVersions(List.of(rule));
        List<RawVersion> raw = rawVersions(rule, stored);
        boolean missing = false;
        for (RawVersion version : raw) {
            missing = missing || missingSchema(version.definition());
        }
        Map<String, FactDeclaration> catalog = missing ? typeService.catalog() : Map.of();
        return fill(raw, catalog);
    }

    private Map<Long, List<RuleVersion>> fetchVersions(List<AuthoredRule> rules) {
        List<Long> ids = new ArrayList<>();
        for (AuthoredRule rule : rules) {
            if (rule.getId() != null) {
                ids.add(rule.getId());
            }
        }
        Map<Long, List<RuleVersion>> grouped = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return grouped;
        }
        for (RuleVersion version : versionRepository.findByRule_IdInOrderByVersionNumberAsc(ids)) {
            grouped.computeIfAbsent(version.getRule().getId(), ignored -> new ArrayList<>()).add(version);
        }
        return grouped;
    }

    private List<RawVersion> rawVersions(AuthoredRule rule, Map<Long, List<RuleVersion>> stored) {
        List<RuleVersion> rows = rule.getId() == null ? List.of() : stored.getOrDefault(rule.getId(), List.of());
        if (!rows.isEmpty()) {
            List<RawVersion> versions = new ArrayList<>(rows.size());
            for (RuleVersion row : rows) {
                EffectiveSchedule schedule = scheduleOf(row);
                versions.add(new RawVersion(
                        row.getId(),
                        row.getVersionNumber(),
                        row.getDescription(),
                        readJson(row.getDefinition(), rule.getId()),
                        row.getDrl(),
                        schedule,
                        row.getCreatedAt(),
                        openEnded(schedule),
                        row.getStage(),
                        row.getWorkflowId(),
                        row.getReturnedFromStage(),
                        row.getReturnedComment(),
                        row.getReturnedAt()));
            }
            versions.sort(Comparator.comparingInt(RawVersion::number));
            return versions;
        }
        if (rule.getDefinition() != null && !rule.getDefinition().isBlank()) {
            return List.of(new RawVersion(
                    null,
                    1,
                    rule.getDescription(),
                    readJson(rule.getDefinition(), rule.getId()),
                    rule.getDrl(),
                    new EffectiveSchedule(List.of(new EffectiveWindow(LEGACY_FROM, null))),
                    rule.getCreatedAt(),
                    true,
                    null,
                    null,
                    null,
                    null,
                    null));
        }
        return List.of();
    }

    private List<FilledVersion> fill(List<RawVersion> raw, Map<String, FactDeclaration> catalog) {
        List<FilledVersion> filled = new ArrayList<>(raw.size());
        for (RawVersion version : raw) {
            filled.add(new FilledVersion(
                    version.id(),
                    version.number(),
                    version.description(),
                    schemasFor(version.definition(), catalog),
                    version.drl(),
                    version.schedule(),
                    version.createdAt(),
                    version.legacyOpen(),
                    version.stage(),
                    version.workflowId(),
                    version.returnedFromStage(),
                    version.returnedComment(),
                    version.returnedAt()));
        }
        return filled;
    }

    private FilledVersion requireVersion(AuthoredRule rule, String token) {
        Integer number = VersionLabels.number(token);
        if (number == null) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid version", List.of("version must look like v1"));
        }
        for (FilledVersion version : load(rule)) {
            if (version.number() == number) {
                return version;
            }
        }
        throw new ApiRequestException(HttpStatus.NOT_FOUND, "Rule version not found",
                List.of("No version " + VersionLabels.of(number) + " exists for rule " + ruleLabel(rule)));
    }

    private RuleResponse respond(AuthoredRule entity, List<FilledVersion> versions) {
        Instant now = clock.instant();
        List<RuleVersionResponse> rendered = new ArrayList<>(versions.size());
        List<RuleVersionResponse> active = new ArrayList<>();
        Map<Long, ApprovalService.WorkflowSnapshot> workflows = snapshots(versions);
        for (FilledVersion version : versions) {
            RuleVersionResponse response = toVersionResponse(version, now, workflows);
            rendered.add(response);
            if (response.active()) {
                active.add(response);
            }
        }
        RuleVersionResponse chosen = active.size() == 1 ? active.get(0) : null;
        return new RuleResponse(
                entity.getId(),
                entity.getCode(),
                entity.getName(),
                chosen == null ? null : chosen.description(),
                chosen == null ? null : chosen.definition(),
                chosen == null ? null : chosen.drl(),
                entity.getCreatedAt(),
                chosen == null ? null : chosen.version(),
                rendered);
    }

    private RuleVersionResponse render(FilledVersion version) {
        return toVersionResponse(version, clock.instant(), snapshots(List.of(version)));
    }

    private RuleVersionResponse toVersionResponse(FilledVersion version,
                                                  Instant now,
                                                  Map<Long, ApprovalService.WorkflowSnapshot> workflows) {
        List<EffectiveSegment> segments = new ArrayList<>();
        for (EffectiveWindow window : version.schedule().segments()) {
            segments.add(new EffectiveSegment(window.from(), window.until()));
        }
        EffectiveWindow first = version.schedule().segments().get(0);
        EffectiveWindow last = version.schedule().segments().get(version.schedule().segments().size() - 1);
        ApprovalService.WorkflowSnapshot workflow = version.workflowId() == null ? null : workflows.get(version.workflowId());
        ApprovalStages.Presentation approval = ApprovalStages.present(
                version.stage(),
                workflow == null ? List.of() : workflow.middle(),
                workflow == null ? null : workflow.version(),
                version.returnedFromStage(),
                version.returnedComment(),
                version.returnedAt());
        return new RuleVersionResponse(
                version.id(),
                version.number(),
                VersionLabels.of(version.number()),
                version.description(),
                first.from(),
                last.until(),
                List.copyOf(segments),
                version.schedule().contains(now),
                version.definition(),
                version.drl(),
                version.createdAt(),
                approval.stage(),
                approval.stageLabel(),
                approval.editable(),
                approval.workflowVersion(),
                approval.stages(),
                approval.returnedFrom());
    }

    private FilledVersion filled(RuleVersion version, RuleDefinition definition) {
        EffectiveSchedule schedule = scheduleOf(version);
        return new FilledVersion(
                version.getId(),
                version.getVersionNumber(),
                version.getDescription(),
                definition,
                version.getDrl(),
                schedule,
                version.getCreatedAt(),
                openEnded(schedule),
                version.getStage(),
                version.getWorkflowId(),
                version.getReturnedFromStage(),
                version.getReturnedComment(),
                version.getReturnedAt());
    }

    private RuleVersion entityOf(AuthoredRule rule, FilledVersion current) {
        if (current.id() != null) {
            return versionRepository.findByRule_IdAndVersionNumber(rule.getId(), current.number())
                    .orElseThrow(() -> new IllegalStateException("Version " + current.number() + " disappeared during save"));
        }
        RuleVersion entity = new RuleVersion();
        entity.setRule(rule);
        entity.setVersionNumber(current.number());
        entity.setDescription(current.description());
        entity.setDefinition(rule.getDefinition());
        entity.setDrl(rule.getDrl());
        entity.setCreatedAt(current.createdAt() == null ? clock.instant() : current.createdAt());
        applySchedule(entity, current.schedule());
        return entity;
    }

    private Map<Long, ApprovalService.WorkflowSnapshot> snapshots(List<FilledVersion> versions) {
        List<Long> ids = new ArrayList<>();
        for (FilledVersion version : versions) {
            if (version.workflowId() != null && !ids.contains(version.workflowId())) {
                ids.add(version.workflowId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return approvalService.snapshots(ids);
    }

    private static void requireDraft(FilledVersion version) {
        if (!ApprovalStages.isDraft(version.stage())) {
            throw new ApiRequestException(HttpStatus.CONFLICT, "Version is not editable",
                    List.of("Logic and dates can be changed only while the version is in draft."));
        }
    }

    private static int maxNumber(List<FilledVersion> versions) {
        int max = 0;
        for (FilledVersion version : versions) {
            max = Math.max(max, version.number());
        }
        return max;
    }

    private static boolean openEnded(EffectiveSchedule schedule) {
        return schedule.segments().size() == 1 && schedule.segments().get(0).until() == null;
    }

    private EffectiveSchedule scheduleOf(RuleVersion row) {
        String json = row.getEffectiveSegments();
        if (json != null && !json.isBlank()) {
            try {
                List<EffectiveSegment> stored = objectMapper.readValue(json, new TypeReference<List<EffectiveSegment>>() {
                });
                if (stored != null && !stored.isEmpty()) {
                    List<EffectiveWindow> windows = new ArrayList<>();
                    for (EffectiveSegment segment : stored) {
                        windows.add(new EffectiveWindow(segment.effectiveFrom(), segment.effectiveUntil()));
                    }
                    return new EffectiveSchedule(windows);
                }
            } catch (JsonProcessingException | IllegalArgumentException ex) {
                throw new IllegalStateException("Stored rule version " + row.getId() + " has unreadable effective segments", ex);
            }
        }
        return new EffectiveSchedule(List.of(new EffectiveWindow(row.getEffectiveFrom(), row.getEffectiveUntil())));
    }

    private void applySchedule(RuleVersion version, EffectiveSchedule schedule) {
        EffectiveWindow first = schedule.segments().get(0);
        EffectiveWindow last = schedule.segments().get(schedule.segments().size() - 1);
        version.setEffectiveFrom(first.from());
        if (last.until() == null) {
            if (schedule.segments().size() != 1) {
                throw new IllegalArgumentException("effectiveUntil is required");
            }
            // The column is not null. Readers prefer the segments JSON, which keeps the open end.
            version.setEffectiveUntil(first.from().plus(1, ChronoUnit.MINUTES));
        } else {
            version.setEffectiveUntil(last.until());
        }
        version.setEffectiveSegments(writeSegments(schedule));
    }

    private String writeSegments(EffectiveSchedule schedule) {
        List<EffectiveSegment> segments = new ArrayList<>();
        for (EffectiveWindow window : schedule.segments()) {
            segments.add(new EffectiveSegment(window.from(), window.until()));
        }
        try {
            return objectMapper.writeValueAsString(segments);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not store the effective segments", ex);
        }
    }

    private static List<EffectiveSchedule.RawSegment> rawSegments(String from,
                                                                  String until,
                                                                  List<EffectiveSegmentRequest> segments) {
        if (segments != null) {
            List<EffectiveSchedule.RawSegment> raw = new ArrayList<>();
            for (EffectiveSegmentRequest segment : segments) {
                if (segment == null) {
                    raw.add(new EffectiveSchedule.RawSegment(null, null));
                } else {
                    raw.add(new EffectiveSchedule.RawSegment(segment.effectiveFrom(), segment.effectiveUntil()));
                }
            }
            return raw;
        }
        return List.of(new EffectiveSchedule.RawSegment(from, until));
    }

    private ExecutionTarget target(AuthoredRule rule, FilledVersion version) {
        RuleDefinition body = version.definition();
        RuleDefinition named = new RuleDefinition(
                rule.getCode(),
                rule.getName(),
                version.description(),
                body.salience(),
                body.inputSchema(),
                body.outputSchema(),
                body.when(),
                body.then());
        return new ExecutionTarget(rule.getId(), rule.getCode(), VersionLabels.of(version.number()), named, version.drl());
    }

    /**
     * A schema saved with the version stays as written, including an empty list, so a later edit of
     * a type does not change it. A null schema is filled from the rule body and the types as they
     * are now. The stored JSON is not rewritten.
     */
    private RuleDefinition schemasFor(RuleDefinition stored, Map<String, FactDeclaration> catalog) {
        if (!missingSchema(stored)) {
            return stored;
        }
        Map<String, FactDeclaration> types = catalog != null ? catalog : typeService.catalog();
        RuleSchemas.Schemas schemas = RuleSchemas.build(stored, types);
        return new RuleDefinition(
                stored.code(),
                stored.name(),
                stored.description(),
                stored.salience(),
                stored.inputSchema() != null ? stored.inputSchema() : schemas.input(),
                stored.outputSchema() != null ? stored.outputSchema() : schemas.output(),
                stored.when(),
                stored.then());
    }

    private static boolean missingSchema(RuleDefinition stored) {
        return stored.inputSchema() == null || stored.outputSchema() == null;
    }

    private RuleDefinition readJson(String json, Long ruleId) {
        try {
            // Older documents keep an independent-if array beside when and then. Fold it into
            // then-block chains so the row can be read. The stored text stays unchanged.
            return LegacyBranches.read(objectMapper, json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored rule " + ruleId + " has unreadable JSON", ex);
        }
    }

    private String writeJson(RuleDefinition definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not store the rule JSON", ex);
        }
    }

    private static String codeForValidation(AuthoredRule rule) {
        return rule.getCode() == null ? "legacy" : rule.getCode();
    }

    private static String notApproved(AuthoredRule rule, FilledVersion version) {
        return VersionLabels.of(version.number()) + " of " + ruleLabel(rule) + " is in "
                + ApprovalStages.label(version.stage()) + " and cannot run until it is approved.";
    }

    private static String noneActive(AuthoredRule rule, Instant at, List<FilledVersion> versions) {
        StringBuilder message = new StringBuilder();
        message.append("No version of ").append(ruleLabel(rule)).append(" is active at ").append(EffectiveWindow.format(at)).append('.');
        if (!versions.isEmpty()) {
            message.append(" Versions: ");
            for (int i = 0; i < versions.size(); i++) {
                if (i > 0) {
                    message.append("; ");
                }
                FilledVersion version = versions.get(i);
                message.append(VersionLabels.of(version.number())).append(" is effective ").append(version.schedule().describe());
            }
            message.append('.');
        }
        return message.toString();
    }

    private static String severalActive(AuthoredRule rule, Instant at, List<FilledVersion> active) {
        StringBuilder message = new StringBuilder();
        message.append("More than one version of ").append(ruleLabel(rule)).append(" is active at ").append(EffectiveWindow.format(at)).append(": ");
        for (int i = 0; i < active.size(); i++) {
            if (i > 0) {
                message.append(", ");
            }
            message.append(VersionLabels.of(active.get(i).number()));
        }
        message.append('.');
        return message.toString();
    }

    private static String ruleLabel(AuthoredRule rule) {
        return rule.getCode() != null ? rule.getCode() : String.valueOf(rule.getId());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean digits(String key) {
        for (int i = 0; i < key.length(); i++) {
            if (!Character.isDigit(key.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static ApiRequestException duplicate(String code) {
        return new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid rule", List.of("code " + code + " is already used"));
    }

    static ApiRequestException notFound(String key) {
        return new ApiRequestException(HttpStatus.NOT_FOUND, "Rule not found", List.of("No rule exists with code or id " + key));
    }

    private record Checked(RuleDefinition definition,
                           EffectiveSchedule schedule,
                           Map<String, FactDeclaration> catalog,
                           List<Rewrite> rewrites) {
    }

    private record Prepared(RuleDefinition stored, String json, String drl, EffectiveSchedule schedule) {
    }

    private record Rewrite(int number, EffectiveSchedule schedule) {
    }

    private record Split(List<Rewrite> rewrites, List<String> errors) {
    }

    private record RawVersion(
            Long id,
            int number,
            String description,
            RuleDefinition definition,
            String drl,
            EffectiveSchedule schedule,
            Instant createdAt,
            boolean legacyOpen,
            String stage,
            Long workflowId,
            String returnedFromStage,
            String returnedComment,
            Instant returnedAt) {
    }

    private record FilledVersion(
            Long id,
            int number,
            String description,
            RuleDefinition definition,
            String drl,
            EffectiveSchedule schedule,
            Instant createdAt,
            boolean legacyOpen,
            String stage,
            Long workflowId,
            String returnedFromStage,
            String returnedComment,
            Instant returnedAt) {
    }
}
