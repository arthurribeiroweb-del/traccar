package org.traccar.handler.events;

import org.junit.jupiter.api.Test;
import org.traccar.BaseTest;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Position;
import org.traccar.model.Server;
import org.traccar.session.cache.CacheManager;
import org.traccar.session.state.OverspeedProcessor;
import org.traccar.session.state.OverspeedState;
import org.traccar.storage.Storage;

import java.util.ArrayList;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class OverspeedEventHandlerTest  extends BaseTest {

    private Position position(String time, double speed) throws ParseException {
        Position position = new Position();
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        position.setTime(dateFormat.parse(time));
        position.setSpeed(speed);
        return position;
    }

    private void verifyState(OverspeedState overspeedState, boolean state, long geofenceId) {
        assertEquals(state, overspeedState.getOverspeedState());
        assertEquals(geofenceId, overspeedState.getOverspeedGeofenceId());
    }

    private void testOverspeedWithPosition(long geofenceId) throws ParseException {
        OverspeedState state = new OverspeedState();

        OverspeedProcessor.updateState(state, position("2017-01-01 00:00:00", 50), 40, 1, 15000, geofenceId);
        assertNull(state.getEvent());
        verifyState(state, true, geofenceId);

        OverspeedProcessor.updateState(state, position("2017-01-01 00:00:10", 55), 40, 1, 15000, geofenceId);
        assertNull(state.getEvent());

        OverspeedProcessor.updateState(state, position("2017-01-01 00:00:20", 55), 40, 1, 15000, geofenceId);
        assertNotNull(state.getEvent());
        assertEquals(Event.TYPE_DEVICE_OVERSPEED, state.getEvent().getType());
        assertEquals(55, state.getEvent().getDouble("speed"), 0.1);
        assertEquals(40, state.getEvent().getDouble("speedLimit"), 0.1);
        assertEquals(geofenceId, state.getEvent().getGeofenceId());
        verifyState(state, true, 0);

        OverspeedProcessor.updateState(state, position("2017-01-01 00:00:30", 55), 40, 1, 15000, geofenceId);
        assertNull(state.getEvent());
        verifyState(state, true, 0);

        OverspeedProcessor.updateState(state, position("2017-01-01 00:00:30", 30), 40, 1, 15000, geofenceId);
        assertNull(state.getEvent());
        verifyState(state, false, 0);
    }

    @Test
    public void testOverspeedEventHandler() throws Exception {
        testOverspeedWithPosition(0);
        testOverspeedWithPosition(1);
    }

    private Position livePosition(String time, double speed, long geofenceId) throws ParseException {
        Position position = position(time, speed);
        position.setDeviceId(1);
        position.setGeofenceIds(List.of(geofenceId));
        return position;
    }

    private OverspeedEventHandler createHandler(Config config, Geofence geofence) {
        Device device = new Device();
        device.setId(1);
        device.setAttributes(new java.util.HashMap<>());

        Server server = new Server();

        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(device);
        when(cacheManager.getObject(eq(Geofence.class), anyLong())).thenReturn(geofence);
        when(cacheManager.getPosition(anyLong())).thenReturn(null);
        when(cacheManager.getConfig()).thenReturn(config);
        when(cacheManager.getServer()).thenReturn(server);

        Storage storage = mock(Storage.class);
        return new OverspeedEventHandler(config, cacheManager, storage);
    }

    @Test
    public void testRadarOverspeedGeneratesRepeatedEventsWithoutReset() throws Exception {
        Config config = new Config();
        config.setString(Keys.EVENT_RADAR_OVERSPEED_COOLDOWN, "0");
        config.setString(Keys.EVENT_OVERSPEED_MINIMAL_DURATION, "60");

        Geofence radar = new Geofence();
        radar.setId(10);
        radar.setName("RADAR TESTE");
        radar.set("radar", true);
        radar.set("radarActive", true);
        radar.set("radarSpeedLimitKph", 5);

        OverspeedEventHandler handler = createHandler(config, radar);

        List<Event> events = new ArrayList<>();
        handler.onPosition(livePosition("2026-02-07 00:00:00", UnitsConverter.knotsFromKph(40), 10), events::add);
        handler.onPosition(livePosition("2026-02-07 00:00:10", UnitsConverter.knotsFromKph(45), 10), events::add);

        assertEquals(2, events.size());
        assertEquals(Event.TYPE_DEVICE_OVERSPEED, events.get(0).getType());
        assertEquals(10, events.get(0).getGeofenceId());
        assertEquals("RADAR TESTE", events.get(0).getString("radarName"));
    }

    @Test
    public void testRegularOverspeedStillRequiresStateReset() throws Exception {
        Config config = new Config();
        config.setString(Keys.EVENT_OVERSPEED_MINIMAL_DURATION, "0");

        Geofence geofence = new Geofence();
        geofence.setId(20);
        geofence.set("speedLimit", 10);

        OverspeedEventHandler handler = createHandler(config, geofence);

        List<Event> events = new ArrayList<>();
        handler.onPosition(livePosition("2026-02-07 00:00:00", 20, 20), events::add);
        handler.onPosition(livePosition("2026-02-07 00:00:10", 22, 20), events::add);

        assertEquals(1, events.size());
    }

}
