package org.traccar.handler.events;

import org.junit.jupiter.api.Test;
import org.traccar.BaseTest;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class OilChangeEventHandlerTest extends BaseTest {

    @Test
    public void testOilChangeEventByKm() {
        Position lastPosition = new Position();
        lastPosition.setDeviceId(1);
        lastPosition.setFixTime(new Date(1735689600000L)); // 2025-01-01T00:00:00Z
        lastPosition.set(Position.KEY_ODOMETER, 10999000.0);

        Position position = new Position();
        position.setDeviceId(1);
        position.setFixTime(new Date(1735776000000L)); // 2025-01-02T00:00:00Z
        position.set(Position.KEY_ODOMETER, 11001000.0);

        Device device = new Device();
        device.setId(1);
        device.setAttributes(oilAttributes(
                true,
                10000,
                10000,
                "2025-01-01T00:00:00Z",
                1000,
                6));

        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getPosition(anyLong())).thenReturn(lastPosition);
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(device);

        OilChangeEventHandler eventHandler = new OilChangeEventHandler(cacheManager);
        List<Event> events = new ArrayList<>();

        eventHandler.analyzePosition(position, events::add);

        assertEquals(1, events.size());
        assertEquals(Event.TYPE_OIL_CHANGE_DUE, events.get(0).getType());
        assertEquals("km", events.get(0).getString("oilReason"));
    }

    @Test
    public void testOilChangeEventByDate() {
        Position lastPosition = new Position();
        lastPosition.setDeviceId(1);
        lastPosition.setFixTime(new Date(1738281599000L)); // 2025-01-30T23:59:59Z

        Position position = new Position();
        position.setDeviceId(1);
        position.setFixTime(new Date(1738281601000L)); // 2025-01-31T00:00:01Z

        Device device = new Device();
        device.setId(1);
        device.setAttributes(oilAttributes(
                true,
                10000,
                10000,
                "2024-12-31T00:00:00Z",
                10000,
                1));

        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getPosition(anyLong())).thenReturn(lastPosition);
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(device);

        OilChangeEventHandler eventHandler = new OilChangeEventHandler(cacheManager);
        List<Event> events = new ArrayList<>();

        eventHandler.analyzePosition(position, events::add);

        assertEquals(1, events.size());
        assertEquals(Event.TYPE_OIL_CHANGE_DUE, events.get(0).getType());
        assertEquals("date", events.get(0).getString("oilReason"));
    }

    @Test
    public void testOilChangeDisabled() {
        Position lastPosition = new Position();
        lastPosition.setDeviceId(1);
        lastPosition.setFixTime(new Date(1735689600000L));
        lastPosition.set(Position.KEY_ODOMETER, 10999000.0);

        Position position = new Position();
        position.setDeviceId(1);
        position.setFixTime(new Date(1735776000000L));
        position.set(Position.KEY_ODOMETER, 11001000.0);

        Device device = new Device();
        device.setId(1);
        device.setAttributes(oilAttributes(
                false,
                10000,
                10000,
                "2025-01-01T00:00:00Z",
                1000,
                6));

        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getPosition(anyLong())).thenReturn(lastPosition);
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(device);

        OilChangeEventHandler eventHandler = new OilChangeEventHandler(cacheManager);
        List<Event> events = new ArrayList<>();

        eventHandler.analyzePosition(position, events::add);

        assertTrue(events.isEmpty());
    }

    @Test
    public void testOilChangeSoonByKm() {
        Position lastPosition = new Position();
        lastPosition.setDeviceId(1);
        lastPosition.setFixTime(new Date(1735689600000L)); // 2025-01-01T00:00:00Z
        lastPosition.set(Position.KEY_ODOMETER, 10951000.0);

        Position position = new Position();
        position.setDeviceId(1);
        position.setFixTime(new Date(1735776000000L)); // 2025-01-02T00:00:00Z
        position.set(Position.KEY_ODOMETER, 10950000.0);

        Device device = new Device();
        device.setId(1);
        device.setAttributes(oilAttributes(
                true,
                10000,
                10000,
                "2025-01-01T00:00:00Z",
                1000,
                6));

        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getPosition(anyLong())).thenReturn(lastPosition);
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(device);

        OilChangeEventHandler eventHandler = new OilChangeEventHandler(cacheManager);
        List<Event> events = new ArrayList<>();

        eventHandler.analyzePosition(position, events::add);

        assertEquals(1, events.size());
        assertEquals(Event.TYPE_OIL_CHANGE_SOON, events.get(0).getType());
        assertEquals("km", events.get(0).getString("oilReason"));
    }

    private static Map<String, Object> oilAttributes(
            boolean enabled,
            long odometerCurrent,
            long lastServiceOdometer,
            String lastServiceDate,
            long intervalKm,
            int intervalMonths) {
        Map<String, Object> oil = new HashMap<>();
        oil.put("enabled", enabled);
        oil.put("odometerCurrent", odometerCurrent);
        oil.put("lastServiceOdometer", lastServiceOdometer);
        oil.put("lastServiceDate", lastServiceDate);
        oil.put("intervalKm", intervalKm);
        oil.put("intervalMonths", intervalMonths);

        Map<String, Object> maintenance = new HashMap<>();
        maintenance.put("oil", oil);

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("maintenance", maintenance);
        return attributes;
    }
}
