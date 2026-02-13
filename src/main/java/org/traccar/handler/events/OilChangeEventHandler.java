/*
 * Copyright 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.handler.events;

import jakarta.inject.Inject;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OilChangeEventHandler extends BaseEventHandler {

    private static final String ATTRIBUTE_MAINTENANCE = "maintenance";
    private static final String ATTRIBUTE_OIL = "oil";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ODOMETER_CURRENT = "odometerCurrent";
    private static final String KEY_LAST_SERVICE_ODOMETER = "lastServiceOdometer";
    private static final String KEY_LAST_SERVICE_DATE = "lastServiceDate";
    private static final String KEY_INTERVAL_KM = "intervalKm";
    private static final String KEY_INTERVAL_MONTHS = "intervalMonths";

    private static final String ATTR_OIL_REASON = "oilReason";
    private static final String ATTR_OIL_DUE_KM = "oilDueKm";
    private static final String ATTR_OIL_CURRENT_KM = "oilCurrentKm";
    private static final String ATTR_OIL_DUE_DATE = "oilDueDate";
    private static final String ATTR_MAINTENANCE_NAME = "maintenanceName";

    private final CacheManager cacheManager;

    @Inject
    public OilChangeEventHandler(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        Position lastPosition = cacheManager.getPosition(position.getDeviceId());
        if (lastPosition == null || position.getFixTime().compareTo(lastPosition.getFixTime()) < 0) {
            return;
        }

        Device device = cacheManager.getObject(Device.class, position.getDeviceId());
        if (device == null) {
            return;
        }

        OilConfig config = OilConfig.fromDevice(device);
        if (config == null || !config.enabled) {
            return;
        }

        Long dueKm = config.dueKm();
        Instant dueDate = config.dueDate();

        List<String> reasons = new ArrayList<>();
        Long currentKm = resolveCurrentKm(config, position);

        if (dueKm != null) {
            Long oldKm = resolveCurrentKm(config, lastPosition);
            if (oldKm != null && currentKm != null && oldKm < dueKm && currentKm >= dueKm) {
                reasons.add("km");
            }
        }

        if (dueDate != null) {
            Instant oldTime = resolvePositionTime(lastPosition);
            Instant newTime = resolvePositionTime(position);
            if (oldTime != null && newTime != null && oldTime.isBefore(dueDate) && !newTime.isBefore(dueDate)) {
                reasons.add("date");
            }
        }

        if (reasons.isEmpty()) {
            return;
        }

        Event event = new Event(Event.TYPE_OIL_CHANGE_DUE, position);
        event.set(ATTR_OIL_REASON, String.join(",", reasons));
        event.set(ATTR_MAINTENANCE_NAME, "Troca de oleo");
        if (dueKm != null) {
            event.set(ATTR_OIL_DUE_KM, dueKm);
        }
        if (currentKm != null) {
            event.set(ATTR_OIL_CURRENT_KM, currentKm);
        }
        if (dueDate != null) {
            event.set(ATTR_OIL_DUE_DATE, dueDate.toString());
        }
        callback.eventDetected(event);
    }

    private static Instant resolvePositionTime(Position position) {
        if (position.getFixTime() != null) {
            return position.getFixTime().toInstant();
        }
        if (position.getDeviceTime() != null) {
            return position.getDeviceTime().toInstant();
        }
        if (position.getServerTime() != null) {
            return position.getServerTime().toInstant();
        }
        return null;
    }

    private static Long resolveCurrentKm(OilConfig config, Position position) {
        Long configuredKm = config.odometerCurrentKm;
        Long positionKm = positionOdometerKm(position);
        if (configuredKm == null) {
            return positionKm;
        }
        if (positionKm == null) {
            return configuredKm;
        }
        return Math.max(configuredKm, positionKm);
    }

    private static Long positionOdometerKm(Position position) {
        double odometerMeters = Math.max(
                position.getDouble(Position.KEY_ODOMETER),
                position.getDouble(Position.KEY_TOTAL_DISTANCE));
        if (!Double.isFinite(odometerMeters) || odometerMeters <= 0) {
            return null;
        }
        return Math.round(odometerMeters / 1000.0);
    }

    private record OilConfig(
            boolean enabled,
            Long odometerCurrentKm,
            Long lastServiceOdometerKm,
            Instant lastServiceDate,
            Long intervalKm,
            Integer intervalMonths) {

        static OilConfig fromDevice(Device device) {
            if (device.getAttributes() == null) {
                return null;
            }
            Object maintenance = device.getAttributes().get(ATTRIBUTE_MAINTENANCE);
            if (!(maintenance instanceof Map<?, ?> maintenanceMap)) {
                return null;
            }
            Object oil = maintenanceMap.get(ATTRIBUTE_OIL);
            if (!(oil instanceof Map<?, ?> oilMap)) {
                return null;
            }

            boolean enabled = asBoolean(oilMap.get(KEY_ENABLED), true);
            Long odometerCurrentKm = asLong(oilMap.get(KEY_ODOMETER_CURRENT));
            Long lastServiceOdometerKm = asLong(oilMap.get(KEY_LAST_SERVICE_ODOMETER));
            Instant lastServiceDate = asInstant(oilMap.get(KEY_LAST_SERVICE_DATE));
            Long intervalKm = asPositiveLong(oilMap.get(KEY_INTERVAL_KM));
            Integer intervalMonths = asPositiveInt(oilMap.get(KEY_INTERVAL_MONTHS));

            return new OilConfig(
                    enabled,
                    odometerCurrentKm,
                    lastServiceOdometerKm,
                    lastServiceDate,
                    intervalKm,
                    intervalMonths);
        }

        Long dueKm() {
            if (lastServiceOdometerKm == null || intervalKm == null || intervalKm <= 0) {
                return null;
            }
            return lastServiceOdometerKm + intervalKm;
        }

        Instant dueDate() {
            if (lastServiceDate == null || intervalMonths == null || intervalMonths <= 0) {
                return null;
            }
            return ZonedDateTime.ofInstant(lastServiceDate, ZoneOffset.UTC)
                    .plusMonths(intervalMonths)
                    .toInstant();
        }

        private static boolean asBoolean(Object value, boolean defaultValue) {
            if (value == null) {
                return defaultValue;
            }
            if (value instanceof Boolean bool) {
                return bool;
            }
            if (value instanceof String str) {
                return Boolean.parseBoolean(str);
            }
            return defaultValue;
        }

        private static Long asPositiveLong(Object value) {
            Long parsed = asLong(value);
            return parsed != null && parsed > 0 ? parsed : null;
        }

        private static Integer asPositiveInt(Object value) {
            Integer parsed = asInt(value);
            return parsed != null && parsed > 0 ? parsed : null;
        }

        private static Long asLong(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Number number) {
                return Math.round(number.doubleValue());
            }
            if (value instanceof String string) {
                try {
                    return Math.round(Double.parseDouble(string));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }

        private static Integer asInt(Object value) {
            Long parsed = asLong(value);
            if (parsed == null) {
                return null;
            }
            return Math.toIntExact(parsed);
        }

        private static Instant asInstant(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof String text && !text.isBlank()) {
                try {
                    return Instant.parse(text);
                } catch (Exception e) {
                    return null;
                }
            }
            return null;
        }
    }
}
