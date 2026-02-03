package org.traccar.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DeviceUpdateValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void testAllowedFields() throws Exception {
        var payload = objectMapper.readTree("""
                {
                    "id": 10,
                    "name": "AMAROK 2",
                    "category": "car"
                }
                """);

        assertTrue(DeviceUpdateValidator.hasOnlyAllowedFields(payload));
    }

    @Test
    public void testRejectUnexpectedFields() throws Exception {
        var payload = objectMapper.readTree("""
                {
                    "id": 10,
                    "name": "AMAROK 2",
                    "category": "car",
                    "uniqueId": "12345"
                }
                """);

        assertFalse(DeviceUpdateValidator.hasOnlyAllowedFields(payload));
    }

    @Test
    public void testNormalizeNameRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> DeviceUpdateValidator.normalizeName(" "));
    }

}
