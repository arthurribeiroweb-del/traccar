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

public class TireRotationEventHandlerTest extends BaseTest {

    @Test
    public void testComputeScheduleStates() {
        TireRotationEventHandler.TireConfig cfg = new TireRotationEventHandler.TireConfig(
                8000L, 1000L, 100000L, null, null, null);
        var ok = TireRotationEventHandler.computeSchedule(cfg, 92000);
        assertEquals(TireRotationEventHandler.Status.OK, ok.status());

        var soon = TireRotationEventHandler.computeSchedule(cfg, 101500);
        assertEquals(TireRotationEventHandler.Status.DUE_SOON, soon.status());

        var due = TireRotationEventHandler.computeSchedule(cfg, 108500);
        assertEquals(TireRotationEventHandler.Status.OVERDUE, due.status());
    }

    @Test
    public void testEmitSoonAndDue() {
        Position lastPosition = new Position();
        lastPosition.setDeviceId(1);
        lastPosition.setFixTime(new Date(1735689600000L)); // 2025-01-01
        lastPosition.set(Position.KEY_ODOMETER, 100000000.0);

        Position position = new Position();
        position.setDeviceId(1);
        position.setFixTime(new Date(1735776000000L)); // 2025-01-02
        position.set(Position.KEY_ODOMETER, 100800000.0);

        Device device = new Device();
        device.setId(1);
        device.setAttributes(tireAttributes(8000, 1000, 100000));

        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getPosition(anyLong())).thenReturn(lastPosition);
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(device);

        TireRotationEventHandler handler = new TireRotationEventHandler(cacheManager);
        List<Event> events = new ArrayList<>();
        handler.analyzePosition(position, events::add);

        assertEquals(1, events.size());
        assertEquals(Event.TYPE_TIRE_ROTATION_SOON, events.get(0).getType());

        // second position overdue
        Position overdue = new Position();
        overdue.setDeviceId(1);
        overdue.setFixTime(new Date(1735862400000L));
        overdue.set(Position.KEY_ODOMETER, 101000000.0);

        events.clear();
        handler.analyzePosition(overdue, events::add);
        assertEquals(1, events.size());
        assertEquals(Event.TYPE_TIRE_ROTATION_DUE, events.get(0).getType());
    }

    @Test
    public void testDedupeSameState() {
        Position position = new Position();
        position.setDeviceId(1);
        position.setFixTime(new Date(1735776000000L));
        position.set(Position.KEY_ODOMETER, 100800000.0);

        Device device = new Device();
        device.setId(1);
        device.setAttributes(tireAttributes(8000, 1000, 100000));

        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getPosition(anyLong())).thenReturn(position);
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(device);

        TireRotationEventHandler handler = new TireRotationEventHandler(cacheManager);
        List<Event> events = new ArrayList<>();
        handler.analyzePosition(position, events::add);
        handler.analyzePosition(position, events::add);

        assertEquals(1, events.size()); // deduped
        assertEquals(Event.TYPE_TIRE_ROTATION_SOON, events.get(0).getType());
    }

    private static Map<String, Object> tireAttributes(long interval, long reminder, long lastKm) {
        Map<String, Object> tire = new HashMap<>();
        tire.put("intervalKm", interval);
        tire.put("reminderThresholdKm", reminder);
        tire.put("lastRotationOdometerKm", lastKm);

        Map<String, Object> maintenance = new HashMap<>();
        maintenance.put("tireRotation", tire);

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("maintenance", maintenance);
        return attributes;
    }
}
