/*
 * Copyright 2015 - 2022 Anton Tananaev (anton@traccar.org)
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

import org.traccar.api.BaseResource;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.model.UserRestrictions;
import org.traccar.reports.CsvExportProvider;
import org.traccar.reports.GpxExportProvider;
import org.traccar.reports.KmlExportProvider;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

@Path("positions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PositionResource extends BaseResource {

    @Inject
    private KmlExportProvider kmlExportProvider;

    @Inject
    private CsvExportProvider csvExportProvider;

    @Inject
    private GpxExportProvider gpxExportProvider;

    @GET
    public Collection<Position> getJson(
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("id") List<Long> positionIds,
            @QueryParam("from") Date from,
            @QueryParam("to") Date to)
            throws StorageException {

        // 1) Positions by specific position IDs
        if (!positionIds.isEmpty()) {
            var positions = new ArrayList<Position>();
            for (long positionId : positionIds) {
                Position position = storage.getObject(Position.class, new Request(
                        new Columns.All(), new Condition.Equals("id", positionId)));
                // Ensure user permission for each position's device
                permissionsService.checkPermission(Device.class, getUserId(), position.getDeviceId());
                positions.add(position);
            }
            return positions;
        }

        // 2) Positions by deviceId(s)
        if (deviceIds != null && !deviceIds.isEmpty()) {
            // Check permission on each requested device
            for (Long deviceId : deviceIds) {
                permissionsService.checkPermission(Device.class, getUserId(), deviceId);
            }

            // If from/to provided => positions for all specified devices in time range
            if (from != null && to != null) {
                permissionsService.checkRestriction(getUserId(), UserRestrictions::getDisableReports);

                // Build an OR condition for multiple deviceIds: deviceId=1 OR deviceId=2 OR ...
                var deviceConditions = new ArrayList<Condition>();
                for (Long dId : deviceIds) {
                    deviceConditions.add(new Condition.Equals("deviceId", dId));
                }
                Condition devicesOrCondition = Condition.merge(deviceConditions, Condition.MergeOperator.OR);

                return storage.getObjects(Position.class, new Request(
                        new Columns.All(),
                        new Condition.And(
                                devicesOrCondition,
                                new Condition.Between("fixTime", "from", from, "to", to)
                        ),
                        new Order("fixTime")));
            } else {
                // No from/to => return the LATEST position for each specified device
                var allLatest = storage.getObjects(Position.class, new Request(
                        new Columns.All(), new Condition.LatestPositions()));
                return allLatest.stream()
                        .filter(position -> deviceIds.contains(position.getDeviceId()))
                        .collect(Collectors.toList());
            }
        }

        // 3) Fallback to returning all latest positions if no deviceId & no positionIds
        return PositionUtil.getLatestPositions(storage, getUserId());
    }

    @DELETE
    public Response remove(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("from") Date from, @QueryParam("to") Date to) throws StorageException {

        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getReadonly);

        var conditions = new LinkedList<Condition>();
        conditions.add(new Condition.Equals("deviceId", deviceId));
        conditions.add(new Condition.Between("fixTime", "from", from, "to", to));
        storage.removeObject(Position.class, new Request(Condition.merge(conditions)));

        return Response.status(Response.Status.NO_CONTENT).build();
    }

    @Path("kml")
    @GET
    @Produces("application/vnd.google-earth.kml+xml")
    public Response getKml(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("from") Date from, @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        StreamingOutput stream = output -> {
            try {
                kmlExportProvider.generate(output, deviceId, from, to);
            } catch (StorageException e) {
                throw new WebApplicationException(e);
            }
        };
        return Response.ok(stream)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=positions.kml").build();
    }

    @Path("csv")
    @GET
    @Produces("text/csv")
    public Response getCsv(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("from") Date from, @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        StreamingOutput stream = output -> {
            try {
                csvExportProvider.generate(output, deviceId, from, to);
            } catch (StorageException e) {
                throw new WebApplicationException(e);
            }
        };
        return Response.ok(stream)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=positions.csv").build();
    }

    @Path("gpx")
    @GET
    @Produces("application/gpx+xml")
    public Response getGpx(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("from") Date from, @QueryParam("to") Date to) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        StreamingOutput stream = output -> {
            try {
                gpxExportProvider.generate(output, deviceId, from, to);
            } catch (StorageException e) {
                throw new WebApplicationException(e);
            }
        };
        return Response.ok(stream)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=positions.gpx").build();
    }
}
