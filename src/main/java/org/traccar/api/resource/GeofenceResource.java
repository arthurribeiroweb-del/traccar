/*
 * Copyright 2016 - 2017 Anton Tananaev (anton@traccar.org)
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

import org.traccar.api.ExtendedObjectResource;
import org.traccar.model.Device;
import org.traccar.model.Geofence;
import org.traccar.model.Permission;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashSet;
import java.util.Set;

@Path("geofences")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GeofenceResource extends ExtendedObjectResource<Geofence> {

    private static final String ATTRIBUTE_RADAR = "radar";

    @Inject
    private CacheManager cacheManager;

    public GeofenceResource() {
        super(Geofence.class, "name");
    }

    private boolean isRadar(Geofence geofence) {
        return geofence != null && geofence.getBoolean(ATTRIBUTE_RADAR);
    }

    private void checkRadarAccess(Geofence geofence) {
        if (isRadar(geofence) && permissionsService.notAdmin(getUserId())) {
            throw new SecurityException("Radar geofence can be managed only by administrator");
        }
    }

    private void syncRadarToAllDevices(Geofence geofence) throws Exception {
        if (!isRadar(geofence)) {
            return;
        }

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

    private void removeRadarLinks(Geofence geofence) throws Exception {
        for (Permission permission : storage.getPermissions(Device.class, 0, Geofence.class, geofence.getId())) {
            storage.removePermission(permission);
            cacheManager.invalidatePermission(
                    true,
                    permission.getOwnerClass(), permission.getOwnerId(),
                    permission.getPropertyClass(), permission.getPropertyId(),
                    false);
        }
    }

    @Override
    @POST
    public Response add(Geofence entity) throws Exception {
        checkRadarAccess(entity);
        Response response = super.add(entity);
        syncRadarToAllDevices(entity);
        return response;
    }

    @Override
    @PUT
    @Path("{id}")
    public Response update(Geofence entity) throws Exception {
        Geofence existing = storage.getObject(Geofence.class, new Request(
                new Columns.Include("id", "attributes"),
                new Condition.Equals("id", entity.getId())));
        checkRadarAccess(existing);
        checkRadarAccess(entity);
        boolean wasRadar = isRadar(existing);

        Response response = super.update(entity);

        if (isRadar(entity)) {
            syncRadarToAllDevices(entity);
        } else if (wasRadar) {
            removeRadarLinks(entity);
        }

        return response;
    }

    @Path("{id}")
    @DELETE
    public Response remove(@PathParam("id") long id) throws Exception {
        Geofence existing = storage.getObject(Geofence.class, new Request(
                new Columns.Include("id", "attributes"),
                new Condition.Equals("id", id)));
        checkRadarAccess(existing);
        return super.remove(id);
    }

}
