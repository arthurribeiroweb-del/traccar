/*
 * Copyright 2025 Anton Tananaev (anton@traccar.org)
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

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.api.security.LoginResult;
import org.traccar.api.security.LoginService;
import org.traccar.backup.BackupManager;
import org.traccar.backup.BackupStatus;
import org.traccar.model.User;
import org.traccar.storage.StorageException;

import java.io.File;

@Path("admin/backup")
@Produces(MediaType.APPLICATION_JSON)
public class BackupResource extends BaseResource {

    @Inject
    private BackupManager backupManager;

    @Inject
    private LoginService loginService;

    @Path("export")
    @POST
    public BackupExportResponse exportBackup() throws StorageException {
        permissionsService.checkAdmin(getUserId());
        BackupStatus status = backupManager.startExport();
        BackupExportResponse response = new BackupExportResponse();
        response.setBackupId(status.getId());
        response.setFilename(status.getFileName());
        if (status.getMetadata() != null && status.getMetadata().getChecksum() != null) {
            response.setChecksum("sha256:" + status.getMetadata().getChecksum());
        }
        return response;
    }

    @Path("status")
    @GET
    public BackupStatus backupStatus(@QueryParam("id") String id) throws StorageException {
        permissionsService.checkAdmin(getUserId());
        BackupStatus status = backupManager.getStatus(id);
        if (status == null) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
        }
        return status;
    }

    @Path("download")
    @GET
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response downloadBackup(@QueryParam("backupId") String id) throws StorageException {
        permissionsService.checkAdmin(getUserId());
        if (id == null || id.isBlank()) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).build());
        }
        BackupStatus status = backupManager.getStatus(id);
        if (status == null || status.getFilePath() == null) {
            throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build());
        }
        java.nio.file.Path path = status.getFilePath();
        Response.ResponseBuilder builder = Response.ok(path.toFile())
                .type("application/zip")
                .header("Content-Disposition", "attachment; filename=\"" + status.getFileName() + "\"");
        if (status.getMetadata() != null && status.getMetadata().getChecksum() != null) {
            builder.header("X-Backup-Checksum", "sha256:" + status.getMetadata().getChecksum());
        }
        return builder.build();
    }

    @Path("export/{id}")
    @GET
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response downloadBackupLegacy(@PathParam("id") String id) throws StorageException {
        return downloadBackup(id);
    }

    @Path("import")
    @POST
    @Consumes("*/*")
    public BackupStatus importBackup(
            @HeaderParam("X-Backup-Confirm") String confirm,
            @HeaderParam("X-Backup-Password") String password,
            @HeaderParam("X-Backup-Totp") Integer code,
            @HeaderParam("X-Backup-Mode") String mode,
            File inputFile) throws StorageException {
        permissionsService.checkAdmin(getUserId());
        verifyConfirmation(confirm, password, code);

        boolean replace = mode == null || mode.isBlank() || mode.equalsIgnoreCase("replace");
        return backupManager.startImport(inputFile, replace);
    }

    private void verifyConfirmation(String confirm, String password, Integer code) throws StorageException {
        if (!"RESTORE".equals(confirm)) {
            throw new SecurityException("Restore confirmation failed");
        }
        if (password == null || password.isEmpty()) {
            throw new SecurityException("Password is required");
        }
        User user = permissionsService.getUser(getUserId());
        String login = user.getLogin() != null ? user.getLogin() : user.getEmail();
        LoginResult loginResult = loginService.login(login, password, code);
        if (loginResult == null) {
            throw new SecurityException("User authorization failed");
        }
    }

    public static class BackupExportResponse {
        private String backupId;
        private String filename;
        private String checksum;

        public String getBackupId() {
            return backupId;
        }

        public void setBackupId(String backupId) {
            this.backupId = backupId;
        }

        public String getFilename() {
            return filename;
        }

        public void setFilename(String filename) {
            this.filename = filename;
        }

        public String getChecksum() {
            return checksum;
        }

        public void setChecksum(String checksum) {
            this.checksum = checksum;
        }
    }
}
