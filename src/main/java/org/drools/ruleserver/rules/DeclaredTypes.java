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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.drools.ruleserver.model.FactDeclaration;
import org.drools.ruleserver.model.FieldDeclaration;
import org.kie.api.KieBase;
import org.kie.api.definition.KiePackage;
import org.kie.api.definition.type.FactField;
import org.kie.api.definition.type.FactType;

/**
 * The stored rule JSON keeps only the computed schemas. Execution reads the full field tree
 * back from the DRL that was compiled when the rule was authored.
 */
public final class DeclaredTypes {

    private DeclaredTypes() {
    }

    public static Map<String, FactDeclaration> from(KieBase kieBase) {
        Map<String, FactDeclaration> types = new LinkedHashMap<>();
        if (kieBase == null) {
            return types;
        }
        KiePackage pkg = kieBase.getKiePackage(DrlGenerator.PACKAGE);
        if (pkg == null) {
            return types;
        }
        for (FactType factType : pkg.getFactTypes()) {
            types.put(factType.getSimpleName(), toDeclaration(factType));
        }
        return types;
    }

    private static FactDeclaration toDeclaration(FactType factType) {
        List<FactField> fields = new ArrayList<>(factType.getFields());
        fields.sort(Comparator.comparingInt(FactField::getIndex));
        List<FieldDeclaration> declarations = new ArrayList<>();
        for (FactField field : fields) {
            declarations.add(new FieldDeclaration(field.getName(), typeName(field.getType())));
        }
        return new FactDeclaration(factType.getSimpleName(), declarations);
    }

    private static String typeName(Class<?> type) {
        if (type == null) {
            return "Object";
        }
        String simple = type.getSimpleName();
        FieldKind kind = FieldKind.fromName(simple);
        return kind == null ? simple : kind.drlName();
    }
}
