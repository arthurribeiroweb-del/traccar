/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
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
package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.model.CommunityReport;
import org.traccar.model.Device;
import org.traccar.model.Geofence;
import org.traccar.model.ObjectOperation;
import org.traccar.model.Permission;
import org.traccar.model.User;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Singleton
public class CommunityRadarGeofenceManager {

    private static final String ATTRIBUTE_RADAR = "radar";
    private static final String ATTRIBUTE_RADAR_ACTIVE = "radarActive";
    private static final String ATTRIBUTE_RADAR_SPEED_LIMIT_KPH = "radarSpeedLimitKph";
    private static final String ATTRIBUTE_RADAR_RADIUS_METERS = "radarRadiusMeters";
    private static final String ATTRIBUTE_COMMUNITY_REPORT_ID = "communityReportId";
    private static final String ATTRIBUTE_COMMUNITY_SOURCE = "communitySource";

    private static final String COMMUNITY_SOURCE_REPORT = "report";
    private static final double COMMUNITY_RADAR_RADIUS_METERS = 30.0;
    private static final String COMMUNITY_RADAR_NAME_PREFIX = "Radar comunidade #";

    @Inject
    private Storage storage;

    @Inject
    private CacheManager cacheManager;

    private boolean isRadarReport(CommunityReport report) {
        return report != null && CommunityReport.TYPE_RADAR.equals(report.getType());
    }

    private Geofence findByCommunityReportId(long reportId) throws Exception {
        for (Geofence geofence : storage.getObjects(Geofence.class, new Request(
                new Columns.Include("id", "name", "description", "area", "attributes")))) {
            Object value = geofence.getAttributes().get(ATTRIBUTE_COMMUNITY_REPORT_ID);
            if (value instanceof Number number && number.longValue() == reportId) {
                return geofence;
            }
            if (value instanceof String text) {
                try {
                    if (Long.parseLong(text) == reportId) {
                        return geofence;
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore malformed values and continue lookup.
                }
            }
            if (Objects.equals(geofence.getName(), buildGeofenceName(reportId))) {
                return geofence;
            }
        }
        return null;
    }

    private String buildGeofenceName(long reportId) {
        return COMMUNITY_RADAR_NAME_PREFIX + reportId;
    }

    private String buildGeofenceDescription(CommunityReport report) {
        int speedLimit = report.getRadarSpeedLimit() != null ? report.getRadarSpeedLimit() : 0;
        return "Radar da comunidade (" + speedLimit + " km/h)";
    }

    private String buildCircleArea(double latitude, double longitude, double radiusMeters) {
        return String.format(Locale.US, "CIRCLE (%.6f %.6f, %.1f)", latitude, longitude, radiusMeters);
    }

    private void syncRadarToAllDevices(Geofence geofence) throws Exception {
        Set<Long> linkedDeviceIds = new HashSet<>();
        for (Permission permission : storage.getPermissions(Device.class, 0, Geofence.class, geofence.getId())) {
            linkedDeviceIds.add(permission.getOwnerId());
        }

        for (Device device : storage.getObjects(Device.class, new Request(new Columns.Include("id")))) {
            if (linkedDeviceIds.contains(device.getId())) {
                continue;
            }
            storage.addPermission(new Permission(Device.class, device.getId(), Geofence.class, geofence.getId()));
            cacheManager.invalidatePermission(true, Device.class, device.getId(), Geofence.class, geofence.getId(), true);
        }
    }

    private void syncRadarToAllUsers(Geofence geofence) throws Exception {
        Set<Long> linkedUserIds = new HashSet<>();
        for (Permission permission : storage.getPermissions(User.class, 0, Geofence.class, geofence.getId())) {
            linkedUserIds.add(permission.getOwnerId());
        }

        for (User user : storage.getObjects(User.class, new Request(new Columns.Include("id")))) {
            if (linkedUserIds.contains(user.getId())) {
                continue;
            }
            storage.addPermission(new Permission(User.class, user.getId(), Geofence.class, geofence.getId()));
            cacheManager.invalidatePermission(true, User.class, user.getId(), Geofence.class, geofence.getId(), true);
        }
    }

    private Geofence upsert(CommunityReport report, boolean active) throws Exception {
        if (!isRadarReport(report) || report.getRadarSpeedLimit() == null || report.getRadarSpeedLimit() <= 0) {
            return null;
        }

        Geofence geofence = findByCommunityReportId(report.getId());
        if (geofence == null) {
            geofence = new Geofence();
        }

        geofence.setName(buildGeofenceName(report.getId()));
        geofence.setDescription(buildGeofenceDescription(report));
        geofence.setArea(buildCircleArea(report.getLatitude(), report.getLongitude(), COMMUNITY_RADAR_RADIUS_METERS));
        geofence.set(ATTRIBUTE_RADAR, true);
        geofence.set(ATTRIBUTE_RADAR_ACTIVE, active);
        geofence.set(ATTRIBUTE_RADAR_SPEED_LIMIT_KPH, report.getRadarSpeedLimit());
        geofence.set(ATTRIBUTE_RADAR_RADIUS_METERS, COMMUNITY_RADAR_RADIUS_METERS);
        geofence.set(ATTRIBUTE_COMMUNITY_REPORT_ID, report.getId());
        geofence.set(ATTRIBUTE_COMMUNITY_SOURCE, COMMUNITY_SOURCE_REPORT);

        if (geofence.getId() == 0) {
            geofence.setId(storage.addObject(geofence, new Request(new Columns.Exclude("id"))));
            cacheManager.invalidateObject(true, Geofence.class, geofence.getId(), ObjectOperation.ADD);
        } else {
            storage.updateObject(geofence, new Request(
                    new Columns.Include("name", "description", "area", "attributes"),
                    new Condition.Equals("id", geofence.getId())));
            cacheManager.invalidateObject(true, Geofence.class, geofence.getId(), ObjectOperation.UPDATE);
        }

        syncRadarToAllDevices(geofence);
        syncRadarToAllUsers(geofence);
        return geofence;
    }

    public void syncFromApprovedReport(CommunityReport report) throws Exception {
        if (!isRadarReport(report)) {
            return;
        }
        upsert(report, true);
    }

    public void syncFromReportStatus(CommunityReport report) throws Exception {
        if (!isRadarReport(report)) {
            return;
        }
        if (CommunityReport.STATUS_APPROVED_PUBLIC.equals(report.getStatus())) {
            upsert(report, true);
            return;
        }

        Geofence geofence = findByCommunityReportId(report.getId());
        if (geofence == null) {
            return;
        }
        geofence.set(ATTRIBUTE_RADAR_ACTIVE, false);
        storage.updateObject(geofence, new Request(
                new Columns.Include("attributes"),
                new Condition.Equals("id", geofence.getId())));
        cacheManager.invalidateObject(true, Geofence.class, geofence.getId(), ObjectOperation.UPDATE);
    }
}
