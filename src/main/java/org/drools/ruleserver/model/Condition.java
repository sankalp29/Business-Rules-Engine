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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * One when-constraint. A comparison is {@code field operator value} or {@code field operator valueField}.
 * An {@code and} or {@code or} node nests two or more conditions.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(using = ConditionDeserializer.class)
@JsonAutoDetect(fieldVisibility = Visibility.NONE, getterVisibility = Visibility.NONE,
        isGetterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
public class Condition {

    private String field;
    private String operator;
    private JsonNode value;
    private boolean valueProvided;
    private String valueField;
    private List<Condition> conditions;
    private String shapeError;
    private List<String> legacyConstraints;

    public static Condition comparison(String field, String operator, JsonNode value) {
        Condition condition = new Condition();
        condition.setField(field);
        condition.setOperator(operator);
        condition.setValue(value);
        return condition;
    }

    public static Condition valueField(String field, String operator, String valueField) {
        Condition condition = new Condition();
        condition.setField(field);
        condition.setOperator(operator);
        condition.setValueField(valueField);
        return condition;
    }

    public static Condition group(String operator, List<Condition> conditions) {
        Condition condition = new Condition();
        condition.setOperator(operator);
        condition.setConditions(conditions);
        return condition;
    }

    @JsonProperty("field")
    public String getField() {
        return field;
    }

    @JsonProperty("field")
    public void setField(String field) {
        this.field = field;
    }

    @JsonProperty("operator")
    public String getOperator() {
        return operator;
    }

    @JsonProperty("operator")
    public void setOperator(String operator) {
        this.operator = operator;
    }

    @JsonProperty("valueField")
    public String getValueField() {
        return valueField;
    }

    @JsonProperty("valueField")
    public void setValueField(String valueField) {
        this.valueField = valueField;
    }

    @JsonProperty("conditions")
    public List<Condition> getConditions() {
        return conditions;
    }

    @JsonProperty("conditions")
    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
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

    @JsonIgnore
    public String getShapeError() {
        return shapeError;
    }

    @JsonIgnore
    public void setShapeError(String shapeError) {
        if (this.shapeError == null) {
            this.shapeError = shapeError;
        }
    }

    /**
     * Constraint text from a rule stored before a condition was an object, such as {@code age >= 65}.
     * Absent from JSON. Authoring still rejects this shape.
     */
    @JsonIgnore
    public List<String> getLegacyConstraints() {
        return legacyConstraints;
    }

    @JsonIgnore
    public void setLegacyConstraints(List<String> legacyConstraints) {
        this.legacyConstraints = legacyConstraints;
    }
}
