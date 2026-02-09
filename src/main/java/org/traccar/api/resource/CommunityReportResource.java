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

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
import org.traccar.model.CommunityReportVote;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Path("community/reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CommunityReportResource extends BaseResource {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            CommunityReport.TYPE_RADAR,
            CommunityReport.TYPE_BURACO,
            CommunityReport.TYPE_QUEBRA_MOLAS);
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            CommunityReport.STATUS_PENDING_PRIVATE,
            CommunityReport.STATUS_APPROVED_PUBLIC,
            CommunityReport.STATUS_REJECTED,
            CommunityReport.STATUS_REMOVED);

    private static final Set<String> ALLOWED_VOTES = Set.of(
            CommunityReportVote.VOTE_EXISTS,
            CommunityReportVote.VOTE_GONE);

    private static final int MAX_REPORTS_PER_DAY = 10;
    private static final long COOLDOWN_MILLIS = 30_000;
    private static final long CANCEL_WINDOW_MILLIS = 120_000;
    private static final double EARTH_METERS_PER_DEGREE = 111_320.0;
    private static final int MIN_RADAR_SPEED_LIMIT_KPH = 20;
    private static final int MAX_RADAR_SPEED_LIMIT_KPH = 120;

    private static double radiusByType(String type) {
        return switch (type) {
            case CommunityReport.TYPE_RADAR -> 80.0;
            case CommunityReport.TYPE_BURACO -> 40.0;
            case CommunityReport.TYPE_QUEBRA_MOLAS -> 60.0;
            default -> 0.0;
        };
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return null;
        }
        return type.trim().toUpperCase();
    }

    private static String normalizeStatus(String status) {
        if (status == null) {
            return null;
        }
        return status.trim().toUpperCase();
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

    private static double haversineDistanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lon2 - lon1);

        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6_371_000 * c;
    }

    private record Bounds(double west, double south, double east, double north) {
    }

    private Bounds parseBounds(String bounds) {
        if (bounds == null || bounds.isBlank()) {
            throw new IllegalArgumentException("INVALID_BOUNDS");
        }
        String[] parts = bounds.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("INVALID_BOUNDS");
        }
        double west;
        double south;
        double east;
        double north;
        try {
            west = Double.parseDouble(parts[0].trim());
            south = Double.parseDouble(parts[1].trim());
            east = Double.parseDouble(parts[2].trim());
            north = Double.parseDouble(parts[3].trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("INVALID_BOUNDS");
        }
        if (!Double.isFinite(west) || !Double.isFinite(south)
                || !Double.isFinite(east) || !Double.isFinite(north)
                || south < -90 || south > 90 || north < -90 || north > 90
                || west < -180 || west > 180 || east < -180 || east > 180
                || south > north) {
            throw new IllegalArgumentException("INVALID_BOUNDS");
        }
        return new Bounds(west, south, east, north);
    }

    private Condition buildBoundsCondition(Bounds bounds) {
        Condition latCondition = new Condition.Between("latitude", bounds.south, bounds.north);
        if (bounds.west <= bounds.east) {
            return new Condition.And(latCondition, new Condition.Between("longitude", bounds.west, bounds.east));
        } else {
            Condition westSide = new Condition.Between("longitude", bounds.west, 180.0);
            Condition eastSide = new Condition.Between("longitude", -180.0, bounds.east);
            return new Condition.And(latCondition, new Condition.Or(westSide, eastSide));
        }
    }

    private void ensureCooldown(long userId, Date now) throws StorageException {
        Condition activeStatusCondition = new Condition.Or(
                new Condition.Equals("status", CommunityReport.STATUS_PENDING_PRIVATE),
                new Condition.Equals("status", CommunityReport.STATUS_APPROVED_PUBLIC));
        List<CommunityReport> latest = storage.getObjects(CommunityReport.class, new Request(
                new Columns.Include("createdAt"),
                new Condition.And(
                        new Condition.Equals("createdByUserId", userId),
                        activeStatusCondition),
                new Order("createdAt", true, 1)));
        if (!latest.isEmpty() && latest.get(0).getCreatedAt() != null
                && now.getTime() - latest.get(0).getCreatedAt().getTime() < COOLDOWN_MILLIS) {
            throw new IllegalArgumentException("COOLDOWN_ACTIVE");
        }
    }

    private void ensureDailyLimit(long userId, Date now) throws StorageException {
        Date startOfDay = Date.from(LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant());
        Condition activeStatusCondition = new Condition.Or(
                new Condition.Equals("status", CommunityReport.STATUS_PENDING_PRIVATE),
                new Condition.Equals("status", CommunityReport.STATUS_APPROVED_PUBLIC));
        List<CommunityReport> daily = storage.getObjects(CommunityReport.class, new Request(
                new Columns.Include("id"),
                new Condition.And(
                        new Condition.Equals("createdByUserId", userId),
                        new Condition.And(
                                new Condition.Between("createdAt", startOfDay, now),
                                activeStatusCondition))));
        if (daily.size() >= MAX_REPORTS_PER_DAY) {
            throw new IllegalArgumentException("RATE_LIMIT_DAILY");
        }
    }

    private void ensureNoDuplicate(String type, double latitude, double longitude) throws StorageException {
        double radius = radiusByType(type);
        double latitudeDelta = radius / EARTH_METERS_PER_DEGREE;
        double cosine = Math.max(Math.cos(Math.toRadians(latitude)), 0.01);
        double longitudeDelta = radius / (EARTH_METERS_PER_DEGREE * cosine);

        Condition statusCondition = new Condition.Or(
                new Condition.Equals("status", CommunityReport.STATUS_PENDING_PRIVATE),
                new Condition.Equals("status", CommunityReport.STATUS_APPROVED_PUBLIC));
        Condition areaCondition = new Condition.And(
                new Condition.Between("latitude", latitude - latitudeDelta, latitude + latitudeDelta),
                new Condition.Between("longitude", longitude - longitudeDelta, longitude + longitudeDelta));
        Condition condition = new Condition.And(
                new Condition.Equals("type", type),
                new Condition.And(statusCondition, areaCondition));

        List<CommunityReport> nearby = storage.getObjects(CommunityReport.class, new Request(
                new Columns.Include("id", "latitude", "longitude"),
                condition));

        for (CommunityReport report : nearby) {
            if (haversineDistanceMeters(latitude, longitude, report.getLatitude(), report.getLongitude()) <= radius) {
                throw new IllegalArgumentException("DUPLICATE_NEARBY");
            }
        }
    }

    private void fillAuthorNames(List<CommunityReport> reports) throws StorageException {
        Map<Long, String> names = new HashMap<>();
        for (CommunityReport report : reports) {
            long userId = report.getCreatedByUserId();
            if (names.containsKey(userId)) {
                continue;
            }
            User user = storage.getObject(User.class, new Request(
                    new Columns.Include("id", "name", "login", "email"),
                    new Condition.Equals("id", userId)));
            String label = user == null ? null : user.getName();
            if (label == null || label.isBlank()) {
                label = user == null ? null : user.getLogin();
            }
            if (label == null || label.isBlank()) {
                label = user == null ? null : user.getEmail();
            }
            if (label == null || label.isBlank()) {
                label = "#" + userId;
            }
            names.put(userId, label);
        }
        for (CommunityReport report : reports) {
            report.setAuthorName(names.getOrDefault(report.getCreatedByUserId(),
                    "#" + report.getCreatedByUserId()));
        }
    }

    @POST
    public CommunityReport create(CreateCommunityReportRequest request) throws StorageException {
        String type = normalizeType(request != null ? request.getType() : null);
        if (!ALLOWED_TYPES.contains(type)) {
            throw new IllegalArgumentException("INVALID_TYPE");
        }
        if (!isValidCoordinate(request.getLatitude(), request.getLongitude())) {
            throw new IllegalArgumentException("INVALID_COORDINATES");
        }

        Integer radarSpeedLimit = request.getRadarSpeedLimit();
        if (CommunityReport.TYPE_RADAR.equals(type)) {
            if (!isValidRadarSpeedLimit(radarSpeedLimit)) {
                throw new IllegalArgumentException("INVALID_RADAR_SPEED_LIMIT");
            }
        } else {
            radarSpeedLimit = null;
        }

        long userId = getUserId();
        Date now = new Date();

        ensureCooldown(userId, now);
        ensureDailyLimit(userId, now);
        ensureNoDuplicate(type, request.getLatitude(), request.getLongitude());

        CommunityReport report = new CommunityReport();
        report.setType(type);
        report.setStatus(CommunityReport.STATUS_PENDING_PRIVATE);
        report.setLatitude(request.getLatitude());
        report.setLongitude(request.getLongitude());
        report.setRadarSpeedLimit(radarSpeedLimit);
        report.setCreatedByUserId(userId);
        report.setCreatedAt(now);
        report.setUpdatedAt(now);

        // Exclui radarSpeedLimit do insert se não for radar, para evitar erro quando coluna não existe
        Columns columns = CommunityReport.TYPE_RADAR.equals(type)
                ? new Columns.Exclude("id")
                : new Columns.Exclude("id", "radarSpeedLimit");
        report.setId(storage.addObject(report, new Request(columns)));
        return report;
    }

    @GET
    public Collection<CommunityReport> get(
            @QueryParam("scope") String scope,
            @QueryParam("status") String status,
            @QueryParam("bounds") String bounds) throws StorageException {

        if ("mine".equalsIgnoreCase(scope)) {
            Condition condition = new Condition.Equals("createdByUserId", getUserId());
            String normalizedStatus = normalizeStatus(status);
            if (normalizedStatus != null && !normalizedStatus.isBlank()) {
                if (!ALLOWED_STATUSES.contains(normalizedStatus)) {
                    throw new IllegalArgumentException("INVALID_STATUS");
                }
                condition = new Condition.And(condition, new Condition.Equals("status", normalizedStatus));
            }
            return storage.getObjects(CommunityReport.class, new Request(
                    new Columns.All(), condition, new Order("createdAt", true, 0)));
        }

        if ("public".equalsIgnoreCase(scope)) {
            Bounds parsedBounds = parseBounds(bounds);
            Condition condition = new Condition.And(
                    new Condition.Equals("status", CommunityReport.STATUS_APPROVED_PUBLIC),
                    buildBoundsCondition(parsedBounds));
            List<CommunityReport> reports = storage.getObjects(CommunityReport.class, new Request(
                    new Columns.All(), condition, new Order("createdAt", true, 500)));
            Date now = new Date();
            List<CommunityReport> active = reports.stream()
                    .filter(report -> report.getExpiresAt() == null || report.getExpiresAt().after(now))
                    .toList();
            fillAuthorNames(active);
            return active;
        }

        throw new IllegalArgumentException("INVALID_SCOPE");
    }

    @Path("{id}")
    @DELETE
    public Response remove(@PathParam("id") long id) throws StorageException {
        CommunityReport report = storage.getObject(CommunityReport.class, new Request(
                new Columns.All(),
                new Condition.Equals("id", id)));
        if (report == null) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
        }

        if (report.getCreatedByUserId() != getUserId()) {
            throw new SecurityException("REPORT_ACCESS_DENIED");
        }
        if (!CommunityReport.STATUS_PENDING_PRIVATE.equals(report.getStatus())) {
            throw new IllegalArgumentException("CANNOT_CANCEL_REPORT");
        }
        if (report.getCreatedAt() == null
                || System.currentTimeMillis() - report.getCreatedAt().getTime() > CANCEL_WINDOW_MILLIS) {
            throw new IllegalArgumentException("CANCEL_WINDOW_EXPIRED");
        }

        storage.removeObject(CommunityReport.class, new Request(new Condition.Equals("id", id)));
        return Response.noContent().build();
    }

    private VoteResponse recomputeVotes(CommunityReport report, long currentUserId) throws StorageException {
        List<CommunityReportVote> votes = storage.getObjects(CommunityReportVote.class, new Request(
                new Columns.All(),
                new Condition.Equals("reportId", report.getId())));
        Date now = new Date();
        int existsVotes = 0;
        int goneVotes = 0;
        Date lastVotedAt = null;
        for (CommunityReportVote vote : votes) {
            if (CommunityReportVote.VOTE_EXISTS.equals(vote.getVote())) {
                existsVotes++;
            } else if (CommunityReportVote.VOTE_GONE.equals(vote.getVote())) {
                goneVotes++;
            }
            if (vote.getUpdatedAt() != null
                    && (lastVotedAt == null || vote.getUpdatedAt().after(lastVotedAt))) {
                lastVotedAt = vote.getUpdatedAt();
            }
        }
        report.setExistsVotes(existsVotes);
        report.setGoneVotes(goneVotes);
        report.setLastVotedAt(lastVotedAt);

        String status = report.getStatus();
        if (CommunityReport.STATUS_APPROVED_PUBLIC.equals(status)
                && goneVotes - existsVotes >= 3) {
            status = CommunityReport.STATUS_REMOVED;
            report.setStatus(status);
            report.setRemovedAt(now);
            report.setUpdatedAt(now);
        } else if (CommunityReport.STATUS_REMOVED.equals(status)
                && existsVotes - goneVotes >= 1) {
            status = CommunityReport.STATUS_APPROVED_PUBLIC;
            report.setStatus(status);
            report.setRemovedAt(null);
            report.setUpdatedAt(now);
        }

        storage.updateObject(report, new Request(
                new Columns.Include("existsVotes", "goneVotes", "lastVotedAt", "status", "removedAt", "updatedAt"),
                new Condition.Equals("id", report.getId())));

        String userVote = votes.stream()
                .filter(vote -> vote.getUserId() == currentUserId)
                .map(CommunityReportVote::getVote)
                .findFirst()
                .orElse(null);

        return new VoteResponse(existsVotes, goneVotes, userVote, lastVotedAt, status);
    }

    @Path("{id}/votes")
    @GET
    public VoteResponse getVotes(@PathParam("id") long id) throws StorageException {
        CommunityReport report = storage.getObject(CommunityReport.class, new Request(
                new Columns.All(),
                new Condition.Equals("id", id)));
        if (report == null) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
        }
        return recomputeVotes(report, getUserId());
    }

    @Path("{id}/vote")
    @POST
    public VoteResponse vote(@PathParam("id") long id, VoteRequest request) throws StorageException {
        CommunityReport report = storage.getObject(CommunityReport.class, new Request(
                new Columns.All(),
                new Condition.Equals("id", id)));
        if (report == null) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
        }
        if (!CommunityReport.STATUS_APPROVED_PUBLIC.equals(report.getStatus())
                && !CommunityReport.STATUS_REMOVED.equals(report.getStatus())) {
            throw new IllegalArgumentException("INVALID_STATUS");
        }

        String vote = request.getVote();
        if (vote != null) {
            vote = vote.trim().toUpperCase();
        }
        if (!ALLOWED_VOTES.contains(vote)) {
            throw new IllegalArgumentException("INVALID_VOTE");
        }

        long userId = getUserId();
        CommunityReportVote existing = storage.getObject(CommunityReportVote.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("reportId", id),
                        new Condition.Equals("userId", userId))));
        Date now = new Date();
        if (existing == null) {
            CommunityReportVote newVote = new CommunityReportVote();
            newVote.setReportId(id);
            newVote.setUserId(userId);
            newVote.setVote(vote);
            newVote.setCreatedAt(now);
            newVote.setUpdatedAt(now);
            storage.addObject(newVote, new Request(new Columns.Exclude("id")));
        } else if (!vote.equals(existing.getVote())) {
            existing.setVote(vote);
            existing.setUpdatedAt(now);
            storage.updateObject(existing, new Request(
                    new Columns.Include("vote", "updatedAt"),
                    new Condition.Equals("id", existing.getId())));
        } else {
            return recomputeVotes(report, userId);
        }

        return recomputeVotes(report, userId);
    }

    public static class CreateCommunityReportRequest {
        private String type;
        private double latitude;
        private double longitude;
        private Integer radarSpeedLimit;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public double getLatitude() {
            return latitude;
        }

        public void setLatitude(double latitude) {
            this.latitude = latitude;
        }

        public double getLongitude() {
            return longitude;
        }

        public void setLongitude(double longitude) {
            this.longitude = longitude;
        }

        public Integer getRadarSpeedLimit() {
            return radarSpeedLimit;
        }

        public void setRadarSpeedLimit(Integer radarSpeedLimit) {
            this.radarSpeedLimit = radarSpeedLimit;
        }
    }

    public static class VoteRequest {
        private String vote;

        public String getVote() {
            return vote;
        }

        public void setVote(String vote) {
            this.vote = vote;
        }
    }

    public static class VoteResponse {
        private int existsVotes;
        private int goneVotes;
        private String userVote;
        private Date lastVotedAt;
        private String status;

        public VoteResponse(int existsVotes, int goneVotes, String userVote, Date lastVotedAt, String status) {
            this.existsVotes = existsVotes;
            this.goneVotes = goneVotes;
            this.userVote = userVote;
            this.lastVotedAt = lastVotedAt;
            this.status = status;
        }

        public int getExistsVotes() {
            return existsVotes;
        }

        public int getGoneVotes() {
            return goneVotes;
        }

        public String getUserVote() {
            return userVote;
        }

        public Date getLastVotedAt() {
            return lastVotedAt;
        }

        public String getStatus() {
            return status;
        }
    }

}
