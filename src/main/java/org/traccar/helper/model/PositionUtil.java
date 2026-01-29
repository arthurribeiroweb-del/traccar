/*
 * Copyright 2022 - 2025 Anton Tananaev (anton@traccar.org)
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

import org.traccar.model.BaseModel;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class PositionUtil {

    /** 1 km/h in knots; segments below this are treated as stopped (match frontend Replay). */
    private static final double STOP_SPEED_KNOTS = 0.539957;

    private PositionUtil() {
    }

    /**
     * Haversine distance between two positions in meters.
     */
    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double toRad = Math.PI / 180.0;
        double r1 = lat1 * toRad;
        double r2 = lat2 * toRad;
        double dLat = (lat2 - lat1) * toRad;
        double dLon = (lon2 - lon1) * toRad;
        double R = 6_371_000;
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(r1) * Math.cos(r2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.sqrt(h));
    }

    /**
     * Route distance in meters: sum of haversine segments between consecutive positions
     * only when moving (speed &gt; 1 km/h). Aligns with Replay / Combined report logic.
     * Positions must be sorted by fixTime.
     */
    public static double calculateRouteDistanceMeters(List<Position> positions) {
        if (positions == null || positions.size() < 2) {
            return 0;
        }
        double meters = 0;
        for (int i = 1; i < positions.size(); i++) {
            Position prev = positions.get(i - 1);
            Position curr = positions.get(i);
            long prevTime = prev.getFixTime().getTime();
            long currTime = curr.getFixTime().getTime();
            double deltaSec = (currTime - prevTime) / 1000.0;
            if (!Double.isFinite(deltaSec) || deltaSec <= 0) {
                continue;
            }
            double prevSpeedKnots = Double.isFinite(prev.getSpeed()) ? prev.getSpeed() : 0;
            boolean stopped = prevSpeedKnots <= STOP_SPEED_KNOTS;
            if (!stopped) {
                meters += haversineMeters(
                        prev.getLatitude(), prev.getLongitude(),
                        curr.getLatitude(), curr.getLongitude());
            }
        }
        return meters;
    }

    public static boolean isLatest(CacheManager cacheManager, Position position) {
        Position lastPosition = cacheManager.getPosition(position.getDeviceId());
        return lastPosition == null || position.getFixTime().compareTo(lastPosition.getFixTime()) >= 0;
    }

    public static double calculateDistance(Position first, Position last, boolean useOdometer) {
        double distance;
        double firstOdometer = first.getDouble(Position.KEY_ODOMETER);
        double lastOdometer = last.getDouble(Position.KEY_ODOMETER);

        if (useOdometer && firstOdometer != 0.0 && lastOdometer != 0.0) {
            distance = lastOdometer - firstOdometer;
        } else {
            distance = last.getDouble(Position.KEY_TOTAL_DISTANCE) - first.getDouble(Position.KEY_TOTAL_DISTANCE);
        }
        return distance;
    }

    public static List<Position> getPositions(
            Storage storage, long deviceId, Date from, Date to) throws StorageException {
        try (var positions = getPositionsStream(storage, deviceId, from, to)) {
            return positions.toList();
        }
    }

    public static Stream<Position> getPositionsStreamWithExtra(
            Storage storage, long deviceId, Date from, Date to) throws StorageException {
        Stream<Position> extraStream = storage.getObjectsStream(Position.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("deviceId", deviceId),
                        new Condition.Compare("fixTime", "<", from)),
                new Order("fixTime", true, 1)));
        Stream<Position> positions = getPositionsStream(storage, deviceId, from, to);
        return Stream.concat(extraStream, positions);
    }

    public static Stream<Position> getPositionsStream(
            Storage storage, long deviceId, Date from, Date to) throws StorageException {
        return storage.getObjectsStream(Position.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("deviceId", deviceId),
                        new Condition.Between("fixTime", from, to)),
                new Order("fixTime")));
    }

    public static Position getEdgePosition(
            Storage storage, long deviceId, Date from, Date to, boolean end) throws StorageException {
        return storage.getObject(Position.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("deviceId", deviceId),
                        new Condition.Between("fixTime", from, to)),
                new Order("fixTime", end, 1)));
    }

    public static List<Position> getLatestPositions(Storage storage, long userId) throws StorageException {
        var devices = storage.getObjects(Device.class, new Request(
                new Columns.Include("id"),
                new Condition.Permission(User.class, userId, Device.class)));
        var deviceIds = devices.stream().map(BaseModel::getId).collect(Collectors.toUnmodifiableSet());

        var positions = storage.getObjects(Position.class, new Request(
                new Columns.All(), new Condition.LatestPositions()));
        return positions.stream()
                .filter(position -> deviceIds.contains(position.getDeviceId()))
                .toList();
    }

}
