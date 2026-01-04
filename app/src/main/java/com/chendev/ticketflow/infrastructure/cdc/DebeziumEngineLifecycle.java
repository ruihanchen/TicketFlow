package com.chendev.ticketflow.infrastructure.cdc;

import com.zaxxer.hikari.HikariDataSource;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// Derives DB connection from HikariDataSource so test profile overrides (spring.datasource.url) apply automatically,
// no separate Debezium config file needed.
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DebeziumEngineLifecycle {

    @Value("${debezium.connector.name}")
    private String connectorName;

    @Value("${debezium.connector.table-include-list}")
    private String tableIncludeList;

    @Value("${debezium.connector.slot-name}")
    private String slotName;

    @Value("${debezium.connector.publication-name}")
    private String publicationName;

    @Value("${debezium.connector.tombstones-on-delete}")
    private String tombstonesOnDelete;

    @Value("${debezium.connector.snapshot-mode}")
    private String snapshotMode;

    @Value("${debezium.offset.storage-file}")
    private String offsetStorageFile;

    @Value("${debezium.offset.schema-history-file}")
    private String schemaHistoryFile;

    @Value("${debezium.offset.flush-interval-ms}")
    private String offsetFlushIntervalMs;

    private final DataSource dataSource;
    private final InventoryChangeHandler inventoryChangeHandler;

    private ExecutorService executor;
    private DebeziumEngine<ChangeEvent<String, String>> engine;

    @PostConstruct
    public void start() throws IOException {
        ensureParentDirectoryExists(offsetStorageFile);
        ensureParentDirectoryExists(schemaHistoryFile);

        Properties props = buildConnectorProperties();

        engine = DebeziumEngine.create(Json.class)
                .using(props)
                .notifying(inventoryChangeHandler)
                .build();

        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "debezium-engine");
            t.setDaemon(true);
            return t;
        });
        executor.execute(engine);

        log.info("[Debezium] Engine started for connector: {}", connectorName);
    }

    @PreDestroy
    public void stop() {
        log.info("[Debezium] Stopping engine...");
        if (engine != null) {
            try {
                engine.close();
            } catch (IOException e) {
                log.warn("[Debezium] Error closing engine", e);
            }
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
        log.info("[Debezium] Engine stopped.");
    }

    private Properties buildConnectorProperties() {
        // requires HikariDataSource specifically to access getJdbcUrl(), getUsername(), getPassword()
        HikariDataSource hikari = unwrapHikari(dataSource);
        JdbcConnection conn = parseJdbcUrl(hikari.getJdbcUrl());

        Properties props = new Properties();

        props.setProperty("name", connectorName);
        props.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        props.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
        props.setProperty("offset.storage.file.filename", offsetStorageFile);
        props.setProperty("offset.flush.interval.ms", offsetFlushIntervalMs);

        props.setProperty("database.hostname", conn.host);
        props.setProperty("database.port", String.valueOf(conn.port));
        props.setProperty("database.user", hikari.getUsername());
        props.setProperty("database.password", hikari.getPassword());
        props.setProperty("database.dbname", conn.database);
        props.setProperty("topic.prefix", "ticketflow");
        props.setProperty("table.include.list", tableIncludeList);
        props.setProperty("plugin.name", "pgoutput");
        props.setProperty("slot.name", slotName);
        props.setProperty("publication.name", publicationName);
        //scope to table.include.list; no superuser needed
        props.setProperty("publication.autocreate.mode", "filtered");
        // when_needed: re-snapshots if the saved LSN is gone (DB restore, Testcontainers restart).
        // 'initial' would hang in those cases, it skips snapshot whenever an offset file exists.
        props.setProperty("snapshot.mode", snapshotMode);
        // tombstones serve Kafka log compaction only; suppressed here to avoid double handler invocations on delete
        props.setProperty("tombstones.on.delete", tombstonesOnDelete);

        // engine requires schema history even if the consumer doesn't use it
        props.setProperty("schema.history.internal", "io.debezium.storage.file.history.FileSchemaHistory");
        props.setProperty("schema.history.internal.file.filename", schemaHistoryFile);

        log.info("[Debezium] Connection derived from Spring DataSource: host={}, port={}, db={}, snapshot.mode={}",
                conn.host, conn.port, conn.database, snapshotMode);

        return props;
    }

    private HikariDataSource unwrapHikari(DataSource ds) {
        if (ds instanceof HikariDataSource hikari) {
            return hikari;
        }
        throw new IllegalStateException(
                "DebeziumEngineLifecycle requires a HikariDataSource but got "
                        + ds.getClass().getName());
    }

    //java.net.URI handles query params and edge cases more robustly than regex
    private JdbcConnection parseJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException(
                    "Unsupported JDBC URL for Debezium: " + jdbcUrl);
        }

        String uriString = jdbcUrl.substring("jdbc:".length());
        try {
            URI uri = new URI(uriString);
            String host = uri.getHost();
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            String path = uri.getPath();
            if (path == null || path.length() <= 1) {
                throw new IllegalStateException(
                        "JDBC URL missing database name: " + jdbcUrl);
            }
            String database = path.substring(1); // strip leading '/'
            return new JdbcConnection(host, port, database);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(
                    "Failed to parse JDBC URL: " + jdbcUrl, e);
        }
    }

    private void ensureParentDirectoryExists(String filePath) throws IOException {
        File file = new File(filePath);
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
    }

    private record JdbcConnection(String host, int port, String database) {
    }
}