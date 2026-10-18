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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.drools.ruleserver.ApiRequestException;
import org.drools.ruleserver.model.FieldDeclaration;
import org.drools.ruleserver.persistence.FactTypeEntity;
import org.drools.ruleserver.persistence.FactTypeRepository;
import org.drools.ruleserver.rules.TypeNormalizer;
import org.drools.ruleserver.rules.TypeValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class FactTypeServiceTest {

    @Mock
    private FactTypeRepository repository;

    private FactTypeService service;

    @BeforeEach
    void setUp() {
        service = new FactTypeService(repository, new TypeNormalizer(), new TypeValidator(), new ObjectMapper());
    }

    @Test
    void addFieldAppendsAScalarAndKeepsTheExistingFields() {
        FactTypeEntity customer = stored("Customer",
                "{\"name\":\"Customer\",\"fields\":[{\"name\":\"age\",\"type\":\"int\"}]}");
        FactTypeEntity address = stored("Address",
                "{\"name\":\"Address\",\"fields\":[{\"name\":\"city\",\"type\":\"String\"}]}");
        when(repository.findByName("Customer")).thenReturn(Optional.of(customer));
        when(repository.findAllByOrderByNameAsc()).thenReturn(List.of(address, customer));
        when(repository.save(any(FactTypeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.addField("Customer", new FieldDeclaration("address", "Address"));

        assertThat(response.fields()).containsExactly(
                new FieldDeclaration("age", "int"),
                new FieldDeclaration("address", "Address"));
        assertThat(customer.getDefinition()).contains("\"address\"");
        assertThat(customer.getDefinition()).contains("\"age\"");
    }

    @Test
    void addFieldRejectsADuplicateName() {
        FactTypeEntity customer = stored("Customer",
                "{\"name\":\"Customer\",\"fields\":[{\"name\":\"age\",\"type\":\"int\"}]}");
        when(repository.findByName("Customer")).thenReturn(Optional.of(customer));
        when(repository.findAllByOrderByNameAsc()).thenReturn(List.of(customer));

        assertThatThrownBy(() -> service.addField("Customer", new FieldDeclaration("age", "String")))
                .isInstanceOfSatisfying(ApiRequestException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getDetails()).anyMatch(detail -> detail.contains("declared more than once"));
                });
        verify(repository, never()).save(any());
    }

    @Test
    void addFieldRejectsAnUnknownType() {
        when(repository.findByName("Missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addField("Missing", new FieldDeclaration("age", "int")))
                .isInstanceOfSatisfying(ApiRequestException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
        verify(repository, never()).save(any());
    }

    private static FactTypeEntity stored(String name, String definition) {
        FactTypeEntity entity = new FactTypeEntity();
        entity.setName(name);
        entity.setDefinition(definition);
        entity.setCreatedAt(Instant.parse("2026-10-17T00:00:00Z"));
        return entity;
    }
}
