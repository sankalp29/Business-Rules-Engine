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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.drools.ruleserver.ApiRequestException;
import org.drools.ruleserver.model.FactDeclaration;
import org.drools.ruleserver.model.FieldDeclaration;
import org.drools.ruleserver.model.TypeDefinition;
import org.drools.ruleserver.model.TypeResponse;
import org.drools.ruleserver.persistence.FactTypeEntity;
import org.drools.ruleserver.persistence.FactTypeRepository;
import org.drools.ruleserver.rules.TypeNormalizer;
import org.drools.ruleserver.rules.TypeValidator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FactTypeService {

    private final FactTypeRepository repository;
    private final TypeNormalizer normalizer;
    private final TypeValidator validator;
    private final ObjectMapper objectMapper;

    public FactTypeService(FactTypeRepository repository,
                           TypeNormalizer normalizer,
                           TypeValidator validator,
                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.normalizer = normalizer;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    public TypeResponse create(TypeDefinition request) {
        TypeDefinition definition = normalizer.normalize(request);
        List<String> errors = validator.validate(definition, catalog());
        if (!errors.isEmpty()) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid type", errors);
        }
        FactTypeEntity entity = new FactTypeEntity();
        entity.setName(definition.name());
        entity.setDefinition(writeJson(definition));
        entity.setCreatedAt(Instant.now());
        return toResponse(repository.save(entity), definition);
    }

    @Transactional
    public TypeResponse addField(String name, FieldDeclaration field) {
        if (field == null) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid type", List.of("field is required"));
        }
        FactTypeEntity entity = repository.findByName(name).orElseThrow(() -> notFound(name));
        TypeDefinition current = read(entity);
        List<FieldDeclaration> fields = new ArrayList<>();
        if (current.fields() != null) {
            fields.addAll(current.fields());
        }
        fields.add(field);
        TypeDefinition merged = normalizer.normalize(new TypeDefinition(current.name(), fields));
        Map<String, FactDeclaration> catalog = catalog();
        catalog.remove(merged.name());
        List<String> errors = validator.validate(merged, catalog);
        if (!errors.isEmpty()) {
            throw new ApiRequestException(HttpStatus.BAD_REQUEST, "Invalid type", errors);
        }
        entity.setDefinition(writeJson(merged));
        return toResponse(repository.save(entity), merged);
    }

    @Transactional(readOnly = true)
    public TypeResponse get(String name) {
        FactTypeEntity entity = repository.findByName(name).orElseThrow(() -> notFound(name));
        return toResponse(entity, read(entity));
    }

    @Transactional(readOnly = true)
    public List<TypeResponse> list() {
        return repository.findAllByOrderByNameAsc().stream()
                .map(entity -> toResponse(entity, read(entity)))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, FactDeclaration> catalog() {
        Map<String, FactDeclaration> types = new LinkedHashMap<>();
        for (FactTypeEntity entity : repository.findAllByOrderByNameAsc()) {
            TypeDefinition definition = read(entity);
            types.put(definition.name(), new FactDeclaration(definition.name(), definition.fields()));
        }
        return types;
    }

    private TypeResponse toResponse(FactTypeEntity entity, TypeDefinition definition) {
        return new TypeResponse(definition.name(), definition.fields(), entity.getCreatedAt());
    }

    private TypeDefinition read(FactTypeEntity entity) {
        try {
            return objectMapper.readValue(entity.getDefinition(), TypeDefinition.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored type " + entity.getName() + " has unreadable JSON", ex);
        }
    }

    private String writeJson(TypeDefinition definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not store the type JSON", ex);
        }
    }

    private static ApiRequestException notFound(String name) {
        return new ApiRequestException(HttpStatus.NOT_FOUND, "Type not found", List.of("No type exists with name " + name));
    }
}
