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
package org.traccar.radar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.DistanceCalculator;
import org.traccar.model.Position;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Singleton
public class StaticRadarManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(StaticRadarManager.class);

    private static final String DEFAULT_RELATIVE_FILE = "web/radars/scdb-radars-br.geojson";
    private static final String DEFAULT_INSTALL_FILE = "/opt/traccar/web/radars/scdb-radars-br.geojson";
    private static final double GRID_CELL_DEGREES = 0.02;

    private final Config config;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final double defaultRadiusMeters;
    private final double minSpeedKph;
    private final double maxSpeedKph;
    private final long reloadIntervalMs;
    private final Path overridePath;

    private volatile RadarIndex radarIndex = RadarIndex.empty();
    private volatile Path sourcePath;
    private volatile long sourceModifiedAt = Long.MIN_VALUE;
    private volatile long overrideModifiedAt = Long.MIN_VALUE;
    private volatile Map<String, Double> radiusOverrides = Map.of();
    private volatile long lastReloadCheck = 0;
    private volatile boolean warnedMissing;

    private record RadarEntry(
            long radarId,
            String radarName,
            double latitude,
            double longitude,
            double speedLimitKph,
            double radiusMeters) {
    }

    private record RadarIndex(Map<Long, List<RadarEntry>> buckets, int latitudeCellRange, double maxRadiusMeters, int count) {
        private static RadarIndex empty() {
            return new RadarIndex(Map.of(), 1, 1, 0);
        }
    }

    public record RadarMatch(long radarId, String radarName, double speedLimitKph) {
    }

    @Inject
    public StaticRadarManager(Config config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        enabled = config.getBoolean(Keys.EVENT_STATIC_RADAR_ENABLED);
        defaultRadiusMeters = Math.max(5, config.getDouble(Keys.EVENT_STATIC_RADAR_RADIUS_METERS));
        minSpeedKph = Math.max(0, config.getDouble(Keys.EVENT_STATIC_RADAR_MIN_SPEED_KPH));
        maxSpeedKph = Math.max(minSpeedKph, config.getDouble(Keys.EVENT_STATIC_RADAR_MAX_SPEED_KPH));
        reloadIntervalMs = Math.max(10_000, config.getLong(Keys.EVENT_STATIC_RADAR_RELOAD_INTERVAL) * 1000L);
        String overrideFile = config.getString(Keys.EVENT_STATIC_RADAR_OVERRIDE_FILE);
        overridePath = overrideFile != null && !overrideFile.isBlank() ? Path.of(overrideFile.trim()) : null;
    }

    public RadarMatch match(Position position) {
        if (!enabled || position == null) {
            return null;
        }

        double latitude = position.getLatitude();
        double longitude = position.getLongitude();
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            return null;
        }

        reloadIfNeeded();

        RadarIndex current = radarIndex;
        if (current.count() == 0) {
            return null;
        }

        int latitudeCell = toLatitudeCell(latitude);
        int longitudeCell = toLongitudeCell(longitude);
        int latitudeRange = current.latitudeCellRange();
        int longitudeRange = longitudeCellRange(current.maxRadiusMeters(), latitude);

        RadarEntry bestEntry = null;
        double bestDistance = Double.MAX_VALUE;
        for (int latOffset = -latitudeRange; latOffset <= latitudeRange; latOffset++) {
            for (int lonOffset = -longitudeRange; lonOffset <= longitudeRange; lonOffset++) {
                List<RadarEntry> bucket = current.buckets().get(bucketKey(latitudeCell + latOffset, longitudeCell + lonOffset));
                if (bucket == null || bucket.isEmpty()) {
                    continue;
                }
                for (RadarEntry entry : bucket) {
                    double distance = DistanceCalculator.distance(
                            latitude, longitude, entry.latitude(), entry.longitude());
                    if (distance <= entry.radiusMeters() && distance < bestDistance) {
                        bestDistance = distance;
                        bestEntry = entry;
                    }
                }
            }
        }

        if (bestEntry == null) {
            return null;
        }
        return new RadarMatch(bestEntry.radarId(), bestEntry.radarName(), bestEntry.speedLimitKph());
    }

    public synchronized Map<String, Double> getRadiusOverrides() {
        reloadOverridesIfNeeded();
        return Map.copyOf(radiusOverrides);
    }

    public synchronized double setRadiusOverride(String externalId, double radiusMeters) throws IOException {
        String normalizedExternalId = externalId != null ? externalId.trim() : "";
        if (normalizedExternalId.isEmpty()) {
            throw new IllegalArgumentException("INVALID_EXTERNAL_ID");
        }
        if (!Double.isFinite(radiusMeters) || radiusMeters <= 0) {
            throw new IllegalArgumentException("INVALID_RADIUS");
        }
        if (overridePath == null) {
            throw new IllegalStateException("STATIC_RADAR_OVERRIDE_FILE_DISABLED");
        }

        reloadOverridesIfNeeded();
        Map<String, Double> updated = new HashMap<>(radiusOverrides);
        updated.put(normalizedExternalId, radiusMeters);
        writeOverrides(updated);

        radiusOverrides = Collections.unmodifiableMap(updated);
        overrideModifiedAt = Files.getLastModifiedTime(overridePath).toMillis();
        lastReloadCheck = 0;
        sourceModifiedAt = Long.MIN_VALUE;
        return radiusMeters;
    }

    private synchronized void reloadIfNeeded() {
        if (!enabled) {
            return;
        }

        long now = System.currentTimeMillis();
        if (lastReloadCheck != 0 && now - lastReloadCheck < reloadIntervalMs) {
            return;
        }
        lastReloadCheck = now;
        long previousOverrideModifiedAt = overrideModifiedAt;
        reloadOverridesIfNeeded();
        boolean overridesChanged = previousOverrideModifiedAt != overrideModifiedAt;

        for (Path candidate : resolveCandidatePaths()) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }

            try {
                long sourceModified = Files.getLastModifiedTime(candidate).toMillis();
                if (candidate.equals(sourcePath)
                        && sourceModified == sourceModifiedAt
                        && radarIndex.count() > 0
                        && !overridesChanged) {
                    return;
                }

                RadarIndex loadedIndex = loadIndex(candidate, radiusOverrides);
                radarIndex = loadedIndex;
                sourcePath = candidate;
                sourceModifiedAt = sourceModified;
                warnedMissing = false;

                LOGGER.info(
                        "Loaded {} static radars from {} (overrides: {})",
                        loadedIndex.count(),
                        candidate.toAbsolutePath(),
                        radiusOverrides.size());
                return;
            } catch (IOException error) {
                LOGGER.warn("Failed to load static radar catalog from {}", candidate.toAbsolutePath(), error);
            }
        }

        if (!warnedMissing) {
            LOGGER.warn(
                    "Static radar catalog file not found. Checked: {}",
                    resolveCandidatePaths().stream().map(path -> path.toAbsolutePath().toString()).toList());
            warnedMissing = true;
        }
        radarIndex = RadarIndex.empty();
        sourcePath = null;
        sourceModifiedAt = Long.MIN_VALUE;
    }

    private RadarIndex loadIndex(Path file, Map<String, Double> overrides) throws IOException {
        JsonNode root;
        try (InputStream inputStream = Files.newInputStream(file)) {
            root = objectMapper.readTree(inputStream);
        }

        if (!"FeatureCollection".equalsIgnoreCase(root.path("type").asText())) {
            return RadarIndex.empty();
        }

        JsonNode features = root.path("features");
        if (!features.isArray()) {
            return RadarIndex.empty();
        }

        Map<Long, List<RadarEntry>> buckets = new HashMap<>();
        long syntheticRadarId = -1;
        int count = 0;
        double maxRadiusMeters = defaultRadiusMeters;

        for (JsonNode feature : features) {
            JsonNode geometry = feature.path("geometry");
            if (!"Point".equalsIgnoreCase(geometry.path("type").asText())) {
                continue;
            }

            JsonNode coordinates = geometry.path("coordinates");
            if (!coordinates.isArray() || coordinates.size() < 2) {
                continue;
            }

            double longitude = coordinates.get(0).asDouble(Double.NaN);
            double latitude = coordinates.get(1).asDouble(Double.NaN);
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
                continue;
            }

            JsonNode properties = feature.path("properties");
            double speedLimitKph = asDouble(properties.get("speedKph"));
            if (!Double.isFinite(speedLimitKph) || speedLimitKph < minSpeedKph || speedLimitKph > maxSpeedKph) {
                continue;
            }

            double radiusMeters = asDouble(properties.get("radiusMeters"));
            if (!Double.isFinite(radiusMeters) || radiusMeters <= 0) {
                radiusMeters = defaultRadiusMeters;
            }

            String externalId = textOrEmpty(properties.get("externalId"));
            Double overrideRadius = externalId.isEmpty() ? null : overrides.get(externalId);
            if (overrideRadius != null && Double.isFinite(overrideRadius) && overrideRadius > 0) {
                radiusMeters = overrideRadius;
            }

            long radarId = parseRadarId(externalId, syntheticRadarId);
            if (radarId == syntheticRadarId) {
                syntheticRadarId--;
            }

            String radarName = buildRadarName(externalId, speedLimitKph);
            RadarEntry entry = new RadarEntry(
                    radarId,
                    radarName,
                    latitude,
                    longitude,
                    speedLimitKph,
                    radiusMeters);

            long bucketKey = bucketKey(toLatitudeCell(latitude), toLongitudeCell(longitude));
            buckets.computeIfAbsent(bucketKey, key -> new ArrayList<>()).add(entry);
            maxRadiusMeters = Math.max(maxRadiusMeters, radiusMeters);
            count++;
        }

        int latitudeCellRange = Math.max(1, (int) Math.ceil(
                DistanceCalculator.getLatitudeDelta(maxRadiusMeters) / GRID_CELL_DEGREES));
        return new RadarIndex(buckets, latitudeCellRange, maxRadiusMeters, count);
    }

    private void reloadOverridesIfNeeded() {
        if (overridePath == null) {
            radiusOverrides = Map.of();
            overrideModifiedAt = Long.MIN_VALUE;
            return;
        }
        if (!Files.isRegularFile(overridePath)) {
            radiusOverrides = Map.of();
            overrideModifiedAt = Long.MIN_VALUE;
            return;
        }
        try {
            long modified = Files.getLastModifiedTime(overridePath).toMillis();
            if (modified == overrideModifiedAt) {
                return;
            }

            Map<String, Double> loaded = objectMapper.readValue(
                    overridePath.toFile(), new TypeReference<Map<String, Double>>() {
            });
            Map<String, Double> sanitized = new HashMap<>();
            if (loaded != null) {
                for (Map.Entry<String, Double> entry : loaded.entrySet()) {
                    String key = entry.getKey() != null ? entry.getKey().trim() : "";
                    Double value = entry.getValue();
                    if (!key.isEmpty() && value != null && Double.isFinite(value) && value > 0) {
                        sanitized.put(key, value);
                    }
                }
            }
            radiusOverrides = Collections.unmodifiableMap(sanitized);
            overrideModifiedAt = modified;
            LOGGER.info("Loaded {} static radar radius overrides from {}", sanitized.size(), overridePath.toAbsolutePath());
        } catch (IOException error) {
            LOGGER.warn("Failed to load static radar radius overrides from {}", overridePath.toAbsolutePath(), error);
        }
    }

    private void writeOverrides(Map<String, Double> overrides) throws IOException {
        Path absolutePath = overridePath.toAbsolutePath();
        Path parent = absolutePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tempPath = absolutePath.resolveSibling(absolutePath.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), overrides);
        try {
            Files.move(tempPath, absolutePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            Files.move(tempPath, absolutePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<Path> resolveCandidatePaths() {
        Set<Path> uniquePaths = new LinkedHashSet<>();

        String configured = config.getString(Keys.EVENT_STATIC_RADAR_FILE);
        if (configured != null && !configured.isBlank()) {
            uniquePaths.add(Path.of(configured.trim()));
        }

        uniquePaths.add(Path.of(DEFAULT_RELATIVE_FILE));
        uniquePaths.add(Path.of(DEFAULT_INSTALL_FILE));

        return new ArrayList<>(uniquePaths);
    }

    private static double asDouble(JsonNode node) {
        if (node == null || node.isNull()) {
            return Double.NaN;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText().trim());
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static String textOrEmpty(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText("").trim();
    }

    private static String buildRadarName(String externalId, double speedLimitKph) {
        int roundedSpeed = (int) Math.round(speedLimitKph);
        if (externalId != null && !externalId.isBlank()) {
            return "Radar " + roundedSpeed + " km/h #" + externalId;
        }
        return "Radar " + roundedSpeed + " km/h";
    }

    private static long parseRadarId(String externalId, long fallbackId) {
        if (externalId != null && !externalId.isBlank()) {
            try {
                return Long.parseLong(externalId);
            } catch (NumberFormatException ignored) {
                return fallbackId;
            }
        }
        return fallbackId;
    }

    private static int toLatitudeCell(double latitude) {
        return (int) Math.floor((latitude + 90.0) / GRID_CELL_DEGREES);
    }

    private static int toLongitudeCell(double longitude) {
        return (int) Math.floor((longitude + 180.0) / GRID_CELL_DEGREES);
    }

    private static long bucketKey(int latitudeCell, int longitudeCell) {
        return ((long) latitudeCell << 32) | (longitudeCell & 0xffffffffL);
    }

    private static int longitudeCellRange(double maxRadiusMeters, double latitude) {
        double deltaDegrees = DistanceCalculator.getLongitudeDelta(maxRadiusMeters, latitude);
        if (!Double.isFinite(deltaDegrees) || deltaDegrees <= 0) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(deltaDegrees / GRID_CELL_DEGREES));
    }
}
