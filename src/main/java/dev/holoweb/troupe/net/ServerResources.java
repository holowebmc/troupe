package dev.holoweb.troupe.net;

import dev.holoweb.troupe.net.Protocol.Blob;
import dev.holoweb.troupe.net.Protocol.Manifest;

import java.io.IOException;
import java.io.InputStream;
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

/**
 * The server's resources: every valid one in {@code infos} (folder {@code root/<name>/}), and what clients get: the
 * manifest plus, for each blob, the file it is read from.
 */
public record ServerResources(Manifest manifest, List<Path> files, Map<String, ResourceInfo> infos, Path root) {
    public static final ServerResources EMPTY = new ServerResources(Manifest.EMPTY, List.of(), Map.of(), Path.of("resources"));

    /**
     * Scans {@code root/<name>/}: a folder is a resource when it has a {@code resource.json}. Only resources with client
     * files go into the manifest, with their client folders and {@code resource.json}. Problems with one resource skip it.
     */
    public static ServerResources scan(Path root, Consumer<String> warn) throws IOException {
        Files.createDirectories(root);
        List<Blob> blobs = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        Map<String, Integer> byHash = new HashMap<>();
        Map<String, Map<String, Integer>> resources = new LinkedHashMap<>();
        Map<String, ResourceInfo> infos = new LinkedHashMap<>();

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
            try {
                infos.put(name, ResourceInfo.parse(name, Files.readString(json)));
            } catch (IOException e) {
                warn.accept(name + ": " + e.getMessage());
                continue;
            }

            Map<String, Path> clientFiles = new TreeMap<>();
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
                    clientFiles.put(path, file);
                }
            }
            if (clientFiles.isEmpty()) continue;
            clientFiles.put(Protocol.RESOURCE_JSON, json);

            Map<String, Integer> resourceFiles = new TreeMap<>();
            for (Map.Entry<String, Path> f : clientFiles.entrySet()) {
                String sha = sha256(f.getValue());
                Integer index = byHash.get(sha);
                if (index == null) {
                    index = blobs.size();
                    byHash.put(sha, index);
                    blobs.add(new Blob(sha, Files.size(f.getValue())));
                    files.add(f.getValue());
                }
                resourceFiles.put(f.getKey(), index);
            }
            resources.put(name, resourceFiles);
        }
        return new ServerResources(new Manifest(blobs, resources, Map.of()), files, infos, root);
    }

    /**
     * What clients get: only {@code running} resources (with client files), with their generations. Blobs are
     * renumbered so the manifest lists only what those resources use.
     */
    public ServerResources only(Map<String, Integer> running) {
        List<Blob> blobs = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        Map<Integer, Integer> renumber = new HashMap<>();
        Map<String, Map<String, Integer>> resources = new LinkedHashMap<>();
        Map<String, Integer> generations = new LinkedHashMap<>();
        manifest.resources().forEach((name, paths) -> {
            if (!running.containsKey(name)) return;
            Map<String, Integer> mapped = new TreeMap<>();
            paths.forEach((path, blob) -> mapped.put(path, renumber.computeIfAbsent(blob, b -> {
                blobs.add(manifest.blobs().get(b));
                files.add(this.files.get(b));
                return blobs.size() - 1;
            })));
            resources.put(name, mapped);
            generations.put(name, running.get(name));
        });
        return new ServerResources(new Manifest(blobs, resources, generations, manifest.key()), files, infos, root);
    }

    /** The server's cache key goes out with the manifest ({@link BlobCache}). */
    public ServerResources withKey(String key) {
        return new ServerResources(manifest.withKey(key), files, infos, root);
    }

    /**
     * Dev mode: a cheap fingerprint per resource folder (paths, sizes, modification times), to notice edits.
     * ponytail: walks every file each call; a WatchService if folders get big.
     */
    public static Map<String, Long> stamps(Path root) {
        Map<String, Long> out = new TreeMap<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                long stamp = 17;
                try (Stream<Path> walk = Files.walk(dir)) {
                    for (Path f : walk.filter(Files::isRegularFile).sorted().toList())
                        stamp = stamp * 31 + (dir.relativize(f) + ":" + Files.size(f) + ":" + Files.getLastModifiedTime(f).toMillis()).hashCode();
                } catch (IOException | java.io.UncheckedIOException e) {
                    stamp = -1; // mid-save; looks changed, and the next look settles it
                }
                out.put(dir.getFileName().toString(), stamp);
            }
        } catch (IOException e) {
            return out;
        }
        return out;
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
