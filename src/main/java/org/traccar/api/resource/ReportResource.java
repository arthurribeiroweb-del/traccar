/*
 * Copyright 2016 - 2023 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 - 2018 Andrey Kunitsyn (andrey@traccar.org)
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
import jakarta.ws.rs.core.Context;
import org.traccar.api.SimpleObjectResource;
import org.traccar.helper.LogAction;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.Report;
import org.traccar.model.UserRestrictions;
import org.traccar.helper.model.UserUtil;
import org.traccar.reports.CombinedReportProvider;
import org.traccar.reports.DevicesReportProvider;
import org.traccar.reports.EventsReportProvider;
import org.traccar.reports.RouteReportProvider;
import org.traccar.reports.StopsReportProvider;
import org.traccar.reports.SummaryReportProvider;
import org.traccar.reports.TripsReportProvider;
import org.traccar.reports.common.ReportExecutor;
import org.traccar.reports.common.ReportMailer;
import org.traccar.reports.model.CombinedReportItem;
import org.traccar.reports.model.DailySummaryItem;
import org.traccar.reports.model.StopReportItem;
import org.traccar.reports.model.SummaryReportItem;
import org.traccar.reports.model.TripReportItem;
import org.traccar.storage.StorageException;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Path("reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReportResource extends SimpleObjectResource<Report> {

    private static final String EXCEL = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Inject
    private CombinedReportProvider combinedReportProvider;

    @Inject
    private EventsReportProvider eventsReportProvider;

    @Inject
    private RouteReportProvider routeReportProvider;

    @Inject
    private StopsReportProvider stopsReportProvider;

    @Inject
    private SummaryReportProvider summaryReportProvider;

    @Inject
    private TripsReportProvider tripsReportProvider;

    @Inject
    private DevicesReportProvider devicesReportProvider;

    @Inject
    private ReportMailer reportMailer;

    @Inject
    private LogAction actionLogger;

    @Context
    private HttpServletRequest request;

    public ReportResource() {
        super(Report.class, "description");
    }

    private Response executeReport(long userId, boolean mail, ReportExecutor executor) {
        if (mail) {
            reportMailer.sendAsync(userId, executor);
            return Response.noContent().build();
        } else {
            StreamingOutput stream = output -> {
                try {
                    executor.execute(output);
                } catch (StorageException e) {
                    throw new WebApplicationException(e);
                }
            };
            return Response.ok(stream)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report.xlsx").build();
        }
    }

    @Path("combined")
    @GET
    public Collection<CombinedReportItem> getCombined(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "combined", from, to, deviceIds, groupIds);
        return combinedReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }

    @Path("route")
    @GET
    public Collection<Position> getRoute(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "route", from, to, deviceIds, groupIds);
        return routeReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }

    @Path("route")
    @GET
    @Produces(EXCEL)
    public Response getRouteExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("mail") boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            actionLogger.report(request, getUserId(), false, "route", from, to, deviceIds, groupIds);
            routeReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, from, to);
        });
    }

    @Path("route/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getRouteExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") final List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @PathParam("type") String type) throws StorageException {
        return getRouteExcel(deviceIds, groupIds, from, to, type.equals("mail"));
    }

    @Path("events")
    @GET
    public Stream<Event> getEvents(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("type") List<String> types,
            @QueryParam("alarm") List<String> alarms,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "events", from, to, deviceIds, groupIds);
        return eventsReportProvider.getObjects(getUserId(), deviceIds, groupIds, types, alarms, from, to);
    }

    @Path("events")
    @GET
    @Produces(EXCEL)
    public Response getEventsExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("type") List<String> types,
            @QueryParam("alarm") List<String> alarms,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("mail") boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            actionLogger.report(request, getUserId(), false, "events", from, to, deviceIds, groupIds);
            eventsReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, types, alarms, from, to);
        });
    }

    @Path("events/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getEventsExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("type") List<String> types,
            @QueryParam("alarm") List<String> alarms,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @PathParam("type") String type) throws StorageException {
        return getEventsExcel(deviceIds, groupIds, types, alarms, from, to, type.equals("mail"));
    }

    @Path("summary")
    @GET
    public Collection<SummaryReportItem> getSummary(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("daily") boolean daily) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "summary", from, to, deviceIds, groupIds);
        return summaryReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to, daily);
    }

    @Path("summary")
    @GET
    @Produces(EXCEL)
    public Response getSummaryExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("daily") boolean daily,
            @QueryParam("mail") boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            actionLogger.report(request, getUserId(), false, "summary", from, to, deviceIds, groupIds);
            summaryReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, from, to, daily);
        });
    }

    @Path("summary/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getSummaryExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("daily") boolean daily,
            @PathParam("type") String type) throws StorageException {
        return getSummaryExcel(deviceIds, groupIds, from, to, daily, type.equals("mail"));
    }

    @Path("daily")
    @GET
    public Collection<DailySummaryItem> getDaily(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);

        var user = permissionsService.getUser(getUserId());
        var timezone = UserUtil.getTimezone(permissionsService.getServer(), user).toZoneId();
        ZonedDateTime fromDateTime = ZonedDateTime.now(timezone).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime toDateTime = fromDateTime.plusDays(1).minusNanos(1);
        Date from = Date.from(fromDateTime.toInstant());
        Date to = Date.from(toDateTime.toInstant());

        actionLogger.report(request, getUserId(), false, "daily", from, to, deviceIds, groupIds);

        Map<Long, Long> alertCounts = new HashMap<>();
        try (Stream<Event> events = eventsReportProvider.getObjects(
                getUserId(), deviceIds, groupIds, List.of(Event.ALL_EVENTS), List.of(), from, to)) {
            events.forEach(event -> alertCounts.merge(event.getDeviceId(), 1L, Long::sum));
        }

        Map<Long, Double> distanceByDevice = new HashMap<>();
        for (TripReportItem trip : tripsReportProvider.getObjects(
                getUserId(), deviceIds, groupIds, from, to)) {
            long did = trip.getDeviceId();
            distanceByDevice.merge(did, trip.getDistance(), Double::sum);
        }

        Set<Long> deviceIdsUnion = new HashSet<>();
        deviceIdsUnion.addAll(distanceByDevice.keySet());
        deviceIdsUnion.addAll(alertCounts.keySet());

        for (SummaryReportItem summary : summaryReportProvider.getObjects(
                getUserId(), deviceIds, groupIds, from, to, false)) {
            deviceIdsUnion.add(summary.getDeviceId());
        }

        Collection<DailySummaryItem> results = new ArrayList<>();
        for (long deviceId : deviceIdsUnion) {
            DailySummaryItem item = new DailySummaryItem();
            item.setDeviceId(deviceId);
            item.setDistance(distanceByDevice.getOrDefault(deviceId, 0.0));
            item.setAlerts(alertCounts.getOrDefault(deviceId, 0L));
            item.setFrom(from);
            item.setTo(to);
            results.add(item);
        }

        return results;
    }

    @Path("trips")
    @GET
    public Collection<TripReportItem> getTrips(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "trips", from, to, deviceIds, groupIds);
        return tripsReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }

    @Path("trips")
    @GET
    @Produces(EXCEL)
    public Response getTripsExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("mail") boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            actionLogger.report(request, getUserId(), false, "trips", from, to, deviceIds, groupIds);
            tripsReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, from, to);
        });
    }

    @Path("trips/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getTripsExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @PathParam("type") String type) throws StorageException {
        return getTripsExcel(deviceIds, groupIds, from, to, type.equals("mail"));
    }

    @Path("stops")
    @GET
    public Collection<StopReportItem> getStops(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        actionLogger.report(request, getUserId(), false, "stops", from, to, deviceIds, groupIds);
        return stopsReportProvider.getObjects(getUserId(), deviceIds, groupIds, from, to);
    }

    @Path("stops")
    @GET
    @Produces(EXCEL)
    public Response getStopsExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @QueryParam("mail") boolean mail) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), mail, stream -> {
            actionLogger.report(request, getUserId(), false, "stops", from, to, deviceIds, groupIds);
            stopsReportProvider.getExcel(stream, getUserId(), deviceIds, groupIds, from, to);
        });
    }

    @Path("stops/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getStopsExcel(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("groupId") List<Long> groupIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to,
            @PathParam("type") String type) throws StorageException {
        return getStopsExcel(deviceIds, groupIds, from, to, type.equals("mail"));
    }

    @Path("devices/{type:xlsx|mail}")
    @GET
    @Produces(EXCEL)
    public Response getDevicesExcel(
            @PathParam("type") String type) throws StorageException {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);
        return executeReport(getUserId(), type.equals("mail"), stream -> {
            devicesReportProvider.getExcel(stream, getUserId());
        });
    }

}
