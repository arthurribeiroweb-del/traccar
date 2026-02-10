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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.traccar.api.BaseResource;
import org.traccar.radar.StaticRadarManager;

import java.util.Map;

@Path("admin/static-radars")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StaticRadarAdminResource extends BaseResource {

    private static final double MIN_RADIUS_METERS = 5.0;
    private static final double MAX_RADIUS_METERS = 200.0;

    @Inject
    private StaticRadarManager staticRadarManager;

    @Path("radius")
    @GET
    public RadiusOverridesResponse getRadiusOverrides() throws Exception {
        permissionsService.checkAdmin(getUserId());
        return new RadiusOverridesResponse(staticRadarManager.getRadiusOverrides());
    }

    @Path("radius")
    @POST
    public RadiusOverrideResponse upsertRadiusOverride(RadiusOverrideRequest request) throws Exception {
        permissionsService.checkAdmin(getUserId());
        if (request == null) {
            throw new IllegalArgumentException("INVALID_REQUEST");
        }
        String externalId = request.getExternalId() != null ? request.getExternalId().trim() : "";
        if (externalId.isEmpty()) {
            throw new IllegalArgumentException("INVALID_EXTERNAL_ID");
        }
        if (request.getRadiusMeters() == null
                || !Double.isFinite(request.getRadiusMeters())
                || request.getRadiusMeters() < MIN_RADIUS_METERS
                || request.getRadiusMeters() > MAX_RADIUS_METERS) {
            throw new IllegalArgumentException("INVALID_RADIUS");
        }

        double normalizedRadius = Math.round(request.getRadiusMeters() * 10.0) / 10.0;
        double saved = staticRadarManager.setRadiusOverride(externalId, normalizedRadius);
        return new RadiusOverrideResponse(externalId, saved);
    }

    public static class RadiusOverrideRequest {
        private String externalId;
        private Double radiusMeters;

        public String getExternalId() {
            return externalId;
        }

        public void setExternalId(String externalId) {
            this.externalId = externalId;
        }

        public Double getRadiusMeters() {
            return radiusMeters;
        }

        public void setRadiusMeters(Double radiusMeters) {
            this.radiusMeters = radiusMeters;
        }
    }

    public static class RadiusOverrideResponse {
        private String externalId;
        private double radiusMeters;

        public RadiusOverrideResponse() {
        }

        public RadiusOverrideResponse(String externalId, double radiusMeters) {
            this.externalId = externalId;
            this.radiusMeters = radiusMeters;
        }

        public String getExternalId() {
            return externalId;
        }

        public void setExternalId(String externalId) {
            this.externalId = externalId;
        }

        public double getRadiusMeters() {
            return radiusMeters;
        }

        public void setRadiusMeters(double radiusMeters) {
            this.radiusMeters = radiusMeters;
        }
    }

    public static class RadiusOverridesResponse {
        private Map<String, Double> overrides;

        public RadiusOverridesResponse() {
        }

        public RadiusOverridesResponse(Map<String, Double> overrides) {
            this.overrides = overrides;
        }

        public Map<String, Double> getOverrides() {
            return overrides;
        }

        public void setOverrides(Map<String, Double> overrides) {
            this.overrides = overrides;
        }
    }
}
