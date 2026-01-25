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
package org.traccar.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Server;

import javax.sql.DataSource;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Singleton
public class BackupManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackupManager.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final Config config;
    private final DataSource dataSource;
    private final ExecutorService executorService;
    private final ObjectMapper objectMapper;

    private final ConcurrentMap<String, BackupStatus> jobs = new ConcurrentHashMap<>();
    private volatile String latestJobId;

    private enum DatabaseType {
        H2("h2"),
        POSTGRES("postgres"),
        MYSQL("mysql"),
        UNKNOWN("unknown");

        private final String id;

        DatabaseType(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public static DatabaseType fromConfig(Config config) {
            if (config.getBoolean(Keys.DATABASE_MEMORY)) {
                return H2;
            }
            String url = config.getString(Keys.DATABASE_URL);
            if (url == null) {
                return UNKNOWN;
            }
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.startsWith("jdbc:postgresql:")) {
                return POSTGRES;
            } else if (lower.startsWith("jdbc:mysql:") || lower.startsWith("jdbc:mariadb:")) {
                return MYSQL;
            } else if (lower.startsWith("jdbc:h2:")) {
                return H2;
            }
            return UNKNOWN;
        }
    }

    @Inject
    public BackupManager(Config config, DataSource dataSource, ExecutorService executorService, ObjectMapper objectMapper) {
        this.config = config;
        this.dataSource = dataSource;
        this.executorService = executorService;
        this.objectMapper = objectMapper;
    }

    public BackupStatus startExport() {
        BackupStatus status = createStatus("export");
        executorService.submit(() -> runExport(status));
        return status;
    }

    public BackupStatus startImport(File zipFile, boolean replace) {
        BackupStatus status = createStatus("import");
        executorService.submit(() -> runImport(status, zipFile.toPath(), replace));
        return status;
    }

    public BackupStatus getStatus(String id) {
        if (id == null || id.isEmpty()) {
            return latestJobId != null ? jobs.get(latestJobId) : null;
        }
        return jobs.get(id);
    }

    private BackupStatus createStatus(String type) {
        BackupStatus status = new BackupStatus();
        status.setId(UUID.randomUUID().toString());
        status.setType(type);
        status.setState(BackupStatus.State.QUEUED);
        status.setProgress(0);
        status.setMessage("Queued");
        status.setStarted(System.currentTimeMillis());
        jobs.put(status.getId(), status);
        latestJobId = status.getId();
        return status;
    }

    private void runExport(BackupStatus status) {
        status.setState(BackupStatus.State.RUNNING);
        status.setMessage("Preparing backup");

        Path backupDir = getBackupDir();
        Path workDir = null;
        try {
            Files.createDirectories(backupDir);
            workDir = Files.createTempDirectory(backupDir, "backup-work-");

            DatabaseType dbType = DatabaseType.fromConfig(config);
            if (dbType == DatabaseType.UNKNOWN) {
                throw new IllegalStateException("Unsupported database type");
            }

            BackupMetadata metadata = new BackupMetadata();
            metadata.setCreatedAt(Instant.now().toString());
            metadata.setServerVersion(Server.class.getPackage().getImplementationVersion());
            metadata.setDatabaseType(dbType.getId());
            metadata.setDatabaseUrl(sanitizeUrl(config.getString(Keys.DATABASE_URL)));
            status.setMetadata(metadata);

            Path sqlFile = workDir.resolve("backup.sql");
            Path dumpFile = workDir.resolve("backup.sql.gz");
            switch (dbType) {
                case POSTGRES -> exportPostgres(sqlFile, status);
                case MYSQL -> exportMysql(sqlFile, status);
                case H2 -> exportH2(sqlFile, status);
                default -> throw new IllegalStateException("Unsupported database type");
            }
            sanitizeDump(sqlFile, status);
            gzipFile(sqlFile, dumpFile);
            Files.deleteIfExists(sqlFile);
            metadata.setChecksumType("sha256");
            metadata.setChecksum(computeSha256(dumpFile));

            String fileName = "traccar-backup-" + TIMESTAMP_FORMAT.format(Instant.now()) + ".zip";
            status.setFileName(fileName);
            Path zipPath = backupDir.resolve(fileName);
            writeZip(zipPath, dumpFile, metadata);

            status.setFilePath(zipPath);
            status.setProgress(100);
            status.setState(BackupStatus.State.SUCCESS);
            status.setMessage("Backup ready");

            if (config.hasKey(Keys.BACKUP_EXTERNAL_TYPE)) {
                status.getLog().add("External storage is not configured in this build");
            }
        } catch (Exception e) {
            status.setState(BackupStatus.State.ERROR);
            status.setMessage(e.getMessage());
            LOGGER.warn("Backup export failed", e);
        } finally {
            status.setFinished(System.currentTimeMillis());
            if (workDir != null) {
                deleteDirectory(workDir, status);
            }
        }
    }

    private void runImport(BackupStatus status, Path zipPath, boolean replace) {
        status.setState(BackupStatus.State.RUNNING);
        status.setMessage("Preparing restore");

        Path backupDir = getBackupDir();
        Path workDir = null;
        try {
            Files.createDirectories(backupDir);
            workDir = Files.createTempDirectory(backupDir, "restore-work-");

            Path metadataPath = null;
            Path dumpPath = null;
            Path checksumPath = null;
            try (ZipInputStream zipInput = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipPath)))) {
                ZipEntry entry;
                while ((entry = zipInput.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    Path target = workDir.resolve(entry.getName()).normalize();
                    if (!target.startsWith(workDir)) {
                        throw new SecurityException("Invalid backup archive");
                    }
                    Files.createDirectories(target.getParent());
                    Files.copy(zipInput, target, StandardCopyOption.REPLACE_EXISTING);
                    if ("metadata.json".equals(entry.getName()) || "meta.json".equals(entry.getName())) {
                        metadataPath = target;
                    } else if (entry.getName().endsWith(".sql.gz")) {
                        dumpPath = target;
                    } else if ("checksum.txt".equals(entry.getName())) {
                        checksumPath = target;
                    }
                }
            }

            if (metadataPath == null || dumpPath == null) {
                throw new IllegalStateException("Backup archive is missing required files");
            }

            BackupMetadata metadata = objectMapper.readValue(metadataPath.toFile(), BackupMetadata.class);
            status.setMetadata(metadata);
            String serverVersion = Server.class.getPackage().getImplementationVersion();
            if (metadata.getServerVersion() != null && serverVersion != null
                    && !metadata.getServerVersion().equals(serverVersion)) {
                status.getLog().add("Backup version differs from server version");
            }

            DatabaseType dbType = DatabaseType.fromConfig(config);
            if (dbType == DatabaseType.UNKNOWN) {
                throw new IllegalStateException("Unsupported database type");
            }
            if (metadata.getDatabaseType() != null && !dbType.getId().equals(metadata.getDatabaseType())) {
                throw new IllegalStateException("Backup database type does not match current database");
            }
            String checksumValue = metadata.getChecksum();
            if (checksumValue == null && checksumPath != null) {
                checksumValue = readChecksum(checksumPath);
            }
            if (checksumValue != null) {
                String checksum = computeSha256(dumpPath);
                if (!checksumValue.equalsIgnoreCase(checksum)) {
                    throw new IllegalStateException("Backup checksum verification failed");
                }
            }

            switch (dbType) {
                case POSTGRES -> importPostgres(dumpPath, replace, status);
                case MYSQL -> importMysql(dumpPath, replace, status);
                case H2 -> importH2(dumpPath, replace, status);
                default -> throw new IllegalStateException("Unsupported database type");
            }
            status.getLog().add("Sensitive credentials were removed from backup and must be reset");

            status.setProgress(100);
            status.setState(BackupStatus.State.SUCCESS);
            status.setMessage("Restore completed");
        } catch (Exception e) {
            status.setState(BackupStatus.State.ERROR);
            status.setMessage(e.getMessage());
            LOGGER.warn("Backup restore failed", e);
        } finally {
            status.setFinished(System.currentTimeMillis());
            if (workDir != null) {
                deleteDirectory(workDir, status);
            }
            try {
                Files.deleteIfExists(zipPath);
            } catch (IOException ignored) {
                LOGGER.debug("Failed to delete temporary import file");
            }
        }
    }

    private void exportPostgres(Path output, BackupStatus status) throws Exception {
        DbTarget target = parseJdbc(config.getString(Keys.DATABASE_URL), 5432);
        String user = requireValue(config.getString(Keys.DATABASE_USER), "database user");
        String password = config.getString(Keys.DATABASE_PASSWORD, "");
        List<String> command = List.of(
                "pg_dump",
                "--host", target.host,
                "--port", String.valueOf(target.port),
                "--username", user,
                "--no-owner",
                "--no-privileges",
                "--format", "plain",
                "--inserts",
                "--column-inserts",
                target.database);
        status.getLog().add("Running pg_dump");
        runProcessToFile(command, Map.of("PGPASSWORD", password), output);
    }

    private void exportMysql(Path output, BackupStatus status) throws Exception {
        DbTarget target = parseJdbc(config.getString(Keys.DATABASE_URL), 3306);
        String user = requireValue(config.getString(Keys.DATABASE_USER), "database user");
        String password = config.getString(Keys.DATABASE_PASSWORD, "");
        List<String> command = List.of(
                "mysqldump",
                "--host", target.host,
                "--port", String.valueOf(target.port),
                "--user", user,
                "--single-transaction",
                "--skip-lock-tables",
                "--hex-blob",
                target.database);
        status.getLog().add("Running mysqldump");
        runProcessToFile(command, Map.of("MYSQL_PWD", password), output);
    }

    private void exportH2(Path output, BackupStatus status) throws Exception {
        String scriptPath = output.toAbsolutePath().toString().replace("\\", "/");
        status.getLog().add("Running H2 script export");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SCRIPT TO '" + scriptPath.replace("'", "''") + "'");
        }
    }

    private void importPostgres(Path dumpFile, boolean replace, BackupStatus status) throws Exception {
        DbTarget target = parseJdbc(config.getString(Keys.DATABASE_URL), 5432);
        String user = requireValue(config.getString(Keys.DATABASE_USER), "database user");
        String password = config.getString(Keys.DATABASE_PASSWORD, "");
        if (replace) {
            status.getLog().add("Dropping public schema");
            runProcess(List.of(
                    "psql",
                    "--host", target.host,
                    "--port", String.valueOf(target.port),
                    "--username", user,
                    "--dbname", target.database,
                    "--command", "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"),
                    Map.of("PGPASSWORD", password));
        }
        status.getLog().add("Running psql restore");
        runGzipToProcess(dumpFile, List.of(
                "psql",
                "--host", target.host,
                "--port", String.valueOf(target.port),
                "--username", user,
                "--dbname", target.database,
                "--single-transaction"),
                Map.of("PGPASSWORD", password));
    }

    private void importMysql(Path dumpFile, boolean replace, BackupStatus status) throws Exception {
        DbTarget target = parseJdbc(config.getString(Keys.DATABASE_URL), 3306);
        String user = requireValue(config.getString(Keys.DATABASE_USER), "database user");
        String password = config.getString(Keys.DATABASE_PASSWORD, "");
        if (replace) {
            status.getLog().add("Dropping database");
            runProcess(List.of(
                    "mysql",
                    "--host", target.host,
                    "--port", String.valueOf(target.port),
                    "--user", user,
                    "--execute", "DROP DATABASE IF EXISTS " + target.database + "; CREATE DATABASE "
                            + target.database + ";"),
                    Map.of("MYSQL_PWD", password));
        }
        status.getLog().add("Running mysql restore");
        runGzipToProcess(dumpFile, List.of(
                "mysql",
                "--host", target.host,
                "--port", String.valueOf(target.port),
                "--user", user,
                "--database", target.database),
                Map.of("MYSQL_PWD", password));
    }

    private void importH2(Path dumpFile, boolean replace, BackupStatus status) throws Exception {
        Path sqlFile = dumpFile.resolveSibling("restore.sql");
        try (InputStream input = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(dumpFile)));
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(sqlFile))) {
            input.transferTo(output);
        }
        String scriptPath = sqlFile.toAbsolutePath().toString().replace("\\", "/");
        status.getLog().add("Running H2 restore script");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                if (replace) {
                    statement.execute("DROP ALL OBJECTS");
                }
                statement.execute("RUNSCRIPT FROM '" + scriptPath.replace("'", "''") + "'");
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
        Files.deleteIfExists(sqlFile);
    }

    private void runProcessToFile(List<String> command, Map<String, String> env, Path output) throws Exception {
        Process process = startProcess(command, env);
        Thread drainThread = drainProcessOutput(process);
        try (InputStream input = new BufferedInputStream(process.getInputStream());
             OutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(output))) {
            input.transferTo(outputStream);
        }
        drainThread.join();
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException("Database dump failed");
        }
    }

    private void runGzipToProcess(Path inputFile, List<String> command, Map<String, String> env) throws Exception {
        Process process = startProcess(command, env);
        Thread drainThread = drainProcessOutput(process);
        try (OutputStream output = new BufferedOutputStream(process.getOutputStream());
             InputStream input = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(inputFile)))) {
            input.transferTo(output);
        }
        drainThread.join();
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException("Database restore failed");
        }
    }

    private void runProcess(List<String> command, Map<String, String> env) throws Exception {
        Process process = startProcess(command, env);
        Thread drainThread = drainProcessOutput(process);
        drainThread.join();
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException("Database command failed");
        }
    }

    private Process startProcess(List<String> command, Map<String, String> env) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.environment().putAll(env);
        return builder.start();
    }

    private Thread drainProcessOutput(Process process) {
        Thread thread = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                input.transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void gzipFile(Path input, Path output) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(input));
             OutputStream out = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))) {
            in.transferTo(out);
        }
    }

    private void writeZip(Path zipPath, Path dumpFile, BackupMetadata metadata) throws IOException {
        byte[] metadataBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(metadata);
        try (ZipOutputStream zipOutput = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipPath)))) {
            ZipEntry metaEntry = new ZipEntry("meta.json");
            zipOutput.putNextEntry(metaEntry);
            zipOutput.write(metadataBytes);
            zipOutput.closeEntry();

            ZipEntry metadataEntry = new ZipEntry("metadata.json");
            zipOutput.putNextEntry(metadataEntry);
            zipOutput.write(metadataBytes);
            zipOutput.closeEntry();

            ZipEntry dumpEntry = new ZipEntry("data/backup.sql.gz");
            zipOutput.putNextEntry(dumpEntry);
            Files.copy(dumpFile, zipOutput);
            zipOutput.closeEntry();

            ZipEntry checksumEntry = new ZipEntry("checksum.txt");
            zipOutput.putNextEntry(checksumEntry);
            String checksumValue = metadata.getChecksum() != null ? metadata.getChecksum() : "";
            zipOutput.write(("sha256 " + checksumValue).getBytes());
            zipOutput.closeEntry();
        }
    }

    private void sanitizeDump(Path sqlFile, BackupStatus status) throws IOException {
        Path sanitized = sqlFile.resolveSibling("backup.sanitized.sql");
        boolean sanitizedOnce = false;
        try (var reader = Files.newBufferedReader(sqlFile); var writer = Files.newBufferedWriter(sanitized)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String sanitizedLine = sanitizeInsertLine(line);
                if (!sanitizedOnce && !line.equals(sanitizedLine)) {
                    status.getLog().add("Sanitized sensitive fields in tc_users");
                    sanitizedOnce = true;
                }
                writer.write(sanitizedLine);
                writer.newLine();
            }
        }
        Files.move(sanitized, sqlFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private String sanitizeInsertLine(String line) {
        Matcher matcher = INSERT_USERS_PATTERN.matcher(line);
        if (!matcher.find()) {
            return line;
        }
        String tableToken = matcher.group(1);
        String columnsRaw = matcher.group(2);
        String valuesRaw = matcher.group(3);
        if (!isUsersTable(tableToken)) {
            return line;
        }

        List<String> columns = splitColumns(columnsRaw);
        List<List<String>> rows = splitRows(valuesRaw);
        if (rows.isEmpty() || columns.isEmpty()) {
            return line;
        }

        for (List<String> row : rows) {
            if (row.size() != columns.size()) {
                return line;
            }
            for (int i = 0; i < columns.size(); i++) {
                String column = columns.get(i);
                if (SENSITIVE_COLUMNS.contains(column)) {
                    row.set(i, "NULL");
                }
            }
        }

        StringBuilder builder = new StringBuilder();
        builder.append("INSERT INTO ").append(tableToken).append(" (").append(columnsRaw).append(") VALUES ");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                builder.append(",");
            }
            builder.append("(").append(String.join(", ", rows.get(i))).append(")");
        }
        builder.append(";");
        return builder.toString();
    }

    private List<String> splitColumns(String raw) {
        var columns = new java.util.ArrayList<String>();
        for (String column : raw.split(",")) {
            columns.add(normalizeColumn(column.trim()));
        }
        return columns;
    }

    private String normalizeColumn(String column) {
        if ((column.startsWith("\"") && column.endsWith("\""))
                || (column.startsWith("`") && column.endsWith("`"))) {
            return column.substring(1, column.length() - 1).toLowerCase(Locale.ROOT);
        }
        return column.toLowerCase(Locale.ROOT);
    }

    private List<List<String>> splitRows(String raw) {
        var rows = new java.util.ArrayList<List<String>>();
        int index = 0;
        while (index < raw.length()) {
            char ch = raw.charAt(index);
            if (ch == '(') {
                int end = findMatchingParen(raw, index);
                if (end < 0) {
                    return List.of();
                }
                String rowRaw = raw.substring(index + 1, end);
                rows.add(splitValues(rowRaw));
                index = end + 1;
            } else {
                index++;
            }
        }
        return rows;
    }

    private int findMatchingParen(String raw, int start) {
        boolean inString = false;
        for (int i = start; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '\'' && !isEscaped(raw, i)) {
                inString = !inString;
            }
            if (!inString && ch == ')' && i > start) {
                return i;
            }
        }
        return -1;
    }

    private List<String> splitValues(String raw) {
        var values = new java.util.ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '\\' && inString && i + 1 < raw.length()) {
                current.append(ch);
                current.append(raw.charAt(i + 1));
                i++;
                continue;
            }
            if (ch == '\'' && !isEscaped(raw, i)) {
                inString = !inString;
            }
            if (ch == ',' && !inString) {
                values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (current.length() > 0) {
            values.add(current.toString().trim());
        }
        return values;
    }

    private boolean isEscaped(String raw, int index) {
        int count = 0;
        for (int i = index - 1; i >= 0 && raw.charAt(i) == '\\'; i--) {
            count++;
        }
        return count % 2 != 0;
    }

    private boolean isUsersTable(String tableToken) {
        String normalized = tableToken.replace("\"", "").replace("`", "");
        if (normalized.contains(".")) {
            normalized = normalized.substring(normalized.lastIndexOf('.') + 1);
        }
        return "tc_users".equalsIgnoreCase(normalized);
    }

    private String computeSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IOException("Checksum calculation failed", e);
        }
    }

    private String readChecksum(Path file) throws IOException {
        String content = Files.readString(file).trim();
        if (content.isEmpty()) {
            return null;
        }
        String[] parts = content.split("\\s+");
        return parts[parts.length - 1];
    }

    private Path getBackupDir() {
        String configured = config.getString(Keys.BACKUP_PATH, "./data/backup");
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private String sanitizeUrl(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceAll("(?i)(password=)[^&;]+", "$1***");
    }

    private DbTarget parseJdbc(String url, int defaultPort) {
        if (url == null) {
            throw new IllegalStateException("Database URL is not configured");
        }
        String raw = url.substring("jdbc:".length());
        URI uri = URI.create(raw);
        String database = uri.getPath();
        if (database != null && database.startsWith("/")) {
            database = database.substring(1);
        }
        if (database == null || database.isEmpty()) {
            throw new IllegalStateException("Database name is not configured in URL");
        }
        return new DbTarget(
                uri.getHost() != null ? uri.getHost() : "localhost",
                uri.getPort() > 0 ? uri.getPort() : defaultPort,
                database);
    }

    private String requireValue(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(name + " is not configured");
        }
        return value;
    }

    private static final Pattern INSERT_USERS_PATTERN = Pattern.compile(
            "(?i)insert\\s+into\\s+([^\\s]+)\\s*\\(([^)]+)\\)\\s*values\\s*(.+);");

    private static final java.util.Set<String> SENSITIVE_COLUMNS = java.util.Set.of(
            "hashedpassword",
            "salt",
            "totpkey",
            "token");

    private void deleteDirectory(Path directory, BackupStatus status) {
        try (var stream = Files.walk(directory)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOGGER.debug("Failed to delete {}", path);
                }
            });
        } catch (IOException e) {
            status.getLog().add("Cleanup failed: " + e.getMessage());
        }
    }

    private record DbTarget(String host, int port, String database) {
    }
}
