/*
 * Copyright 2015 - 2025 Anton Tananaev (anton@traccar.org)
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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.core.Context;
import org.traccar.api.BaseObjectResource;
import org.traccar.api.signature.TokenManager;
import org.traccar.broadcast.BroadcastService;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.MediaManager;
import org.traccar.helper.LogAction;
import org.traccar.model.Device;
import org.traccar.model.DeviceAccumulators;
import org.traccar.model.Geofence;
import org.traccar.model.ObjectOperation;
import org.traccar.model.Permission;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.UUID;
import java.security.GeneralSecurityException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Path("devices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DeviceResource extends BaseObjectResource<Device> {

    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private static final int IMAGE_SIZE_LIMIT = 500000;
    private static final String ATTRIBUTE_RADAR = "radar";
    private static final String ATTRIBUTE_MAINTENANCE = "maintenance";
    private static final String ATTRIBUTE_MAINTENANCE_OIL = "oil";

    @Inject
    private Config config;

    @Inject
    private CacheManager cacheManager;

    @Inject
    private ConnectionManager connectionManager;

    @Inject
    private BroadcastService broadcastService;

    @Inject
    private MediaManager mediaManager;

    @Inject
    private TokenManager tokenManager;

    @Inject
    private LogAction actionLogger;

    @Context
    private HttpServletRequest request;

    public DeviceResource() {
        super(Device.class);
    }

    private boolean isRadar(Geofence geofence) {
        return geofence != null && geofence.getBoolean(ATTRIBUTE_RADAR);
    }

    private void syncRadarsToDevice(Device device) throws Exception {
        Set<Long> linkedGeofenceIds = new HashSet<>();
        for (Permission permission : storage.getPermissions(Device.class, device.getId(), Geofence.class, 0)) {
            linkedGeofenceIds.add(permission.getPropertyId());
        }

        for (Geofence geofence : storage.getObjects(Geofence.class, new Request(
                new Columns.Include("id", "attributes")))) {
            if (!isRadar(geofence) || linkedGeofenceIds.contains(geofence.getId())) {
                continue;
            }
            storage.addPermission(new Permission(Device.class, device.getId(), Geofence.class, geofence.getId()));
            cacheManager.invalidatePermission(true, Device.class, device.getId(), Geofence.class, geofence.getId(), true);
        }
    }

    @Override
    @POST
    public Response add(Device entity) throws Exception {
        Response response = super.add(entity);
        syncRadarsToDevice(entity);
        return response;
    }

    @Path("{id}")
    @PUT
    public Response update(Device entity) throws Exception {
        String correlationId = "dev-upd-" + UUID.randomUUID();
        permissionsService.checkPermission(Device.class, getUserId(), entity.getId());
        User user = permissionsService.getUser(getUserId());
        Device existing = storage.getObject(Device.class, new Request(
                new Columns.All(), new Condition.Equals("id", entity.getId())));
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (!user.getAdministrator()) {
            permissionsService.checkEdit(getUserId(), Device.class, false, false);
            existing.setName(DeviceUpdateValidator.normalizeName(entity.getName()));
            existing.setCategory(entity.getCategory());

            // For non-admin users, allow changing only overspeed limit on device attributes.
            if (entity.getAttributes() != null
                    && entity.getAttributes().containsKey(Keys.EVENT_OVERSPEED_LIMIT.getKey())) {
                Object speedLimit = entity.getAttributes().get(Keys.EVENT_OVERSPEED_LIMIT.getKey());
                if (speedLimit != null) {
                    existing.getAttributes().put(Keys.EVENT_OVERSPEED_LIMIT.getKey(), speedLimit);
                }
            }

            // Allow non-admin users to persist maintenance oil settings used by maintenance-center.
            // Keep write scope narrow: only attributes.maintenance.oil.
            if (entity.getAttributes() != null && entity.getAttributes().containsKey(ATTRIBUTE_MAINTENANCE)) {
                Object incomingMaintenance = entity.getAttributes().get(ATTRIBUTE_MAINTENANCE);
                if (incomingMaintenance instanceof Map<?, ?> incomingMaintenanceMap
                        && incomingMaintenanceMap.containsKey(ATTRIBUTE_MAINTENANCE_OIL)) {
                    Object incomingOil = incomingMaintenanceMap.get(ATTRIBUTE_MAINTENANCE_OIL);
                    LOGGER.info("maintenance_save correlationId={} userId={} deviceId={} payloadOil={}",
                            correlationId, getUserId(), entity.getId(), incomingOil);

                    Object currentMaintenance = existing.getAttributes().get(ATTRIBUTE_MAINTENANCE);
                    Map<String, Object> nextMaintenance = new HashMap<>();
                    if (currentMaintenance instanceof Map<?, ?> currentMaintenanceMap) {
                        for (Map.Entry<?, ?> entry : currentMaintenanceMap.entrySet()) {
                            if (entry.getKey() != null) {
                                nextMaintenance.put(String.valueOf(entry.getKey()), entry.getValue());
                            }
                        }
                    }
                    nextMaintenance.put(ATTRIBUTE_MAINTENANCE_OIL, incomingOil);
                    existing.getAttributes().put(ATTRIBUTE_MAINTENANCE, nextMaintenance);
                }
            }

            storage.updateObject(existing, new Request(
                    new Columns.Include("name", "category", "attributes"),
                    new Condition.Equals("id", existing.getId())));
            cacheManager.invalidateObject(true, Device.class, existing.getId(), ObjectOperation.UPDATE);
            actionLogger.edit(request, getUserId(), existing);
            LOGGER.info("maintenance_save_done correlationId={} userId={} deviceId={}", correlationId, getUserId(), entity.getId());
            return Response.ok(existing).build();
        }

        permissionsService.checkEdit(getUserId(), entity, false, false);
        LOGGER.info("maintenance_save_admin correlationId={} userId={} deviceId={} attributesPresent={}",
                correlationId, getUserId(), entity.getId(), entity.getAttributes() != null);
        storage.updateObject(entity, new Request(
                new Columns.Exclude("id"),
                new Condition.Equals("id", entity.getId())));
        cacheManager.invalidateObject(true, Device.class, entity.getId(), ObjectOperation.UPDATE);
        actionLogger.edit(request, getUserId(), entity);
        return Response.ok(entity).build();
    }

    @GET
    public Stream<Device> get(
            @QueryParam("all") boolean all, @QueryParam("userId") long userId,
            @QueryParam("uniqueId") List<String> uniqueIds,
            @QueryParam("id") List<Long> deviceIds,
            @QueryParam("excludeAttributes") boolean excludeAttributes) throws StorageException {

        Columns columns = excludeAttributes ? new Columns.Exclude("attributes") : new Columns.All();

        if (!uniqueIds.isEmpty() || !deviceIds.isEmpty()) {

            List<Device> result = new LinkedList<>();
            for (String uniqueId : uniqueIds) {
                result.addAll(storage.getObjects(Device.class, new Request(
                        columns,
                        new Condition.And(
                                new Condition.Equals("uniqueId", uniqueId),
                                new Condition.Permission(User.class, getUserId(), Device.class)))));
            }
            for (Long deviceId : deviceIds) {
                result.addAll(storage.getObjects(Device.class, new Request(
                        columns,
                        new Condition.And(
                                new Condition.Equals("id", deviceId),
                                new Condition.Permission(User.class, getUserId(), Device.class)))));
            }
            return result.stream();

        } else {

            var conditions = new LinkedList<Condition>();

            if (all) {
                if (permissionsService.notAdmin(getUserId())) {
                    conditions.add(new Condition.Permission(User.class, getUserId(), baseClass));
                }
            } else {
                if (userId == 0) {
                    conditions.add(new Condition.Permission(User.class, getUserId(), baseClass));
                } else {
                    permissionsService.checkUser(getUserId(), userId);
                    conditions.add(new Condition.Permission(User.class, userId, baseClass).excludeGroups());
                }
            }

            return storage.getObjectsStream(baseClass, new Request(
                    columns, Condition.merge(conditions), new Order("name")));

        }
    }

    @Path("{id}/accumulators")
    @PUT
    public Response updateAccumulators(DeviceAccumulators entity) throws Exception {
        permissionsService.checkPermission(Device.class, getUserId(), entity.getDeviceId());
        permissionsService.checkEdit(getUserId(), Device.class, false, false);

        Position position = storage.getObject(Position.class, new Request(
                new Columns.All(), new Condition.LatestPositions(entity.getDeviceId())));
        if (position != null) {
            if (entity.getTotalDistance() != null) {
                position.getAttributes().put(Position.KEY_TOTAL_DISTANCE, entity.getTotalDistance());
            }
            if (entity.getHours() != null) {
                position.getAttributes().put(Position.KEY_HOURS, entity.getHours());
            }
            position.setId(storage.addObject(position, new Request(new Columns.Exclude("id"))));

            Device device = new Device();
            device.setId(position.getDeviceId());
            device.setPositionId(position.getId());
            storage.updateObject(device, new Request(
                    new Columns.Include("positionId"),
                    new Condition.Equals("id", device.getId())));

            var key = new Object();
            try {
                cacheManager.addDevice(position.getDeviceId(), key);
                cacheManager.updatePosition(position);
                connectionManager.updatePosition(true, position);
            } finally {
                cacheManager.removeDevice(position.getDeviceId(), key);
            }
        } else {
            throw new IllegalArgumentException();
        }

        actionLogger.resetAccumulators(request, getUserId(), entity.getDeviceId());
        return Response.noContent().build();
    }

    private String imageExtension(String type) {
        return switch (type) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/svg+xml" -> "svg";
            default -> throw new IllegalArgumentException("Unsupported image type");
        };
    }

    @Path("{id}/image")
    @POST
    @Consumes("image/*")
    public Response uploadImage(
            @PathParam("id") long deviceId, File file,
            @HeaderParam(HttpHeaders.CONTENT_TYPE) String type) throws StorageException, IOException {

        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("id", deviceId),
                        new Condition.Permission(User.class, getUserId(), Device.class))));
        if (device != null) {
            String name = "device";
            String extension = imageExtension(type);
            try (var input = new FileInputStream(file);
                    var output = mediaManager.createFileStream(device.getUniqueId(), name, extension)) {

                long transferred = 0;
                byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer, 0, buffer.length)) >= 0) {
                    output.write(buffer, 0, read);
                    transferred += read;
                    if (transferred > IMAGE_SIZE_LIMIT) {
                        throw new IllegalArgumentException("Image size limit exceeded");
                    }
                }
            }
            return Response.ok(name + "." + extension).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    @Path("share")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @POST
    public String shareDevice(
            @FormParam("deviceId") long deviceId,
            @FormParam("expiration") Date expiration) throws StorageException, GeneralSecurityException, IOException {

        User user = permissionsService.getUser(getUserId());
        if (permissionsService.getServer().getBoolean(Keys.DEVICE_SHARE_DISABLE.getKey())) {
            throw new SecurityException("Sharing is disabled");
        }
        if (user.getTemporary()) {
            throw new SecurityException("Temporary user");
        }
        if (user.getExpirationTime() != null && user.getExpirationTime().before(expiration)) {
            expiration = user.getExpirationTime();
        }

        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("id", deviceId),
                        new Condition.Permission(User.class, user.getId(), Device.class))));

        String shareEmail = user.getEmail() + ":" + device.getUniqueId();
        User share = storage.getObject(User.class, new Request(
                new Columns.All(), new Condition.Equals("email", shareEmail)));

        if (share == null) {
            share = new User();
            share.setName(device.getName());
            share.setEmail(shareEmail);
            share.setExpirationTime(expiration);
            share.setTemporary(true);
            share.setReadonly(true);
            share.setLimitCommands(user.getLimitCommands() || !config.getBoolean(Keys.WEB_SHARE_DEVICE_COMMANDS));
            share.setDisableReports(user.getDisableReports() || !config.getBoolean(Keys.WEB_SHARE_DEVICE_REPORTS));

            share.setId(storage.addObject(share, new Request(new Columns.Exclude("id"))));

            storage.addPermission(new Permission(User.class, share.getId(), Device.class, deviceId));
        }

        return tokenManager.generateToken(share.getId(), expiration);
    }

}
