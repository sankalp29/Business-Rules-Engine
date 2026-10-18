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
package org.drools.ruleserver.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonAutoDetect(fieldVisibility = Visibility.NONE, getterVisibility = Visibility.NONE,
        isGetterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
public class FieldAssignment {

    private String field;
    private JsonNode value;
    private boolean valueProvided;
    private String expression;

    @JsonProperty("field")
    public String getField() {
        return field;
    }

    @JsonProperty("field")
    public void setField(String field) {
        this.field = field;
    }

    @JsonProperty("expression")
    public String getExpression() {
        return expression;
    }

    @JsonProperty("expression")
    public void setExpression(String expression) {
        this.expression = expression;
    }

    @JsonIgnore
    public boolean hasValue() {
        return valueProvided;
    }

    @JsonIgnore
    public JsonNode valueNode() {
        return value;
    }

    @JsonProperty("value")
    public void setValue(JsonNode value) {
        this.value = value == null || value.isNull() ? NullNode.getInstance() : value;
        this.valueProvided = true;
    }

    @JsonProperty("value")
    public JsonNode getValue() {
        return valueProvided ? value : null;
    }
}
