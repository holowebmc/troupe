package dev.holoweb.troupe.net;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Serves what clients download as {@code GET /<sha256>}: the place a manifest's download URL points at. Only the
 * blobs of the resources given to {@link #serve} exist here, so server code is never reachable.
 */
public final class BlobServer implements AutoCloseable {
    private final HttpServer http;
    private volatile Map<String, Path> files = Map.of();

    /** {@code port} 0 picks a free one. */
    public BlobServer(int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/", exchange -> {
            try {
                Path file = "GET".equals(exchange.getRequestMethod()) ? files.get(exchange.getRequestURI().getPath().substring(1)) : null;
                if (file == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, Files.size(file));
                Files.copy(file, exchange.getResponseBody());
            } finally {
                exchange.close();
            }
        });
        http.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "Troupe downloads");
            t.setDaemon(true);
            return t;
        }));
        http.start();
    }

    public int port() {
        return http.getAddress().getPort();
    }

    /** What clients run now. A file edited since the scan no longer matches its hash; the client then asks for it in-game. */
    public void serve(ServerResources resources) {
        Map<String, Path> map = new HashMap<>();
        for (int i = 0; i < resources.files().size(); i++) map.put(resources.manifest().blobs().get(i).sha256(), resources.files().get(i));
        files = map;
    }

    @Override
    public void close() {
        http.stop(0);
    }
}
