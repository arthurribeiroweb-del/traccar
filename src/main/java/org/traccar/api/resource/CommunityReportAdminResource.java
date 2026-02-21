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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.model.CommunityReport;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Path("admin/community/reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CommunityReportAdminResource extends BaseResource {

    private static final int MIN_RADAR_SPEED_LIMIT_KPH = 20;
    private static final int MAX_RADAR_SPEED_LIMIT_KPH = 120;

    @Inject
    private CommunityRadarGeofenceManager communityRadarGeofenceManager;

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            CommunityReport.STATUS_PENDING_PRIVATE,
            CommunityReport.STATUS_APPROVED_PUBLIC,
            CommunityReport.STATUS_REJECTED,
            CommunityReport.STATUS_REMOVED);

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return status.trim().toUpperCase();
    }

    private static Date calculateExpiration(String type, Date approvedAt) {
        long days = switch (type) {
            case CommunityReport.TYPE_BURACO -> 7;
            case CommunityReport.TYPE_QUEBRA_MOLAS -> 180;
            case CommunityReport.TYPE_FAIXA_PEDESTRE -> 180;
            case CommunityReport.TYPE_SINAL_TRANSITO -> 180;
            default -> 365;
        };
        return new Date(approvedAt.getTime() + (days * 24L * 60L * 60L * 1000L));
    }

    private static boolean isValidCoordinate(double latitude, double longitude) {
        return Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -90
                && latitude <= 90
                && longitude >= -180
                && longitude <= 180;
    }

    private static boolean isValidRadarSpeedLimit(Integer radarSpeedLimit) {
        return radarSpeedLimit != null
                && radarSpeedLimit >= MIN_RADAR_SPEED_LIMIT_KPH
                && radarSpeedLimit <= MAX_RADAR_SPEED_LIMIT_KPH;
    }

    private static boolean isVisibleOnPublicMap(CommunityReport report, Date now) {
        if (!CommunityReport.STATUS_APPROVED_PUBLIC.equals(report.getStatus())) {
            return false;
        }
        return report.getExpiresAt() == null || report.getExpiresAt().after(now);
    }

    private CommunityReport getRequiredReport(long id) throws StorageException {
        CommunityReport report = storage.getObject(CommunityReport.class, new Request(
                new Columns.All(),
                new Condition.Equals("id", id)));
        if (report == null) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
        }
        return report;
    }

    private void fillAuthorNames(List<CommunityReport> reports) throws StorageException {
        Set<Long> userIds = reports.stream().map(CommunityReport::getCreatedByUserId).collect(Collectors.toSet());
        Map<Long, String> names = userIds.stream().map(userId -> {
            try {
                User user = storage.getObject(User.class, new Request(
                        new Columns.Include("id", "name", "login", "email"),
                        new Condition.Equals("id", userId)));
                if (user == null) {
                    return null;
                }
                String label = user.getName();
                if (label == null || label.isBlank()) {
                    label = user.getLogin();
                }
                if (label == null || label.isBlank()) {
                    label = user.getEmail();
                }
                if (label == null || label.isBlank()) {
                    label = "#" + userId;
                }
                return Map.entry(userId, label);
            } catch (StorageException error) {
                throw new RuntimeException(error);
            }
        }).filter(item -> item != null).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        for (CommunityReport report : reports) {
            report.setAuthorName(names.getOrDefault(report.getCreatedByUserId(), "#" + report.getCreatedByUserId()));
        }
    }

    @GET
    public List<CommunityReport> get(@QueryParam("status") String status) throws StorageException {
        permissionsService.checkAdmin(getUserId());

        String normalizedStatus = normalizeStatus(status);
        if (normalizedStatus == null) {
            normalizedStatus = CommunityReport.STATUS_PENDING_PRIVATE;
        }
        if (!ALLOWED_STATUSES.contains(normalizedStatus)) {
            throw new IllegalArgumentException("INVALID_STATUS");
        }

        List<CommunityReport> reports = storage.getObjects(CommunityReport.class, new Request(
                new Columns.All(),
                new Condition.Equals("status", normalizedStatus),
                new Order("createdAt", true, 1000)));
        if (CommunityReport.STATUS_APPROVED_PUBLIC.equals(normalizedStatus)) {
            Date now = new Date();
            reports = reports.stream()
                    .filter(report -> isVisibleOnPublicMap(report, now))
                    .toList();
        }
        fillAuthorNames(reports);
        return reports;
    }

    @Path("count")
    @GET
    public CountResponse count(@QueryParam("status") String status) throws StorageException {
        permissionsService.checkAdmin(getUserId());

        String normalizedStatus = normalizeStatus(status);
        if (normalizedStatus == null) {
            normalizedStatus = CommunityReport.STATUS_PENDING_PRIVATE;
        }
        if (!ALLOWED_STATUSES.contains(normalizedStatus)) {
            throw new IllegalArgumentException("INVALID_STATUS");
        }

        List<CommunityReport> reports = storage.getObjects(CommunityReport.class, new Request(
                new Columns.Include("id", "expiresAt"),
                new Condition.Equals("status", normalizedStatus)));
        if (CommunityReport.STATUS_APPROVED_PUBLIC.equals(normalizedStatus)) {
            Date now = new Date();
            reports = reports.stream()
                    .filter(report -> isVisibleOnPublicMap(report, now))
                    .toList();
        }
        return new CountResponse(reports.size());
    }

    @Path("{id}/approve")
    @POST
    public CommunityReport approve(
            @PathParam("id") long id,
            ApproveCommunityReportRequest request) throws Exception {
        permissionsService.checkAdmin(getUserId());

        CommunityReport report = getRequiredReport(id);
        if (!CommunityReport.STATUS_PENDING_PRIVATE.equals(report.getStatus())) {
            throw new IllegalArgumentException("REPORT_NOT_PENDING");
        }

        boolean hasCoordinateOverride = request != null
                && request.getLatitude() != null
                && request.getLongitude() != null;
        if (request != null && (request.getLatitude() == null ^ request.getLongitude() == null)) {
            throw new IllegalArgumentException("INVALID_COORDINATES");
        }
        if (hasCoordinateOverride && !isValidCoordinate(request.getLatitude(), request.getLongitude())) {
            throw new IllegalArgumentException("INVALID_COORDINATES");
        }
        boolean hasRadarSpeedOverride = request != null && request.getRadarSpeedLimit() != null;
        if (CommunityReport.TYPE_RADAR.equals(report.getType()) && hasRadarSpeedOverride
                && !isValidRadarSpeedLimit(request.getRadarSpeedLimit())) {
            throw new IllegalArgumentException("INVALID_RADAR_SPEED_LIMIT");
        }

        Date now = new Date();
        if (hasCoordinateOverride) {
            report.setLatitude(request.getLatitude());
            report.setLongitude(request.getLongitude());
        }
        if (CommunityReport.TYPE_RADAR.equals(report.getType()) && hasRadarSpeedOverride) {
            report.setRadarSpeedLimit(request.getRadarSpeedLimit());
        }
        report.setStatus(CommunityReport.STATUS_APPROVED_PUBLIC);
        report.setApprovedAt(now);
        report.setApprovedByUserId(getUserId());
        report.setRejectedAt(null);
        report.setRejectedByUserId(0);
        report.setUpdatedAt(now);
        report.setExpiresAt(calculateExpiration(report.getType(), now));

        // Para BURACO não atualizamos radarSpeedLimit (evita erro de conversão null no H2)
        String[] updateColumns = CommunityReport.TYPE_RADAR.equals(report.getType())
                ? new String[]{"latitude", "longitude", "radarSpeedLimit", "status", "approvedAt", "approvedByUserId", "rejectedAt",
                        "rejectedByUserId", "updatedAt", "expiresAt"}
                : new String[]{"latitude", "longitude", "status", "approvedAt", "approvedByUserId", "rejectedAt",
                        "rejectedByUserId", "updatedAt", "expiresAt"};
        storage.updateObject(report, new Request(
                new Columns.Include(updateColumns),
                new Condition.Equals("id", id)));
        communityRadarGeofenceManager.syncFromApprovedReport(report);

        fillAuthorNames(List.of(report));
        return report;
    }

    @Path("{id}/reject")
    @POST
    public CommunityReport reject(@PathParam("id") long id) throws StorageException {
        permissionsService.checkAdmin(getUserId());

        CommunityReport report = getRequiredReport(id);
        if (!CommunityReport.STATUS_PENDING_PRIVATE.equals(report.getStatus())) {
            throw new IllegalArgumentException("REPORT_NOT_PENDING");
        }

        Date now = new Date();
        report.setStatus(CommunityReport.STATUS_REJECTED);
        report.setRejectedAt(now);
        report.setRejectedByUserId(getUserId());
        report.setApprovedAt(null);
        report.setApprovedByUserId(0);
        report.setUpdatedAt(now);
        report.setExpiresAt(null);

        storage.updateObject(report, new Request(
                new Columns.Include(
                        "status", "rejectedAt", "rejectedByUserId", "approvedAt",
                        "approvedByUserId", "updatedAt", "expiresAt"),
                new Condition.Equals("id", id)));

        fillAuthorNames(List.of(report));
        return report;
    }

    @Path("{id}/deactivate")
    @POST
    public CommunityReport deactivate(@PathParam("id") long id) throws Exception {
        permissionsService.checkAdmin(getUserId());

        CommunityReport report = getRequiredReport(id);
        if (!CommunityReport.STATUS_APPROVED_PUBLIC.equals(report.getStatus())) {
            throw new IllegalArgumentException("REPORT_NOT_ACTIVE");
        }

        Date now = new Date();
        report.setStatus(CommunityReport.STATUS_REJECTED);
        report.setRejectedAt(now);
        report.setRejectedByUserId(getUserId());
        report.setApprovedAt(null);
        report.setApprovedByUserId(0);
        report.setUpdatedAt(now);
        report.setExpiresAt(null);

        storage.updateObject(report, new Request(
                new Columns.Include(
                        "status", "rejectedAt", "rejectedByUserId", "approvedAt",
                        "approvedByUserId", "updatedAt", "expiresAt"),
                new Condition.Equals("id", id)));
        communityRadarGeofenceManager.syncFromReportStatus(report);

        fillAuthorNames(List.of(report));
        return report;
    }

    public static class CountResponse {
        private int count;

        public CountResponse() {
        }

        public CountResponse(int count) {
            this.count = count;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    public static class ApproveCommunityReportRequest {
        private Double latitude;
        private Double longitude;
        private Integer radarSpeedLimit;

        public Double getLatitude() {
            return latitude;
        }

        public void setLatitude(Double latitude) {
            this.latitude = latitude;
        }

        public Double getLongitude() {
            return longitude;
        }

        public void setLongitude(Double longitude) {
            this.longitude = longitude;
        }

        public Integer getRadarSpeedLimit() {
            return radarSpeedLimit;
        }

        public void setRadarSpeedLimit(Integer radarSpeedLimit) {
            this.radarSpeedLimit = radarSpeedLimit;
        }
    }

}
