/*
 * Copyright 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.api.resource;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Set;

final class DeviceUpdateValidator {

    private static final Set<String> ALLOWED_FIELDS = Set.of("id", "name", "category");

    private DeviceUpdateValidator() {
    }

    static boolean hasOnlyAllowedFields(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("Invalid device payload");
        }

        Iterator<String> fields = payload.fieldNames();
        while (fields.hasNext()) {
            if (!ALLOWED_FIELDS.contains(fields.next())) {
                return false;
            }
        }
        return true;
    }

    static String normalizeName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Name is required");
        }
        return name.trim();
    }

}
