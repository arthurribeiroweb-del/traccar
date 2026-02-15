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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class OilChangeEventHandler extends BaseEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OilChangeEventHandler.class);

    private static final String ATTRIBUTE_MAINTENANCE = "maintenance";
    private static final String ATTRIBUTE_OIL = "oil";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ODOMETER_CURRENT = "odometerCurrent";
    private static final String KEY_LAST_SERVICE_ODOMETER = "lastServiceOdometer";
    private static final String KEY_LAST_SERVICE_DATE = "lastServiceDate";
    private static final String KEY_INTERVAL_KM = "intervalKm";
    private static final String KEY_INTERVAL_MONTHS = "intervalMonths";
    private static final String KEY_UPDATED_AT = "updatedAt";
    private static final String KEY_BASELINE_DISTANCE_KM = "baselineDistanceKm";
    private static final String KEY_BASELINE_ODOMETER_KM = "baselineOdometerKm";

    private static final String ATTR_OIL_REASON = "oilReason";
    private static final String ATTR_OIL_DUE_KM = "oilDueKm";
    private static final String ATTR_OIL_CURRENT_KM = "oilCurrentKm";
    private static final String ATTR_OIL_DUE_DATE = "oilDueDate";
    private static final String ATTR_MAINTENANCE_NAME = "maintenanceName";
    private static final String ATTR_OIL_KM_REMAINING = "oilKmRemaining";
    private static final String ATTR_OIL_DAYS_REMAINING = "oilDaysRemaining";

    private static final long PRE_DUE_KM_THRESHOLD = 50;
    private static final long PRE_DUE_DAYS_THRESHOLD = 7;

    private final CacheManager cacheManager;
    private final Map<String, LocalDate> notificationDedupeByDay = new ConcurrentHashMap<>();

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
        Long soonKm = dueKm != null ? Math.max(0L, dueKm - PRE_DUE_KM_THRESHOLD) : null;
        Instant soonDate = dueDate != null ? dueDate.minusSeconds(PRE_DUE_DAYS_THRESHOLD * 24L * 60L * 60L) : null;

        Long oldKm = resolveCurrentKm(config, lastPosition);
        Long currentKm = resolveCurrentKm(config, position);
        Instant oldTime = resolvePositionTime(lastPosition);
        Instant newTime = resolvePositionTime(position);
        if (newTime == null) {
            return;
        }

        boolean dueByKm = dueKm != null && currentKm != null && currentKm >= dueKm;
        boolean dueByDate = dueDate != null && !newTime.isBefore(dueDate);
        boolean soonByKm = !dueByKm
                && soonKm != null
                && currentKm != null
                && currentKm >= soonKm
                && (dueKm == null || currentKm < dueKm);
        boolean soonByDate = !dueByDate
                && soonDate != null
                && !newTime.isBefore(soonDate)
                && (dueDate == null || newTime.isBefore(dueDate));

        String cycleKey = cycleKey(device.getId(), dueKm, dueDate);

        if (dueByKm || dueByDate) {
            if (!shouldNotifyToday(cycleKey, Event.TYPE_OIL_CHANGE_DUE, newTime)) {
                logEvaluation(device.getId(), config, dueKm, dueDate, oldKm, currentKm, oldTime, newTime, "due_suppressed");
                return;
            }
            emitEvent(callback, position, currentKm, dueKm, dueDate, dueByKm, dueByDate, Event.TYPE_OIL_CHANGE_DUE);
            logEvaluation(device.getId(), config, dueKm, dueDate, oldKm, currentKm, oldTime, newTime, "due");
            return;
        }

        if (soonByKm || soonByDate) {
            if (!shouldNotifyToday(cycleKey, Event.TYPE_OIL_CHANGE_SOON, newTime)) {
                logEvaluation(device.getId(), config, dueKm, dueDate, oldKm, currentKm, oldTime, newTime, "soon_suppressed");
                return;
            }
            emitEvent(callback, position, currentKm, dueKm, dueDate, soonByKm, soonByDate, Event.TYPE_OIL_CHANGE_SOON);
            logEvaluation(device.getId(), config, dueKm, dueDate, oldKm, currentKm, oldTime, newTime, "soon");
            return;
        }

        logEvaluation(device.getId(), config, dueKm, dueDate, oldKm, currentKm, oldTime, newTime, "none");
    }

    private static void emitEvent(
            Callback callback, Position position, Long currentKm, Long dueKm, Instant dueDate,
            boolean byKm, boolean byDate, String eventType) {
        List<String> reasons = new ArrayList<>();
        if (byKm) {
            reasons.add("km");
        }
        if (byDate) {
            reasons.add("date");
        }

        if (reasons.isEmpty()) {
            return;
        }

        Event event = new Event(eventType, position);
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
            long daysRemaining = Math.round((dueDate.toEpochMilli() - event.getEventTime().getTime())
                    / (24d * 60d * 60d * 1000d));
            event.set(ATTR_OIL_DAYS_REMAINING, daysRemaining);
        }
        if (currentKm != null && dueKm != null) {
            event.set(ATTR_OIL_KM_REMAINING, dueKm - currentKm);
        }
        callback.eventDetected(event);
    }

    private static void logEvaluation(
            long deviceId, OilConfig config, Long dueKm, Instant dueDate,
            Long oldKm, Long currentKm, Instant oldTime, Instant newTime, String decision) {
        LOGGER.debug(
                "oil_maintenance_eval deviceId={} dueKm={} dueDate={} oldKm={} currentKm={} oldTime={} newTime={} "
                        + "intervalKm={} intervalMonths={} decision={}",
                deviceId,
                dueKm,
                dueDate,
                oldKm,
                currentKm,
                oldTime,
                newTime,
                config.intervalKm,
                config.intervalMonths,
                decision);
    }

    private static String cycleKey(long deviceId, Long dueKm, Instant dueDate) {
        return deviceId + "|" + (dueKm != null ? dueKm : "no-km") + "|" + (dueDate != null ? dueDate : "no-date");
    }

    private boolean shouldNotifyToday(String cycleKey, String eventType, Instant when) {
        String key = cycleKey + "|" + eventType;
        LocalDate today = when.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate previous = notificationDedupeByDay.get(key);
        if (today.equals(previous)) {
            return false;
        }
        notificationDedupeByDay.put(key, today);
        return true;
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
        Long baselineKm = baselineDerivedOdometerKm(config, positionKm);

        if (configuredKm == null) {
            if (baselineKm == null) {
                return positionKm;
            }
            return positionKm == null ? baselineKm : Math.max(positionKm, baselineKm);
        }

        Long result = configuredKm;
        if (positionKm != null) {
            result = Math.max(result, positionKm);
        }
        if (baselineKm != null) {
            result = Math.max(result, baselineKm);
        }
        return result;
    }

    private static Long baselineDerivedOdometerKm(OilConfig config, Long positionKm) {
        if (positionKm == null || config.baselineDistanceKm == null || config.baselineOdometerKm == null) {
            return null;
        }
        long traveledKm = Math.max(0L, positionKm - config.baselineDistanceKm);
        return config.baselineOdometerKm + traveledKm;
    }

    private static Long asKmFromMeters(double meters) {
        if (!Double.isFinite(meters) || meters <= 0) {
            return null;
        }
        return Math.round(meters / 1000.0);
    }

    private static Long positionDistanceKm(Position position) {
        return asKmFromMeters(position.getDouble(Position.KEY_TOTAL_DISTANCE));
    }

    private static Long positionOdometerKm(Position position) {
        Long odometerKm = asKmFromMeters(position.getDouble(Position.KEY_ODOMETER));
        Long totalDistanceKm = positionDistanceKm(position);
        if (odometerKm == null) {
            return totalDistanceKm;
        }
        if (totalDistanceKm == null) {
            return odometerKm;
        }
        return Math.max(odometerKm, totalDistanceKm);
    }

    private record OilConfig(
            boolean enabled,
            Long odometerCurrentKm,
            Long lastServiceOdometerKm,
            Instant lastServiceDate,
            Long intervalKm,
            Integer intervalMonths,
            Instant updatedAt,
            Long baselineDistanceKm,
            Long baselineOdometerKm) {

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
            Instant updatedAt = asInstant(oilMap.get(KEY_UPDATED_AT));
            Long baselineDistanceKm = asLong(oilMap.get(KEY_BASELINE_DISTANCE_KM));
            Long baselineOdometerKm = asLong(oilMap.get(KEY_BASELINE_ODOMETER_KM));

            return new OilConfig(
                    enabled,
                    odometerCurrentKm,
                    lastServiceOdometerKm,
                    lastServiceDate,
                    intervalKm,
                    intervalMonths,
                    updatedAt,
                    baselineDistanceKm,
                    baselineOdometerKm);
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
