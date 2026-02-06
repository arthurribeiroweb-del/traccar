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
package org.traccar.schedule;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Notification;
import org.traccar.model.Permission;
import org.traccar.model.User;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

public class TaskNotificationDeduplicate implements ScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskNotificationDeduplicate.class);
    private static final String TYPE_DEVICE_OVERSPEED = "deviceOverspeed";

    private final Storage storage;
    private final CacheManager cacheManager;

    @Inject
    public TaskNotificationDeduplicate(Storage storage, CacheManager cacheManager) {
        this.storage = storage;
        this.cacheManager = cacheManager;
    }

    @Override
    public boolean multipleInstances() {
        return false;
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        executor.execute(this);
    }

    private static boolean isDefaultDescription(Notification notification) {
        String description = notification.getDescription();
        return description == null || description.isBlank() || description.equals(notification.getType());
    }

    @Override
    public void run() {
        try {
            var notifications = storage.getObjects(Notification.class, new Request(
                    new Columns.Include("id", "type", "description")));
            Map<Long, Notification> overspeedById = new HashMap<>();
            for (Notification notification : notifications) {
                if (TYPE_DEVICE_OVERSPEED.equals(notification.getType())) {
                    overspeedById.put(notification.getId(), notification);
                }
            }
            if (overspeedById.isEmpty()) {
                return;
            }

            var permissions = storage.getPermissions(User.class, Notification.class);
            Map<Long, List<Notification>> userNotifications = new HashMap<>();
            for (Permission permission : permissions) {
                Notification notification = overspeedById.get(permission.getPropertyId());
                if (notification != null) {
                    userNotifications
                            .computeIfAbsent(permission.getOwnerId(), ignored -> new ArrayList<>())
                            .add(notification);
                }
            }

            int removed = 0;
            for (var entry : userNotifications.entrySet()) {
                long userId = entry.getKey();
                List<Notification> list = entry.getValue();

                boolean hasPreferred = list.stream().anyMatch(notification -> !isDefaultDescription(notification));
                if (!hasPreferred) {
                    continue;
                }

                for (Notification notification : list) {
                    if (!isDefaultDescription(notification)) {
                        continue;
                    }
                    storage.removePermission(new Permission(
                            User.class, userId, Notification.class, notification.getId()));
                    cacheManager.invalidatePermission(
                            true, User.class, userId, Notification.class, notification.getId(), false);
                    removed++;
                }
            }

            if (removed > 0) {
                LOGGER.info("Removed {} duplicate overspeed notification links", removed);
            }
        } catch (StorageException error) {
            LOGGER.warn("Failed to deduplicate overspeed notifications", error);
        } catch (Exception error) {
            LOGGER.warn("Unexpected error while deduplicating overspeed notifications", error);
        }
    }
}
