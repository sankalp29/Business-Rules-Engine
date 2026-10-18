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

import java.util.List;

import org.drools.ruleserver.ApiRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiRequestException.class)
    public ResponseEntity<ErrorResponse> badRequest(ApiRequestException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ErrorResponse(exception.getError(), exception.getDetails()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> malformed(HttpMessageNotReadableException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        if (message == null || message.isBlank()) {
            message = "Request body is not valid JSON";
        }
        return ResponseEntity.badRequest().body(new ErrorResponse("Malformed JSON", List.of(trim(message))));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> badPath(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("Invalid request", List.of(exception.getName() + " must be a number")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception exception) {
        LOG.error("Unhandled error", exception);
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return ResponseEntity.internalServerError().body(new ErrorResponse("Internal error", List.of(trim(message))));
    }

    private static String trim(String message) {
        String singleLine = message.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= 500 ? singleLine : singleLine.substring(0, 500);
    }
}
