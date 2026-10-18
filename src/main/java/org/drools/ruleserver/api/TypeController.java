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

import org.drools.ruleserver.model.FieldDeclaration;
import org.drools.ruleserver.model.TypeDefinition;
import org.drools.ruleserver.model.TypeResponse;
import org.drools.ruleserver.service.FactTypeService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/types", produces = MediaType.APPLICATION_JSON_VALUE)
public class TypeController {

    private final FactTypeService typeService;

    public TypeController(FactTypeService typeService) {
        this.typeService = typeService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TypeResponse> create(@RequestBody TypeDefinition definition) {
        TypeResponse created = typeService.create(definition);
        return ResponseEntity.created(URI.create("/api/types/" + created.name())).body(created);
    }

    @PostMapping(path = "/{name}/fields", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TypeResponse addField(@PathVariable String name, @RequestBody FieldDeclaration field) {
        return typeService.addField(name, field);
    }

    @GetMapping
    public List<TypeResponse> list() {
        return typeService.list();
    }

    @GetMapping("/{name}")
    public TypeResponse get(@PathVariable String name) {
        return typeService.get(name);
    }
}
