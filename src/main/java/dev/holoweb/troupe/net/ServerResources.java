package dev.holoweb.troupe.net;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.holoweb.troupe.net.Protocol.Blob;
import dev.holoweb.troupe.net.Protocol.Manifest;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** What the server offers clients: the manifest plus, for each blob, the file it is read from. */
public record ServerResources(Manifest manifest, List<Path> files) {
    public static final ServerResources EMPTY = new ServerResources(Manifest.EMPTY, List.of());

    /**
     * Scans {@code root/<name>/}: a folder is a resource when it has a {@code resource.json}. Only the client folders
     * go into the manifest, and only resources with client files at all. Problems with one resource skip it.
     */
    public static ServerResources scan(Path root, Consumer<String> warn) throws IOException {
        Files.createDirectories(root);
        List<Blob> blobs = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        Map<String, Integer> byHash = new HashMap<>();
        Map<String, Map<String, Integer>> resources = new LinkedHashMap<>();

        List<Path> dirs;
        try (Stream<Path> list = Files.list(root)) {
            dirs = list.filter(Files::isDirectory).sorted().toList();
        }
        for (Path dir : dirs) {
            String name = dir.getFileName().toString();
            if (!Protocol.validName(name)) {
                warn.accept(name + ": resource names use only a-z, 0-9, '_' and '-'");
                continue;
            }
            Path json = dir.resolve("resource.json");
            if (!Files.isRegularFile(json)) {
                warn.accept(name + ": no resource.json");
                continue;
            }
            try (Reader r = Files.newBufferedReader(json)) {
                if (!JsonParser.parseReader(r).isJsonObject()) throw new JsonParseException("not an object");
            } catch (JsonParseException e) {
                warn.accept(name + ": bad resource.json: " + e.getMessage());
                continue;
            }

            Map<String, Integer> resourceFiles = new TreeMap<>();
            for (String sub : Protocol.CLIENT_DIRS) {
                Path subDir = dir.resolve(sub);
                if (!Files.isDirectory(subDir)) continue;
                List<Path> found;
                try (Stream<Path> walk = Files.walk(subDir)) {
                    found = walk.filter(Files::isRegularFile).sorted().toList();
                }
                for (Path file : found) {
                    String path = dir.relativize(file).toString().replace('\\', '/');
                    if (!Protocol.validPath(path)) {
                        warn.accept(name + "/" + path + ": skipped (bad file name)");
                        continue;
                    }
                    String sha = sha256(file);
                    Integer index = byHash.get(sha);
                    if (index == null) {
                        index = blobs.size();
                        byHash.put(sha, index);
                        blobs.add(new Blob(sha, Files.size(file)));
                        files.add(file);
                    }
                    resourceFiles.put(path, index);
                }
            }
            if (!resourceFiles.isEmpty()) resources.put(name, resourceFiles);
        }
        return new ServerResources(new Manifest(blobs, resources), files);
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e); // every JVM has SHA-256
        }
    }
}
