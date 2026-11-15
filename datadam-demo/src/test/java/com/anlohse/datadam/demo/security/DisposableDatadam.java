package com.anlohse.datadam.demo.security;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.anlohse.datadam.spring.config.DatadamAutoConfiguration;
import com.anlohse.datadam.spring.config.DatadamProperties;
import redis.clients.jedis.JedisPool;
import com.anlohse.datadam.demo.support.TestImages;

/** Owned disposable server and volume, with an explicit loopback port that stays stable on restart. */
public final class DisposableDatadam implements AutoCloseable {
    public final String name = "datadam-security-it-" + UUID.randomUUID();
    public final int port;
    public DisposableDatadam(Path directory, String configuration, boolean tls) throws Exception {
        this(directory, configuration, tls, java.util.List.of());
    }
    public DisposableDatadam(Path directory, String configuration, boolean tls, java.util.List<String> limits) throws Exception {
        try (var reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = reservation.getLocalPort(); }
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("server.conf"), "bind 0.0.0.0\nmemcache-port 0\nread-threads 2\ndir /var/lib/datadam\n"
            + (tls ? "port 0\ntls-port 6379\n" : "port 6379\n") + configuration);
        String serverImage = TestImages.datadam();
        try {
            var args = new ArrayList<String>(java.util.List.of("create", "--name", name, "-p", "127.0.0.1:" + port + ":6379", "--entrypoint", "datadam"));
            args.addAll(limits); args.add(serverImage); args.add("/tmp/security/server.conf");
            docker(args.toArray(String[]::new));
            docker("cp", directory.toAbsolutePath().toString(), name + ":/tmp/security");
            docker("start", name);
            awaitReady();
        } catch (Exception | AssertionError e) { close(); throw e; }
    }
    public static DisposableDatadam plain(Path directory) throws Exception {
        return new DisposableDatadam(directory, "protected-mode no\n", false);
    }
    public DatadamProperties properties() {
        var p = new DatadamProperties(); p.setHost("127.0.0.1"); p.setPort(port);
        p.setConnectTimeout(Duration.ofMillis(500)); p.setReadTimeout(Duration.ofMillis(500));
        p.getPool().setMaxWait(Duration.ofMillis(500)); return p;
    }
    public JedisPool pool() { return new DatadamAutoConfiguration().datadamPool(properties()); }
    void awaitReady() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            try (var socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 300); return; }
            catch (IOException e) { Thread.sleep(50); }
        }
        throw new AssertionError("Disposable server did not start: " + docker("logs", "--tail", "15", name));
    }
    void restart() throws Exception { docker("restart", "--time", "0", name); awaitReady(); }
    public static String docker(String... arguments) throws Exception { return run(Duration.ofSeconds(30), "docker", arguments); }
    public static String run(Duration timeout, String executable, String... arguments) throws Exception {
        var command = new ArrayList<String>(); command.add(executable); Collections.addAll(command, arguments);
        var output = Files.createTempFile("datadam-process-", ".log");
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) { process.destroyForcibly(); throw new AssertionError("Test helper process timed out"); }
            String result = Files.readString(output, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new AssertionError("Test helper failed: " + result);
            return result;
        } finally { Files.deleteIfExists(output); }
    }
    @Override public void close() {
        try { docker("rm", "-f", "-v", name); }
        catch (Exception | AssertionError e) { throw new IllegalStateException("Could not remove owned test container " + name, e); }
    }
}
