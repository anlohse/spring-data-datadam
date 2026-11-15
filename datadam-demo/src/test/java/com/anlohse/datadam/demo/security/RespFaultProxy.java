package com.anlohse.datadam.demo.security;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** RESP2 framing proxy that injects one fault at an explicitly chosen protocol boundary. */
final class RespFaultProxy implements AutoCloseable {
    enum Fault { DROP_REQUEST, DROP_REPLY, DELAY_REPLY }
    final ServerSocket listener;
    final ExecutorService workers = Executors.newCachedThreadPool(r -> { var t = new Thread(r, "datadam-fault-proxy"); t.setDaemon(true); return t; });
    final List<Socket> sockets = new CopyOnWriteArrayList<>();
    final AtomicInteger matchingRequests = new AtomicInteger(), forwarded = new AtomicInteger();
    final AtomicBoolean armed = new AtomicBoolean(true);
    final CountDownLatch releaseReply = new CountDownLatch(1);
    final int upstreamPort; final String operation; final Fault fault;
    RespFaultProxy(int upstreamPort, String operation, Fault fault) throws IOException {
        this.upstreamPort = upstreamPort; this.operation = operation; this.fault = fault;
        listener = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        workers.submit(() -> {
            while (!listener.isClosed()) {
                try { var client = listener.accept(); sockets.add(client); workers.submit(() -> serve(client)); }
                catch (IOException e) { if (!listener.isClosed()) throw new UncheckedIOException(e); }
            }
        });
    }
    int port() { return listener.getLocalPort(); }
    void serve(Socket client) {
        try (client; var upstream = new Socket("127.0.0.1", upstreamPort)) {
            sockets.add(upstream); client.setSoTimeout(5000); upstream.setSoTimeout(5000);
            while (true) {
                byte[] request = frame(client.getInputStream());
                boolean matches = operation.equals(command(request));
                if (matches) matchingRequests.incrementAndGet();
                boolean inject = matches && armed.compareAndSet(true, false);
                if (inject && fault == Fault.DROP_REQUEST) return;
                upstream.getOutputStream().write(request); upstream.getOutputStream().flush();
                if (matches) forwarded.incrementAndGet();
                byte[] reply = frame(upstream.getInputStream()); // Backend has already answered: mutation is applied.
                if (inject && fault == Fault.DROP_REPLY) return;
                if (inject && fault == Fault.DELAY_REPLY) releaseReply.await(5, TimeUnit.SECONDS);
                client.getOutputStream().write(reply); client.getOutputStream().flush();
            }
        } catch (IOException e) { /* Expected when clients time out, a fault closes a socket, or fixture closes. */ }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    static String command(byte[] frame) throws IOException {
        var input = new ByteArrayInputStream(frame); input.read(); line(input); input.read();
        int length = Integer.parseInt(line(input));
        return new String(input.readNBytes(length), StandardCharsets.US_ASCII);
    }
    static byte[] frame(InputStream input) throws IOException {
        var output = new ByteArrayOutputStream(); copy(input, output, 0); return output.toByteArray();
    }
    static void copy(InputStream input, OutputStream output, int depth) throws IOException {
        if (depth > 32) throw new IOException("Fixture RESP nesting limit");
        int type = input.read(); if (type < 0) throw new EOFException(); output.write(type);
        String header = line(input); output.write((header + "\r\n").getBytes(StandardCharsets.UTF_8));
        if (type == '$') {
            int size = Integer.parseInt(header); if (size < 0) return;
            if (size > 16 * 1024 * 1024) throw new IOException("Fixture RESP size limit");
            byte[] bytes = input.readNBytes(size + 2); if (bytes.length != size + 2) throw new EOFException(); output.write(bytes);
        } else if (type == '*') {
            int count = Integer.parseInt(header); if (count > 100000) throw new IOException("Fixture RESP array limit");
            for (int i = 0; i < count; i++) copy(input, output, depth + 1);
        } else if (type != '+' && type != '-' && type != ':') throw new IOException("Unexpected fixture RESP type");
    }
    static String line(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int i = 0; i < 8192; i++) {
            int next = input.read(); if (next < 0) throw new EOFException();
            if (next == '\r') { if (input.read() != '\n') throw new IOException("Malformed fixture RESP line"); return bytes.toString(StandardCharsets.UTF_8); }
            bytes.write(next);
        }
        throw new IOException("Fixture RESP line limit");
    }
    @Override public void close() throws Exception {
        listener.close(); releaseReply.countDown(); for (var socket : sockets) socket.close();
        workers.shutdownNow(); if (!workers.awaitTermination(2, TimeUnit.SECONDS)) throw new AssertionError("Fault proxy workers did not stop");
    }
}
