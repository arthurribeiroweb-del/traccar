/*
 * Copyright 2026
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
package org.traccar.schedule;

import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.UnitsConverter;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.ObjectOperation;
import org.traccar.model.Position;
import org.traccar.model.Typed;
import org.traccar.model.User;
import org.traccar.notification.MessageException;
import org.traccar.notification.NotificationMessage;
import org.traccar.notification.NotificatorManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class TaskDailySummaryPush extends SingleScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskDailySummaryPush.class);

    private static final String TYPE_DAILY_SUMMARY_PUSH = "DAILY_SUMMARY_PUSH";
    private static final String ATTR_STATE_DATE = "dailySummaryPush.date";
    private static final String ATTR_STATE_STATUS = "dailySummaryPush.status";
    private static final String ATTR_STATE_NEXT_AT = "dailySummaryPush.nextAt";
    private static final String ATTR_STATE_RETRY_COUNT = "dailySummaryPush.retryCount";
    private static final String ATTR_STATE_SENT_AT = "dailySummaryPush.sentAt";
    private static final String ATTR_STATE_LAST_ERROR = "dailySummaryPush.lastError";
    private static final String ATTR_INSIGHTS_ENABLED = "dailySummaryInsights";
    private static final String ATTR_STATE_WHATSAPP_STATUS = "dailySummaryPush.whatsappStatus";

    private static final ZoneId FALLBACK_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final LocalTime QUIET_START = LocalTime.of(7, 0);
    private static final LocalTime QUIET_END = LocalTime.of(21, 0);
    private static final LocalTime WINDOW_START = LocalTime.of(7, 20);
    private static final LocalTime WINDOW_TARGET = LocalTime.of(7, 30);
    private static final LocalTime WINDOW_END = LocalTime.of(7, 40);
    private static final LocalTime CUTOFF = LocalTime.of(10, 0);

    private static final long MIN_MOVEMENT_SECONDS = 10 * 60;
    private static final double MIN_DISTANCE_KM = 1.0;
    private static final long LONG_STOP_MIN_SECONDS = 15 * 60;
    private static final double STOP_SPEED_KNOTS = UnitsConverter.knotsFromKph(1.0);
    private static final long[] RETRY_DELAYS_MS = {
            TimeUnit.MINUTES.toMillis(1),
            TimeUnit.MINUTES.toMillis(5),
            TimeUnit.MINUTES.toMillis(15),
    };

    private final Storage storage;
    private final CacheManager cacheManager;
    private final NotificatorManager notificatorManager;
    private final Client client;
    private final String webhookUrl;
    private final String webhookToken;

    @Inject
    public TaskDailySummaryPush(
            Storage storage, CacheManager cacheManager,
            NotificatorManager notificatorManager, Client client, Config config) {
        this.storage = storage;
        this.cacheManager = cacheManager;
        this.notificatorManager = notificatorManager;
        this.client = client;
        this.webhookUrl = config.getString(Keys.DAILY_SUMMARY_WEBHOOK_URL);
        this.webhookToken = config.getString(Keys.DAILY_SUMMARY_WEBHOOK_TOKEN);
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        executor.scheduleAtFixedRate(this, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public void run() {
        LOGGER.info("[DailySummary] job=start");
        try {
            var users = storage.getObjects(User.class, new Request(new Columns.All()));
            int processed = 0;
            for (User user : users) {
                if (user.getTemporary() || user.getDisabled() || !user.hasAttribute("notificationTokens")) {
                    continue;
                }
                processUser(user);
                processed++;
            }
            LOGGER.info("[DailySummary] job=end usersProcessed={}", processed);
        } catch (StorageException error) {
            LOGGER.warn("[DailySummary] job=error", error);
        }
    }

    private void processUser(User user) {
        try {
            ZoneId zoneId = resolveTimezone(user);
            ZonedDateTime now = ZonedDateTime.now(zoneId);
            LocalTime localTime = now.toLocalTime();

            if (localTime.isBefore(QUIET_START) || localTime.isAfter(QUIET_END)) {
                return;
            }

            LocalDate reportDate = now.toLocalDate().minusDays(1);
            String reportDateValue = reportDate.format(DATE_FORMATTER);
            resetStateIfNewDate(user, reportDateValue, zoneId);

            String status = user.getString(ATTR_STATE_STATUS, "pending");
            if ("sent".equals(status)
                    || "skipped_no_movement".equals(status)
                    || "skipped_late_or_no_data".equals(status)
                    || "skipped_push_failed".equals(status)) {
                return;
            }

            ZonedDateTime cutoff = now.with(CUTOFF);
            if (now.isAfter(cutoff)) {
                updateState(user, reportDateValue, "skipped_late_or_no_data", null, null, "late_or_no_data");
                return;
            }

            long nextAt = user.getLong(ATTR_STATE_NEXT_AT);
            if (nextAt <= 0) {
                nextAt = now.with(WINDOW_TARGET).toInstant().toEpochMilli();
                user.set(ATTR_STATE_NEXT_AT, nextAt);
                persistUserAttributes(user);
            }
            if (now.toInstant().toEpochMilli() < nextAt) {
                return;
            }

            if ("pending".equals(status) && now.toLocalTime().isBefore(WINDOW_START)) {
                return;
            }

            var summary = buildSummaryForUser(user, reportDate, zoneId);
            if (summary.deviceSummaries.isEmpty()) {
                scheduleNextCheckUntilCutoff(user, reportDateValue, now, "late_or_no_data");
                return;
            }

            if (summary.totalDistanceKm < MIN_DISTANCE_KM && summary.totalMotionSeconds < MIN_MOVEMENT_SECONDS) {
                updateState(user, reportDateValue, "skipped_no_movement", null, null, null);
                return;
            }

            LOGGER.info("[DailySummary] payload userId={} devices={} distanceKm={} motionSec={}",
                    user.getId(), summary.deviceSummaries.size(),
                    formatDistance(summary.totalDistanceKm), summary.totalMotionSeconds);

            NotificationMessage message = buildMessage(summary, reportDate);
            if (message == null) {
                scheduleNextCheckUntilCutoff(user, reportDateValue, now, "late_or_no_data");
                return;
            }

            sendPushWithRetryState(user, reportDateValue, message, now);
            sendToWebhook(user, summary, reportDate, reportDateValue);
        } catch (Exception error) {
            LOGGER.warn("Daily summary push failed for user {}", user.getId(), error);
        }
    }

    private ZoneId resolveTimezone(User user) {
        String timezone = user.getString("timezone");
        if (timezone == null || timezone.isBlank()) {
            timezone = cacheManager.getServer().getString("timezone");
        }
        if (timezone == null || timezone.isBlank()) {
            return FALLBACK_ZONE;
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception error) {
            return FALLBACK_ZONE;
        }
    }

    private void resetStateIfNewDate(User user, String reportDate, ZoneId zoneId) throws StorageException {
        String storedDate = user.getString(ATTR_STATE_DATE);
        if (reportDate.equals(storedDate)) {
            return;
        }

        user.set(ATTR_STATE_DATE, reportDate);
        user.set(ATTR_STATE_STATUS, "pending");
        user.set(ATTR_STATE_RETRY_COUNT, 0);
        user.removeAttribute(ATTR_STATE_LAST_ERROR);
        user.removeAttribute(ATTR_STATE_SENT_AT);
        user.removeAttribute(ATTR_STATE_WHATSAPP_STATUS);
        long firstAttemptAt = ZonedDateTime.now(zoneId)
                .with(WINDOW_TARGET)
                .toInstant()
                .toEpochMilli();
        user.set(ATTR_STATE_NEXT_AT, firstAttemptAt);
        persistUserAttributes(user);
    }

    private void persistUserAttributes(User user) throws StorageException {
        storage.updateObject(user, new Request(
                new Columns.Include("attributes"),
                new Condition.Equals("id", user.getId())));
        try {
            cacheManager.invalidateObject(true, User.class, user.getId(), ObjectOperation.UPDATE);
        } catch (Exception error) {
            LOGGER.warn("Daily summary push cache invalidation failed for user {}", user.getId(), error);
        }
    }

    private void updateState(
            User user, String reportDate, String status, Long nextAt, Integer retryCount, String lastError)
            throws StorageException {
        user.set(ATTR_STATE_DATE, reportDate);
        user.set(ATTR_STATE_STATUS, status);
        if (nextAt != null) {
            user.set(ATTR_STATE_NEXT_AT, nextAt);
        } else {
            user.removeAttribute(ATTR_STATE_NEXT_AT);
        }
        if (retryCount != null) {
            user.set(ATTR_STATE_RETRY_COUNT, retryCount);
        }
        if (lastError != null && !lastError.isBlank()) {
            user.set(ATTR_STATE_LAST_ERROR, lastError);
        } else {
            user.removeAttribute(ATTR_STATE_LAST_ERROR);
        }
        if ("sent".equals(status)) {
            user.set(ATTR_STATE_SENT_AT, System.currentTimeMillis());
        }
        persistUserAttributes(user);
    }

    private void scheduleNextCheckUntilCutoff(User user, String reportDate, ZonedDateTime now, String reason)
            throws StorageException {
        ZonedDateTime cutoff = now.with(CUTOFF);
        ZonedDateTime next = now.plusMinutes(15);
        if (next.isAfter(cutoff)) {
            updateState(user, reportDate, "skipped_late_or_no_data", null, null, reason);
        } else {
            updateState(
                    user,
                    reportDate,
                    "pending",
                    next.toInstant().toEpochMilli(),
                    user.getInteger(ATTR_STATE_RETRY_COUNT),
                    reason);
        }
    }

    private void sendPushWithRetryState(
            User user, String reportDate, NotificationMessage message, ZonedDateTime now) throws StorageException {

        int retryCount = user.getInteger(ATTR_STATE_RETRY_COUNT);
        try {
            sendToAvailablePushNotificator(user, message);
            updateState(user, reportDate, "sent", null, retryCount, null);
            LOGGER.info("[DailySummary] pushSent userId={} dateRef={}", user.getId(), reportDate);
        } catch (MessageException error) {
            if (retryCount >= RETRY_DELAYS_MS.length) {
                updateState(user, reportDate, "skipped_push_failed", null, retryCount, "push_failed");
            } else {
                long delay = RETRY_DELAYS_MS[retryCount];
                long nextAt = now.toInstant().toEpochMilli() + delay;
                updateState(user, reportDate, "retry_pending", nextAt, retryCount + 1, "push_retry");
            }
            LOGGER.warn("[DailySummary] pushFailed userId={}", user.getId(), error);
        }
    }

    private void sendToAvailablePushNotificator(User user, NotificationMessage message) throws MessageException {
        Set<String> available = notificatorManager.getAllNotificatorTypes().stream()
                .map(Typed::type)
                .collect(Collectors.toSet());

        if (available.contains("traccar")) {
            notificatorManager.getNotificator("traccar").send(user, message, null, null);
            return;
        }
        if (available.contains("firebase")) {
            notificatorManager.getNotificator("firebase").send(user, message, null, null);
            return;
        }
        throw new MessageException("No push notificator available");
    }

    private record DeviceSummary(
            long deviceId,
            String name,
            double distanceKm,
            long motionSeconds,
            int geofenceEnterCount,
            int geofenceExitCount,
            int maxSpeedKph) {
        int geofenceTotal() {
            return geofenceEnterCount + geofenceExitCount;
        }
    }

    private record UserSummary(
            List<DeviceSummary> deviceSummaries,
            double totalDistanceKm,
            long totalMotionSeconds,
            double totalPreviousDistanceKm,
            int totalLongStops) {
    }

    private UserSummary buildSummaryForUser(User user, LocalDate date, ZoneId zoneId) throws StorageException {
        ZonedDateTime fromZoned = date.atStartOfDay(zoneId);
        ZonedDateTime toZoned = date.plusDays(1).atStartOfDay(zoneId);
        Date from = Date.from(fromZoned.toInstant());
        Date to = Date.from(toZoned.toInstant());
        ZonedDateTime previousFromZoned = date.minusDays(1).atStartOfDay(zoneId);
        ZonedDateTime previousToZoned = date.atStartOfDay(zoneId);
        Date previousFrom = Date.from(previousFromZoned.toInstant());
        Date previousTo = Date.from(previousToZoned.toInstant());

        var devices = storage.getObjects(Device.class, new Request(
                new Columns.All(),
                new Condition.Permission(User.class, user.getId(), Device.class)));

        List<DeviceSummary> summaries = new ArrayList<>();
        double totalDistanceKm = 0;
        long totalMotionSeconds = 0;
        double totalPreviousDistanceKm = 0;
        int totalLongStops = 0;

        for (Device device : devices) {
            var positions = PositionUtil.getPositions(storage, device.getId(), from, to);
            if (positions.isEmpty()) {
                continue;
            }

            double distanceKm = PositionUtil.calculateRouteDistanceMeters(positions) / 1000.0;
            long motionSeconds = Math.round(PositionUtil.calculateMovingTimeSeconds(positions));
            int longStopCount = countLongStops(positions);
            var previousPositions = PositionUtil.getPositions(storage, device.getId(), previousFrom, previousTo);
            double previousDistanceKm = PositionUtil.calculateRouteDistanceMeters(previousPositions) / 1000.0;
            int maxSpeedKph = 0;
            for (var position : positions) {
                double speedKnots = position.getSpeed();
                if (!Double.isFinite(speedKnots)) {
                    continue;
                }
                int speedKph = (int) Math.round(UnitsConverter.kphFromKnots(speedKnots));
                if (speedKph > maxSpeedKph) {
                    maxSpeedKph = speedKph;
                }
            }

            int geofenceEnterCount = 0;
            int geofenceExitCount = 0;

            var events = storage.getObjects(Event.class, new Request(
                    new Columns.All(),
                    new Condition.And(
                            new Condition.Equals("deviceId", device.getId()),
                            new Condition.Between("eventTime", from, to)),
                    new Order("eventTime")));

            for (Event event : events) {
                if (Event.TYPE_GEOFENCE_ENTER.equals(event.getType())) {
                    geofenceEnterCount += 1;
                } else if (Event.TYPE_GEOFENCE_EXIT.equals(event.getType())) {
                    geofenceExitCount += 1;
                }
            }

            summaries.add(new DeviceSummary(
                    device.getId(),
                    device.getName(),
                    distanceKm,
                    motionSeconds,
                    geofenceEnterCount,
                    geofenceExitCount,
                    maxSpeedKph));
            totalDistanceKm += distanceKm;
            totalMotionSeconds += motionSeconds;
            totalPreviousDistanceKm += previousDistanceKm;
            totalLongStops += longStopCount;
        }

        summaries.sort(Comparator
                .comparingDouble(DeviceSummary::distanceKm)
                .thenComparingLong(DeviceSummary::motionSeconds)
                .reversed()
                .thenComparing(DeviceSummary::name, String.CASE_INSENSITIVE_ORDER));

        return new UserSummary(summaries, totalDistanceKm, totalMotionSeconds, totalPreviousDistanceKm, totalLongStops);
    }

    private NotificationMessage buildMessage(UserSummary summary, LocalDate reportDate) {
        if (summary.deviceSummaries.isEmpty()) {
            return null;
        }

        var top = summary.deviceSummaries.size() > 2
                ? summary.deviceSummaries.subList(0, 2)
                : summary.deviceSummaries;

        String title;
        String body = buildCompactBody(summary);
        String reportPath;

        if (top.size() == 1) {
            DeviceSummary item = top.get(0);
            String vehicle = limitName(item.name, 24);
            title = "Resumo de ontem \u2022 " + vehicle;
            reportPath = "/reports/daily?date=" + reportDate.format(DATE_FORMATTER) + "&deviceId=" + item.deviceId;
        } else {
            DeviceSummary first = top.get(0);
            DeviceSummary second = top.get(1);
            title = "Resumo de ontem";
            reportPath = "/reports/daily?date=" + reportDate.format(DATE_FORMATTER)
                    + "&deviceId=" + first.deviceId
                    + "&deviceId=" + second.deviceId;
        }

        Map<String, String> data = new HashMap<>();
        data.put("reportPath", reportPath);
        data.put("summaryType", TYPE_DAILY_SUMMARY_PUSH);
        return new NotificationMessage(title, body, body, true, data);
    }

    private String joinParts(String separator, String... parts) {
        StringJoiner joiner = new StringJoiner(separator);
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                joiner.add(part);
            }
        }
        return joiner.toString();
    }

    private String buildCompactBody(UserSummary summary) {
        int geofenceTotal = summary.deviceSummaries.stream().mapToInt(DeviceSummary::geofenceTotal).sum();
        int averageSpeedKph = calculateAverageSpeedKph(summary.totalDistanceKm, summary.totalMotionSeconds);
        int maxSpeedKph = summary.deviceSummaries.stream().mapToInt(DeviceSummary::maxSpeedKph).max().orElse(0);
        String distanceDelta = buildDistanceDeltaInsight(summary.totalDistanceKm, summary.totalPreviousDistanceKm);

        return joinParts(" \u2022 ",
                "\uD83D\uDEE3\uFE0F " + formatDistance(summary.totalDistanceKm) + " km",
                "\u23F1\uFE0F " + formatMotion(summary.totalMotionSeconds),
                "\uD83D\uDCCD " + geofenceTotal,
                "\uD83C\uDFCE\uFE0F " + averageSpeedKph + " km/h",
                "\uD83D\uDE80 " + maxSpeedKph + " km/h",
                distanceDelta,
                "\uD83D\uDED1 " + summary.totalLongStops);
    }

    private String formatDistance(double distanceKm) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(new Locale("pt", "BR"));
        symbols.setDecimalSeparator(',');
        DecimalFormat decimalFormat = new DecimalFormat("0.0", symbols);
        return decimalFormat.format(distanceKm);
    }

    private int calculateAverageSpeedKph(double distanceKm, long motionSeconds) {
        if (distanceKm <= 0 || motionSeconds <= 0) {
            return 0;
        }
        double hours = motionSeconds / 3600.0;
        if (!Double.isFinite(hours) || hours <= 0) {
            return 0;
        }
        return (int) Math.round(distanceKm / hours);
    }

    private String buildDistanceDeltaInsight(double currentDistanceKm, double previousDistanceKm) {
        if (previousDistanceKm <= 0) {
            return "";
        }
        double deltaPercent = ((currentDistanceKm - previousDistanceKm) / previousDistanceKm) * 100.0;
        String sign = deltaPercent > 0 ? "+" : "";
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(new Locale("pt", "BR"));
        symbols.setDecimalSeparator(',');
        DecimalFormat decimalFormat = new DecimalFormat("0", symbols);
        return "\uD83D\uDCCA " + sign + decimalFormat.format(deltaPercent) + "% km";
    }

    private int countLongStops(List<Position> positions) {
        if (positions == null || positions.size() < 2) {
            return 0;
        }
        long stoppedSeconds = 0;
        int longStops = 0;
        for (int index = 1; index < positions.size(); index++) {
            Position previous = positions.get(index - 1);
            Position current = positions.get(index);
            long deltaSeconds = (current.getFixTime().getTime() - previous.getFixTime().getTime()) / 1000;
            if (deltaSeconds <= 0) {
                continue;
            }
            double previousSpeedKnots = Double.isFinite(previous.getSpeed()) ? previous.getSpeed() : 0;
            boolean moving = previousSpeedKnots > STOP_SPEED_KNOTS;
            if (moving) {
                if (stoppedSeconds >= LONG_STOP_MIN_SECONDS) {
                    longStops += 1;
                }
                stoppedSeconds = 0;
            } else {
                stoppedSeconds += deltaSeconds;
            }
        }
        if (stoppedSeconds >= LONG_STOP_MIN_SECONDS) {
            longStops += 1;
        }
        return longStops;
    }

    private String formatMotion(long motionSeconds) {
        if (motionSeconds <= 0) {
            return "0m";
        }
        long minutes = Math.max(1, Math.round(motionSeconds / 60.0));
        long hoursPart = minutes / 60;
        long minutesPart = minutes % 60;
        if (hoursPart > 0) {
            return String.format(Locale.ROOT, "%dh%02d", hoursPart, minutesPart);
        }
        return minutes + "m";
    }

    private String limitName(String value, int limit) {
        if (value == null || value.isBlank()) {
            return "Veiculo";
        }
        if (value.length() <= limit) {
            return value;
        }
        if (limit <= 3) {
            return value.substring(0, Math.max(limit, 1));
        }
        return value.substring(0, limit - 3) + "...";
    }

    // ── n8n Webhook Fan-Out ──────────────────────────────────────────────

    private void sendToWebhook(User user, UserSummary summary, LocalDate reportDate, String reportDateValue) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        String whatsappStatus = user.getString(ATTR_STATE_WHATSAPP_STATUS, "pending");
        if ("sent".equals(whatsappStatus)) {
            LOGGER.debug("[DailySummary] webhook skipped (already sent) userId={} dateRef={}",
                    user.getId(), reportDateValue);
            return;
        }

        Map<String, Object> payload = buildWebhookPayload(user, summary, reportDate);

        var requestBuilder = client.target(webhookUrl).request();
        requestBuilder.header("Content-Type", "application/json");
        if (webhookToken != null && !webhookToken.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + webhookToken);
        }

        requestBuilder.async().post(Entity.json(payload), new InvocationCallback<Response>() {
            @Override
            public void completed(Response response) {
                int status = response.getStatusInfo().getStatusCode();
                if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
                    LOGGER.info("[DailySummary] webhookSent userId={} dateRef={} httpStatus={}",
                            user.getId(), reportDateValue, status);
                    try {
                        user.set(ATTR_STATE_WHATSAPP_STATUS, "sent");
                        persistUserAttributes(user);
                    } catch (StorageException e) {
                        LOGGER.warn("[DailySummary] webhookSent but failed to persist whatsapp status", e);
                    }
                } else {
                    LOGGER.warn("[DailySummary] webhookFailed userId={} dateRef={} httpStatus={}",
                            user.getId(), reportDateValue, status);
                    try {
                        user.set(ATTR_STATE_WHATSAPP_STATUS, "failed");
                        persistUserAttributes(user);
                    } catch (StorageException e) {
                        LOGGER.warn("[DailySummary] webhookFailed and failed to persist whatsapp status", e);
                    }
                }
            }

            @Override
            public void failed(Throwable throwable) {
                LOGGER.warn("[DailySummary] webhookError userId={} dateRef={}",
                        user.getId(), reportDateValue, throwable);
                try {
                    user.set(ATTR_STATE_WHATSAPP_STATUS, "failed");
                    persistUserAttributes(user);
                } catch (StorageException e) {
                    LOGGER.warn("[DailySummary] webhookError and failed to persist whatsapp status", e);
                }
            }
        });
    }

    private Map<String, Object> buildWebhookPayload(User user, UserSummary summary, LocalDate reportDate) {
        String dateRef = reportDate.format(DATE_FORMATTER);
        int maxSpeedKph = summary.deviceSummaries.stream().mapToInt(DeviceSummary::maxSpeedKph).max().orElse(0);
        int avgSpeedKph = calculateAverageSpeedKph(summary.totalDistanceKm, summary.totalMotionSeconds);
        Double deltaPercent = null;
        if (summary.totalPreviousDistanceKm > 0) {
            deltaPercent = ((summary.totalDistanceKm - summary.totalPreviousDistanceKm)
                    / summary.totalPreviousDistanceKm) * 100.0;
        }

        List<Map<String, Object>> deviceList = new ArrayList<>();
        for (DeviceSummary ds : summary.deviceSummaries) {
            Map<String, Object> deviceMap = new LinkedHashMap<>();
            deviceMap.put("deviceId", ds.deviceId);
            deviceMap.put("vehicleName", ds.name);
            deviceMap.put("distanceKm", Math.round(ds.distanceKm * 10.0) / 10.0);
            deviceMap.put("motionSeconds", ds.motionSeconds);
            deviceMap.put("motionFormatted", formatMotion(ds.motionSeconds));
            deviceMap.put("geofenceEnterCount", ds.geofenceEnterCount);
            deviceMap.put("geofenceExitCount", ds.geofenceExitCount);
            deviceMap.put("geofenceTotal", ds.geofenceTotal());
            deviceMap.put("avgSpeedKmh", calculateAverageSpeedKph(ds.distanceKm, ds.motionSeconds));
            deviceMap.put("maxSpeedKmh", ds.maxSpeedKph);
            deviceList.add(deviceMap);
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("distanceKm", Math.round(summary.totalDistanceKm * 10.0) / 10.0);
        totals.put("motionSeconds", summary.totalMotionSeconds);
        totals.put("motionFormatted", formatMotion(summary.totalMotionSeconds));
        totals.put("avgSpeedKmh", avgSpeedKph);
        totals.put("maxSpeedKmh", maxSpeedKph);
        totals.put("distanceDeltaPercent", deltaPercent);
        totals.put("longStops", summary.totalLongStops);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", user.getId());
        payload.put("userName", user.getName());
        payload.put("userPhone", user.getPhone());
        payload.put("userEmail", user.getEmail());
        payload.put("dateRef", dateRef);
        payload.put("devices", deviceList);
        payload.put("totals", totals);
        return payload;
    }
}
