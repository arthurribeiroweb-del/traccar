/*
 * Copyright 2022 Anton Tananaev (anton@traccar.org)
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

import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Server;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.util.Date;
import java.util.TimeZone;

public final class UserUtil {

    private UserUtil() {
    }

    public static boolean isEmpty(Storage storage) throws StorageException {
        return storage.getObjects(User.class, new Request(
                new Columns.Include("id"),
                new Order("id", false, 1))).isEmpty();
    }

    public static String getDistanceUnit(Server server, User user) {
        return lookupStringAttribute(server, user, "distanceUnit", "km");
    }

    public static String getSpeedUnit(Server server, User user) {
        return lookupStringAttribute(server, user, "speedUnit", "kn");
    }

    public static String getVolumeUnit(Server server, User user) {
        return lookupStringAttribute(server, user, "volumeUnit", "ltr");
    }

    public static TimeZone getTimezone(Server server, User user) {
        String timezone = lookupStringAttribute(server, user, "timezone", null);
        return timezone != null ? TimeZone.getTimeZone(timezone) : TimeZone.getDefault();
    }

    public static String getLanguage(Server server, User user) {
        return lookupStringAttribute(server, user, "language", null);
    }

    public static void applyServerDefaults(User user, Server server) {
        if (isEmpty(user.getMap())) {
            String value = server.getMap();
            if (!isEmpty(value)) {
                user.setMap(value);
            }
        }
        if (isEmpty(user.getCoordinateFormat())) {
            String value = server.getCoordinateFormat();
            if (!isEmpty(value)) {
                user.setCoordinateFormat(value);
            }
        }
        if (isEmpty(user.getPoiLayer())) {
            String value = server.getPoiLayer();
            if (!isEmpty(value)) {
                user.setPoiLayer(value);
            }
        }
        applyAttributeDefault(user, server, "speedUnit");
        applyAttributeDefault(user, server, "distanceUnit");
        applyAttributeDefault(user, server, "altitudeUnit");
        applyAttributeDefault(user, server, "volumeUnit");
        applyAttributeDefault(user, server, "timezone");
    }

    private static void applyAttributeDefault(User user, Server server, String key) {
        String userValue = user.getString(key);
        if (isEmpty(userValue)) {
            String serverValue = server.getString(key);
            if (!isEmpty(serverValue)) {
                user.set(key, serverValue);
            }
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static String lookupStringAttribute(Server server, User user, String key, String defaultValue) {
        String preference;
        String serverPreference = server.getString(key);
        String userPreference = user.getString(key);
        if (server.getForceSettings()) {
            preference = serverPreference != null ? serverPreference : userPreference;
        } else {
            preference = userPreference != null ? userPreference : serverPreference;
        }
        return preference != null ? preference : defaultValue;
    }

    public static void setUserDefaults(User user, Config config) {
        user.setDeviceLimit(config.getInteger(Keys.USERS_DEFAULT_DEVICE_LIMIT));
        int expirationDays = config.getInteger(Keys.USERS_DEFAULT_EXPIRATION_DAYS);
        if (expirationDays > 0) {
            user.setExpirationTime(new Date(System.currentTimeMillis() + expirationDays * 86400000L));
        }
    }
}
