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
package org.drools.ruleserver;

import java.time.Clock;

import org.drools.ruleserver.rules.EffectiveWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class RuleServerApplication {

    private static final Logger LOG = LoggerFactory.getLogger(RuleServerApplication.class);

    public static void main(String[] args) {
        int feature = Runtime.version().feature();
        if (feature < 17 || feature > 25) {
            throw new IllegalStateException("rule-engine-server requires Java 17 through 25. This process is running Java "
                    + Runtime.version()
                    + ". Set JAVA_HOME to a Java 21 JDK and start again. Example: export JAVA_HOME=$(/usr/libexec/java_home -v 21)");
        }
        SpringApplication.run(RuleServerApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.system(EffectiveWindow.IST);
    }

    @Bean
    CommandLineRunner logEndpoints(org.springframework.core.env.Environment environment) {
        return args -> {
            String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
            LOG.info("Define a type:    POST http://localhost:{}/api/types", port);
            LOG.info("Author a rule:     POST http://localhost:{}/api/rules", port);
            LOG.info("Add a version:     POST http://localhost:{}/api/rules/{code}/versions", port);
            LOG.info("Change dates:      PUT  http://localhost:{}/api/rules/{code}/versions/{version}/dates", port);
            LOG.info("Execute a rule:    POST http://localhost:{}/api/rules/{code}/execute", port);
        };
    }
}
