package dev.holoweb.troupe.net;

import dev.holoweb.troupe.net.Protocol.Manifest;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A resource's {@code assets/} folder as a resource pack zip, built on the client from the (encrypted) cache:
 * {@code assets/<namespace>/textures/...} lands where Minecraft looks for it. The zip is named after its content,
 * so the same assets are built once.
 */
public final class AssetPacks {
    private AssetPacks() {}

    /** @return the zip in {@code dir}, or null when the resource has no assets */
    public static Path build(Manifest m, String resource, BlobCache cache, Path dir, String packMcmeta) throws IOException {
        return build(m, List.of(resource), cache, dir, packMcmeta);
    }

    /** Several resources in one pack (a later one wins a path both have). Null when none has assets. */
    public static Path build(Manifest m, List<String> resources, BlobCache cache, Path dir, String packMcmeta) throws IOException {
        Map<String, Protocol.Blob> assets = new TreeMap<>();
        for (String resource : resources) {
            Map<String, Integer> files = m.resources().get(resource);
            if (files != null) files.forEach((path, blob) -> {
                if (path.startsWith("assets/")) assets.put(path, m.blobs().get(blob));
            });
        }
        if (assets.isEmpty()) return null;

        StringBuilder shas = new StringBuilder();
        assets.forEach((path, b) -> shas.append(path).append('=').append(b.sha256()).append('\n'));
        String id = sha256(resources + "\n" + packMcmeta + "\n" + shas);
        Path zip = dir.resolve(id + ".zip");
        if (Files.isRegularFile(zip)) return zip;
        Files.createDirectories(dir);
        Path tmp = dir.resolve(id + ".zip.tmp");
        try (OutputStream out = Files.newOutputStream(tmp); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("pack.mcmeta"));
            z.write(packMcmeta.getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, Protocol.Blob> a : assets.entrySet()) {
                z.putNextEntry(new ZipEntry(a.getKey()));
                z.write(cache.read(a.getValue()));
            }
        }
        Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return zip;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
