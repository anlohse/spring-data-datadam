package io.datadam.demo.security;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import io.datadam.spring.*;
import io.datadam.spring.annotation.*;
import io.datadam.spring.config.DatadamAutoConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.annotation.Id;
import org.springframework.dao.*;
import redis.clients.jedis.JedisPool;
import static org.assertj.core.api.Assertions.*;

@Timeout(60)
class AclSecurityIT {
    @TempDir static Path directory;
    static DisposableDatadam server;
    static JedisPool writerPool, readerPool, schemaPool;
    static DatadamCommands writer, reader, schema;
    @BeforeAll static void setup() throws Exception {
        server = new DisposableDatadam(directory, "protected-mode yes\nuser default off\n"
            + "user writer on >test-writer-password ~secure:* +@connection +json.get +json.set +json.del +hreplace +hgetall +del\n"
            + "user reader on >test-reader-password %R~secure:json:visible:* %R~secure:hash:visible:* +@connection +json.get +hgetall +eval +idx.query +idx.count +idx.get +ft.search +idx.info +ft.info\n"
            + "user schema on >test-schema-password +@connection +idx.create +idx.drop +idx.info +idx.list +ft.create +ft.info\n", false);
        writerPool = pool("writer"); readerPool = pool("reader"); schemaPool = pool("schema");
        writer = new DatadamCommands(writerPool); reader = new DatadamCommands(readerPool); schema = new DatadamCommands(schemaPool);
        var indexes = new DatadamIndexOperations(schema);
        indexes.ensureIndexes(JsonDoc.class, Duration.ofSeconds(10)); indexes.ensureIndexes(HashDoc.class, Duration.ofSeconds(10));
        for (String visibility : List.of("visible:1", "visible:2", "hidden:1")) {
            writer.execute("JSON.SET", "secure:json:" + visibility, "$", "{\"id\":\"" + visibility + "\",\"team\":\"A\",\"age\":20,\"value\":\""
                + (visibility.startsWith("hidden") ? "hidden-secret" : "visible-value") + "\"}");
            writer.execute("HREPLACE", "secure:hash:" + visibility, "id", visibility, "team", "A", "age", 20,
                "value", visibility.startsWith("hidden") ? "hidden-secret" : "visible-value");
        }
    }
    @AfterAll static void cleanup() {
        if (writerPool != null) writerPool.close(); if (readerPool != null) readerPool.close(); if (schemaPool != null) schemaPool.close();
        if (server != null) server.close();
    }
    static JedisPool pool(String user) {
        var properties = server.properties(); properties.setUsername(user); properties.setPassword("test-" + user + "-password");
        return new DatadamAutoConfiguration().datadamPool(properties);
    }
    @Test void authenticationIsRequiredAndWriterCannotEscapeKeyPatterns() {
        try (var anonymous = server.pool()) {
            assertThatThrownBy(() -> new DatadamCommands(anonymous).execute("JSON.GET", "secure:json:visible:1"))
                .isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> writer.execute("JSON.SET", "outside:private", "$", "{}"))
            .isInstanceOf(PermissionDeniedDataAccessException.class);
        String key = "secure:json:write-test";
        try {
            assertThat(writer.execute("JSON.SET", key, "$", "{\"value\":\"written\"}")).isEqualTo("OK");
            assertThat(writer.execute("JSON.GET", key)).isEqualTo("{\"value\":\"written\"}");
        } finally { writer.execute("DEL", key); }
    }
    @Test void readersCannotReadHiddenDocumentsOrWriteEvenReadableKeys() {
        for (String storage : List.of("json", "hash")) {
            String read = storage.equals("json") ? "JSON.GET" : "HGETALL";
            assertThat(reader.execute(read, "secure:" + storage + ":visible:1").toString()).contains("visible-value");
            assertThatThrownBy(() -> reader.execute(read, "secure:" + storage + ":hidden:1"))
                .isInstanceOf(PermissionDeniedDataAccessException.class);
        }
        assertThatThrownBy(() -> reader.execute("JSON.SET", "secure:json:visible:1", "$", "{}"))
            .isInstanceOf(PermissionDeniedDataAccessException.class);
        assertThatThrownBy(() -> reader.execute("HREPLACE", "secure:hash:visible:1", "value", "forbidden"))
            .isInstanceOf(PermissionDeniedDataAccessException.class);
        assertThatThrownBy(() -> reader.execute("DEL", "secure:json:visible:1")).isInstanceOf(PermissionDeniedDataAccessException.class);
        assertThat(writer.execute("JSON.GET", "secure:json:visible:1").toString()).contains("visible-value");
    }
    @Test void nativeIdxAndFtTotalsAndProjectionContentRespectReadableKeysInBothFormats() {
        for (String storage : List.of("json", "hash")) {
            String compound = "secure:" + storage + ":age", search = "secure:" + storage + ":search";
            assertThat(reader.execute("IDX.COUNT", compound, "age", "=", 20)).isEqualTo(2L);
            Object idx = reader.execute("IDX.QUERY", compound, "age", "=", 20);
            assertThat(idx.toString()).contains("visible:1", "visible:2").doesNotContain("hidden", "hidden-secret");
            var ft = DatadamCommands.array(reader.execute("FT.SEARCH", search, "@group:{A}", "RETURN", 1, "value", "LIMIT", 0, 10));
            assertThat(ft.get(0)).isEqualTo(2L); assertThat(ft.toString()).contains("visible-value").doesNotContain("hidden");
            // Metadata is explicitly granted and remains global, unlike document query totals.
            assertThat(DatadamCommands.map(reader.execute("FT.INFO", search)).get("num_docs")).isEqualTo(3L);
        }
    }
    @Test void evalDoesNotBypassCommandOrKeyPermissions() {
        assertThatThrownBy(() -> reader.execute("EVAL", "return redis.call('JSON.SET',KEYS[1],'$','{}')", 1, "secure:json:visible:1"))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("NOPERM");
        assertThatThrownBy(() -> reader.execute("EVAL", "return redis.call('JSON.GET',KEYS[1])", 1, "secure:json:hidden:1"))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("NOPERM");
        assertThat(writer.execute("JSON.GET", "secure:json:visible:1").toString()).contains("visible-value");
    }
    @Test void indexAdministrationIsSeparateFromApplicationDataPermissions() {
        for (DatadamCommands client : List.of(reader, writer)) {
            assertThatThrownBy(() -> client.execute("IDX.DROP", "secure:json:age")).isInstanceOf(PermissionDeniedDataAccessException.class);
            assertThatThrownBy(() -> client.execute("IDX.CREATE", "secure:unauthorized", "SCHEMA", "age", "NUMERIC"))
                .isInstanceOf(PermissionDeniedDataAccessException.class);
        }
        assertThatThrownBy(() -> schema.execute("JSON.GET", "secure:json:hidden:1")).isInstanceOf(PermissionDeniedDataAccessException.class);
        assertThatThrownBy(() -> writer.execute("IDX.INFO", "secure:json:age")).isInstanceOf(PermissionDeniedDataAccessException.class);
        assertThat(new DatadamIndexOperations(schema).status("secure:json:age").ready()).isTrue();
        schema.execute("IDX.CREATE", "secure:administrative", "ON", "JSON", "PREFIX", 1, "secure:json:", "SCHEMA", "$.age", "AS", "age", "NUMERIC");
        new DatadamIndexOperations(schema).awaitReady("secure:administrative", Duration.ofSeconds(10));
        schema.execute("IDX.DROP", "secure:administrative");
        assertThat(writer.execute("JSON.GET", "secure:json:hidden:1").toString()).contains("hidden-secret");
    }
    @DatadamDocument(prefix = "secure:json:")
    @DatadamIndex(name = "secure:json:age", fields = @IndexField(path = "$.age", alias = "age", type = IndexField.Type.NUMERIC))
    @DatadamIndex(name = "secure:json:search", kind = DatadamIndex.Kind.SEARCH, fields = @IndexField(path = "$.team", alias = "group", caseSensitive = true))
    record JsonDoc(@Id String id) {}
    @DatadamDocument(prefix = "secure:hash:", storage = DatadamStorage.HASH)
    @DatadamIndex(name = "secure:hash:age", fields = @IndexField(path = "age", alias = "age", type = IndexField.Type.NUMERIC))
    @DatadamIndex(name = "secure:hash:search", kind = DatadamIndex.Kind.SEARCH, fields = @IndexField(path = "team", alias = "group", caseSensitive = true))
    record HashDoc(@Id String id) {}
}
