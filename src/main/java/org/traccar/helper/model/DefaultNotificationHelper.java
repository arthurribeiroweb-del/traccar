/*
 * Copyright 2025 Anton Tananaev (anton@traccar.org)
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
package org.traccar.helper.model;

import org.traccar.model.Event;
import org.traccar.model.Notification;
import org.traccar.model.Permission;
import org.traccar.model.User;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import java.util.List;

/**
 * Creates default "kit basico" notifications for new users:
 * geofence enter/exit, ignition on/off, overspeed, maintenance.
 */
public final class DefaultNotificationHelper {

    private static final String NOTIFICATORS = "traccar,web";

    private static final List<String> DEFAULT_TYPES = List.of(
            Event.TYPE_GEOFENCE_ENTER,
            Event.TYPE_GEOFENCE_EXIT,
            Event.TYPE_IGNITION_ON,
            Event.TYPE_IGNITION_OFF,
            Event.TYPE_DEVICE_OVERSPEED,
            Event.TYPE_MAINTENANCE);

    private DefaultNotificationHelper() {
    }

    public static void createForUser(Storage storage, CacheManager cacheManager, long userId)
            throws StorageException, Exception {
        for (String type : DEFAULT_TYPES) {
            Notification notification = new Notification();
            notification.setType(type);
            notification.setAlways(true);
            notification.setNotificators(NOTIFICATORS);
            notification.setDescription(type);

            notification.setId(storage.addObject(notification, new Request(new Columns.Exclude("id"))));
            storage.addPermission(new Permission(User.class, userId, Notification.class, notification.getId()));
            cacheManager.invalidatePermission(true, User.class, userId, Notification.class, notification.getId(), true);
        }
    }
}
