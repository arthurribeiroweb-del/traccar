/*
 * Copyright 2016 - 2022 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 - 2018 Andrey Kunitsyn (andrey@traccar.org)
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
package org.traccar.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.forward.EventData;
import org.traccar.forward.EventForwarder;
import org.traccar.geocoder.Geocoder;
import org.traccar.helper.DateUtil;
import org.traccar.model.Calendar;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Maintenance;
import org.traccar.model.Position;
import org.traccar.notification.MessageException;
import org.traccar.notification.NotificatorManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

@Singleton
public class NotificationManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationManager.class);
    private static final String LEGACY_TYPE_OVERSPEED = "overspeed";

    private final Storage storage;
    private final CacheManager cacheManager;
    private final EventForwarder eventForwarder;
    private final NotificatorManager notificatorManager;
    private final Geocoder geocoder;

    private final boolean geocodeOnRequest;
    private final long timeThreshold;
    private final Set<Long> blockedUsers = new HashSet<>();

    @Inject
    public NotificationManager(
            Config config, Storage storage, CacheManager cacheManager, @Nullable EventForwarder eventForwarder,
            NotificatorManager notificatorManager, @Nullable Geocoder geocoder) {
        this.storage = storage;
        this.cacheManager = cacheManager;
        this.eventForwarder = eventForwarder;
        this.notificatorManager = notificatorManager;
        this.geocoder = geocoder;
        geocodeOnRequest = config.getBoolean(Keys.GEOCODER_ON_REQUEST);
        timeThreshold = config.getLong(Keys.NOTIFICATOR_TIME_THRESHOLD);
        String blockedUsersString = config.getString(Keys.NOTIFICATION_BLOCK_USERS);
        if (blockedUsersString != null) {
            for (String userIdString : blockedUsersString.split(",")) {
                blockedUsers.add(Long.parseLong(userIdString));
            }
        }
    }

    private void updateEvent(Event event, Position position) {
        try {
            event.setId(storage.addObject(event, new Request(new Columns.Exclude("id"))));
        } catch (StorageException error) {
            LOGGER.warn("Event save error", error);
        }

        forwardEvent(event, position);

        if (System.currentTimeMillis() - event.getEventTime().getTime() > timeThreshold) {
            LOGGER.info("Skipping notifications for old event");
            return;
        }

        var notifications = cacheManager.getDeviceNotifications(event.getDeviceId()).stream()
                .filter(notification -> notificationMatchesEvent(notification.getType(), event.getType()))
                .filter(notification -> {
                    if (event.getType().equals(Event.TYPE_ALARM)) {
                        String alarmsAttribute = notification.getString("alarms");
                        if (alarmsAttribute != null) {
                            return Arrays.asList(alarmsAttribute.split(","))
                                    .contains(event.getString(Position.KEY_ALARM));
                        }
                        return false;
                    }
                    return true;
                })
                .filter(notification -> {
                    long calendarId = notification.getCalendarId();
                    Calendar calendar = calendarId != 0 ? cacheManager.getObject(Calendar.class, calendarId) : null;
                    return calendar == null || calendar.checkMoment(event.getEventTime());
                })
                .toList();

        Device device = cacheManager.getObject(Device.class, event.getDeviceId());
        LOGGER.info(
                "notify_lookup eventId={} device={} eventType={} notifications={}",
                event.getId(),
                device != null ? device.getUniqueId() : event.getDeviceId(),
                event.getType(),
                notifications.size());

        if (!notifications.isEmpty()) {
            if (position != null && position.getAddress() == null && geocodeOnRequest && geocoder != null) {
                position.setAddress(geocoder.getAddress(position.getLatitude(), position.getLongitude(), null));
            }

            notifications.forEach(notification -> {
                var users = cacheManager.getNotificationUsers(notification.getId(), event.getDeviceId());
                LOGGER.info(
                        "notify_dispatch eventId={} notificationId={} type={} users={}",
                        event.getId(),
                        notification.getId(),
                        notification.getType(),
                        users.size());
                users.forEach(user -> {
                    if (blockedUsers.contains(user.getId())) {
                        LOGGER.info("User {} notification blocked", user.getId());
                        return;
                    }
                    for (String notificator : notification.getNotificatorsTypes()) {
                        try {
                            LOGGER.info(
                                    "notify_send eventId={} userId={} notificator={}",
                                    event.getId(), user.getId(), notificator);
                            notificatorManager.getNotificator(notificator).send(notification, user, event, position);
                        } catch (MessageException exception) {
                            LOGGER.warn("Notification failed eventId={} userId={} notificator={}",
                                    event.getId(), user.getId(), notificator, exception);
                        }
                    }
                });
            });
        }
    }

    private boolean notificationMatchesEvent(String notificationType, String eventType) {
        if (eventType.equals(Event.TYPE_DEVICE_OVERSPEED) && LEGACY_TYPE_OVERSPEED.equals(notificationType)) {
            return true;
        }
        if (eventType.equals(Event.TYPE_OIL_CHANGE_SOON) && Event.TYPE_MAINTENANCE.equals(notificationType)) {
            return true;
        }
        if (eventType.equals(Event.TYPE_OIL_CHANGE_DUE) && Event.TYPE_MAINTENANCE.equals(notificationType)) {
            return true;
        }
        if (eventType.equals(Event.TYPE_TIRE_ROTATION_SOON) && Event.TYPE_MAINTENANCE.equals(notificationType)) {
            return true;
        }
        if (eventType.equals(Event.TYPE_TIRE_ROTATION_DUE) && Event.TYPE_MAINTENANCE.equals(notificationType)) {
            return true;
        }
        return eventType.equals(notificationType);
    }

    private void forwardEvent(Event event, Position position) {
        if (eventForwarder != null) {
            EventData eventData = new EventData();
            eventData.setEvent(event);
            eventData.setPosition(position);
            eventData.setDevice(cacheManager.getObject(Device.class, event.getDeviceId()));
            if (event.getGeofenceId() != 0) {
                eventData.setGeofence(cacheManager.getObject(Geofence.class, event.getGeofenceId()));
            }
            if (event.getMaintenanceId() != 0) {
                eventData.setMaintenance(cacheManager.getObject(Maintenance.class, event.getMaintenanceId()));
            }
            eventForwarder.forward(eventData, (success, throwable) -> {
                if (!success) {
                    LOGGER.warn("Event forwarding failed", throwable);
                }
            });
        }
    }

    public void updateEvents(Map<Event, Position> events) {
        for (Entry<Event, Position> entry : events.entrySet()) {
            Event event = entry.getKey();
            Position position = entry.getValue();
            var key = new Object();
            try {
                cacheManager.addDevice(event.getDeviceId(), key);
                updateEvent(event, position);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                cacheManager.removeDevice(event.getDeviceId(), key);
            }
        }
    }
}
