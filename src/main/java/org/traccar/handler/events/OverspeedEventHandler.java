/*
 * Copyright 2016 - 2025 Anton Tananaev (anton@traccar.org)
 * Copyright 2018 Andrey Kunitsyn (andrey@traccar.org)
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.UnitsConverter;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.session.state.OverspeedProcessor;
import org.traccar.session.state.OverspeedState;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

public class OverspeedEventHandler extends BaseEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OverspeedEventHandler.class);
    private static final String ATTRIBUTE_RADAR = "radar";
    private static final String ATTRIBUTE_RADAR_ACTIVE = "radarActive";
    private static final String ATTRIBUTE_RADAR_SPEED_LIMIT_KPH = "radarSpeedLimitKph";
    private static final String ATTRIBUTE_RADAR_ID = "radarId";
    private static final String ATTRIBUTE_RADAR_NAME = "radarName";
    private static final String ATTRIBUTE_LIMIT_KPH = "limitKph";
    private static final String ATTRIBUTE_SPEED_KPH = "speedKph";

    private final CacheManager cacheManager;
    private final Storage storage;

    private final long minimalDuration;
    private final boolean preferLowest;
    private final double multiplier;
    private final long radarCooldown;

    private final Map<String, Long> radarCooldowns = new ConcurrentHashMap<>();

    private static class GeofenceSpeedLimitSelection {
        private double speedLimitKnots;
        private long geofenceId;
    }

    @Inject
    public OverspeedEventHandler(Config config, CacheManager cacheManager, Storage storage) {
        this.cacheManager = cacheManager;
        this.storage = storage;
        minimalDuration = config.getLong(Keys.EVENT_OVERSPEED_MINIMAL_DURATION) * 1000;
        preferLowest = config.getBoolean(Keys.EVENT_OVERSPEED_PREFER_LOWEST);
        multiplier = config.getDouble(Keys.EVENT_OVERSPEED_THRESHOLD_MULTIPLIER);
        radarCooldown = config.getLong(Keys.EVENT_RADAR_OVERSPEED_COOLDOWN) * 1000;
    }

    private double parseDouble(Geofence geofence, String key) {
        try {
            return geofence.getDouble(key);
        } catch (RuntimeException error) {
            return 0;
        }
    }

    private boolean isRadarEnabled(Geofence geofence) {
        return geofence != null
                && geofence.getBoolean(ATTRIBUTE_RADAR)
                && (!geofence.hasAttribute(ATTRIBUTE_RADAR_ACTIVE) || geofence.getBoolean(ATTRIBUTE_RADAR_ACTIVE));
    }

    private boolean shouldReplaceLimit(double currentSpeedLimit, double selectedSpeedLimit) {
        return currentSpeedLimit > 0 && (selectedSpeedLimit == 0
                || preferLowest && currentSpeedLimit < selectedSpeedLimit
                || !preferLowest && currentSpeedLimit > selectedSpeedLimit);
    }

    private void updateSelection(GeofenceSpeedLimitSelection selection, long geofenceId, double speedLimitKnots) {
        if (shouldReplaceLimit(speedLimitKnots, selection.speedLimitKnots)) {
            selection.speedLimitKnots = speedLimitKnots;
            selection.geofenceId = geofenceId;
        }
    }

    private GeofenceSpeedLimitSelection selectGeofenceSpeedLimit(Position position) {

        GeofenceSpeedLimitSelection regularSelection = new GeofenceSpeedLimitSelection();
        GeofenceSpeedLimitSelection radarSelection = new GeofenceSpeedLimitSelection();

        if (position.getGeofenceIds() != null) {
            for (long geofenceId : position.getGeofenceIds()) {
                Geofence geofence = cacheManager.getObject(Geofence.class, geofenceId);
                if (geofence == null) {
                    continue;
                }

                if (isRadarEnabled(geofence)) {
                    double radarSpeedLimitKph = parseDouble(geofence, ATTRIBUTE_RADAR_SPEED_LIMIT_KPH);
                    double speedLimitKnots = radarSpeedLimitKph > 0
                            ? UnitsConverter.knotsFromKph(radarSpeedLimitKph)
                            : parseDouble(geofence, Keys.EVENT_OVERSPEED_LIMIT.getKey());
                    if (speedLimitKnots > 0) {
                        updateSelection(radarSelection, geofenceId, speedLimitKnots);
                    }
                } else {
                    double speedLimitKnots = parseDouble(geofence, Keys.EVENT_OVERSPEED_LIMIT.getKey());
                    if (speedLimitKnots > 0) {
                        updateSelection(regularSelection, geofenceId, speedLimitKnots);
                    }
                }
            }
        }

        return radarSelection.speedLimitKnots > 0 ? radarSelection : regularSelection;
    }

    private boolean inRadarCooldown(long deviceId, long geofenceId) {
        if (radarCooldown <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        String key = deviceId + ":" + geofenceId;
        Long previous = radarCooldowns.putIfAbsent(key, now);
        if (previous == null) {
            return false;
        }
        if (now - previous < radarCooldown) {
            return true;
        }
        radarCooldowns.put(key, now);
        return false;
    }

    private void appendRadarAttributes(Event event, Geofence geofence) {
        event.set(ATTRIBUTE_RADAR_ID, geofence.getId());
        event.set(ATTRIBUTE_RADAR_NAME, geofence.getName());

        double limitKph = parseDouble(geofence, ATTRIBUTE_RADAR_SPEED_LIMIT_KPH);
        if (limitKph <= 0) {
            double speedLimitKnots = event.getDouble(Position.KEY_SPEED_LIMIT);
            if (speedLimitKnots > 0) {
                limitKph = UnitsConverter.kphFromKnots(speedLimitKnots);
            }
        }
        if (limitKph > 0) {
            event.set(ATTRIBUTE_RADAR_SPEED_LIMIT_KPH, limitKph);
            event.set(ATTRIBUTE_LIMIT_KPH, limitKph);
        }

        double speedKnots = event.getDouble(OverspeedProcessor.ATTRIBUTE_SPEED);
        if (speedKnots > 0) {
            event.set(ATTRIBUTE_SPEED_KPH, UnitsConverter.kphFromKnots(speedKnots));
        }
    }

    @Override
    public void onPosition(Position position, Callback callback) {

        long deviceId = position.getDeviceId();
        Device device = cacheManager.getObject(Device.class, position.getDeviceId());
        if (device == null) {
            return;
        }
        if (!PositionUtil.isLatest(cacheManager, position)) {
            return;
        }

        double speedLimit = AttributeUtil.lookup(cacheManager, Keys.EVENT_OVERSPEED_LIMIT, deviceId);

        double positionSpeedLimit = position.getDouble(Position.KEY_SPEED_LIMIT);
        if (positionSpeedLimit > 0) {
            speedLimit = positionSpeedLimit;
        }

        GeofenceSpeedLimitSelection geofenceSelection = selectGeofenceSpeedLimit(position);
        if (geofenceSelection.speedLimitKnots > 0) {
            speedLimit = geofenceSelection.speedLimitKnots;
        }

        if (speedLimit == 0) {
            return;
        }

        OverspeedState state = OverspeedState.fromDevice(device);
        OverspeedProcessor.updateState(
                state, position, speedLimit, multiplier, minimalDuration, geofenceSelection.geofenceId);
        if (state.isChanged()) {
            state.toDevice(device);
            try {
                storage.updateObject(device, new Request(
                        new Columns.Include("overspeedState", "overspeedTime", "overspeedGeofenceId"),
                        new Condition.Equals("id", device.getId())));
            } catch (StorageException e) {
                LOGGER.warn("Update device overspeed error", e);
            }
        }
        Event event = state.getEvent();
        if (event != null) {
            if (event.getGeofenceId() != 0) {
                Geofence geofence = cacheManager.getObject(Geofence.class, event.getGeofenceId());
                if (isRadarEnabled(geofence)) {
                    if (inRadarCooldown(deviceId, event.getGeofenceId())) {
                        return;
                    }
                    appendRadarAttributes(event, geofence);
                }
            }
            callback.eventDetected(event);
        }
    }

}
