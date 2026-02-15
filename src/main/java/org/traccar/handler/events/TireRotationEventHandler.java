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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tire rotation notifier. Evaluates per position and emits events when entering SOON/OVERDUE windows.
 */
public class TireRotationEventHandler extends BaseEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TireRotationEventHandler.class);

    private static final String ATTR_MAINTENANCE = "maintenance";
    private static final String ATTR_TIRE = "tireRotation";

    private static final String KEY_INTERVAL_KM = "intervalKm";
    private static final String KEY_REMINDER_KM = "reminderThresholdKm";
    private static final String KEY_LAST_ODOMETER = "lastRotationOdometerKm";
    private static final String KEY_LAST_DATE = "lastRotationDate";
    private static final String KEY_LAST_STATE = "lastNotifiedState";
    private static final String KEY_LAST_AT = "lastNotifiedAt";

    private static final long DEFAULT_INTERVAL_KM = 8000;
    private static final long DEFAULT_REMINDER_KM = 1000;

    private final CacheManager cacheManager;
    private final Map<Long, StateCache> stateByDevice = new ConcurrentHashMap<>();

    @Inject
    public TireRotationEventHandler(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        Device device = cacheManager.getObject(Device.class, position.getDeviceId());
        if (device == null || device.getAttributes() == null) {
            return;
        }
        Object maintenance = device.getAttributes().get(ATTR_MAINTENANCE);
        if (!(maintenance instanceof Map<?, ?> maintenanceMap)) {
            return;
        }
        Object tire = maintenanceMap.get(ATTR_TIRE);
        if (!(tire instanceof Map<?, ?> tireMap)) {
            return;
        }

        TireConfig config = TireConfig.from(tireMap);
        if (config.lastRotationOdometerKm == null) {
            return; // not configured
        }

        Long currentKm = positionOdometerKm(position);
        if (currentKm == null) {
            return;
        }

        Schedule schedule = computeSchedule(config, currentKm);

        String newState = schedule.status.name();
        StateCache cache = stateByDevice.computeIfAbsent(position.getDeviceId(), k -> new StateCache());
        if (newState.equals(cache.lastNotifiedState)) {
            return; // dedupe in-memory
        }

        if (schedule.status == Status.OK) {
            cache.lastNotifiedState = newState;
            return;
        }

        Event event = new Event(
                schedule.status == Status.OVERDUE ? Event.TYPE_TIRE_ROTATION_DUE : Event.TYPE_TIRE_ROTATION_SOON,
                position);
        event.set("tireStatus", schedule.status.name());
        event.set("tireNextKm", schedule.nextDueOdometerKm);
        event.set("tireKmRemaining", schedule.kmRemaining);
        event.set("tireIntervalKm", config.intervalKm);
        event.set("tireReminderKm", config.reminderThresholdKm);
        callback.eventDetected(event);

        cache.lastNotifiedState = newState;
    }

    private static Long positionOdometerKm(Position position) {
        Double odometer = position.getDouble(Position.KEY_ODOMETER);
        if (odometer == null) {
            Double totalDistance = position.getDouble(Position.KEY_TOTAL_DISTANCE);
            odometer = totalDistance;
        }
        if (odometer == null) {
            return null;
        }
        long km = Math.round(odometer / 1000.0);
        return km > 0 ? km : 0;
    }

    static Schedule computeSchedule(TireConfig config, long currentKm) {
        long interval = config.intervalKm != null && config.intervalKm > 0 ? config.intervalKm : DEFAULT_INTERVAL_KM;
        long reminder = config.reminderThresholdKm != null && config.reminderThresholdKm > 0
                ? config.reminderThresholdKm : DEFAULT_REMINDER_KM;
        long nextKm = config.lastRotationOdometerKm + interval;
        long remaining = nextKm - currentKm;
        Status status;
        if (remaining > reminder) {
            status = Status.OK;
        } else if (remaining > 0) {
            status = Status.DUE_SOON;
        } else {
            status = Status.OVERDUE;
        }
        return new Schedule(nextKm, remaining, status);
    }

    enum Status {
        OK,
        DUE_SOON,
        OVERDUE
    }

    record Schedule(long nextDueOdometerKm, long kmRemaining, Status status) {
    }

    record TireConfig(
            Long intervalKm,
            Long reminderThresholdKm,
            Long lastRotationOdometerKm,
            Instant lastRotationDate,
            String lastNotifiedState,
            Instant lastNotifiedAt) {

        static TireConfig from(Map<?, ?> tireMap) {
            return new TireConfig(
                    asLong(tireMap.get(KEY_INTERVAL_KM)),
                    asLong(tireMap.get(KEY_REMINDER_KM)),
                    asLong(tireMap.get(KEY_LAST_ODOMETER)),
                    asInstant(tireMap.get(KEY_LAST_DATE)),
                    asString(tireMap.get(KEY_LAST_STATE)),
                    asInstant(tireMap.get(KEY_LAST_AT)));
        }

        private static Long asLong(Object value) {
            if (value == null) return null;
            if (value instanceof Number n) return Math.round(n.doubleValue());
            if (value instanceof String s) {
                try {
                    return Math.round(Double.parseDouble(s));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }

        private static Instant asInstant(Object value) {
            if (value instanceof String s && !s.isBlank()) {
                try {
                    return Instant.parse(s);
                } catch (Exception e) {
                    return null;
                }
            }
            return null;
        }

        private static String asString(Object value) {
            return value == null ? null : value.toString();
        }
    }

    private static final class StateCache {
        String lastNotifiedState;
    }
}
