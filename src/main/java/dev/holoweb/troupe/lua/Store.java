package dev.holoweb.troupe.lua;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code store} module's data: one JSON object per resource in {@code dir/<resource>.json}, values as Lua
 * passes them. Loaded on first use, written at {@link #flush} (every tick, only what changed). Game thread only.
 * ponytail: rewrites the whole file per change; a log or a database when stores get big.
 */
public final class Store {
    private final Path dir;
    private final Map<String, Map<String, Object>> data = new LinkedHashMap<>();
    private final Set<String> dirty = new HashSet<>();

    public Store(Path dir) {
        this.dir = dir;
    }

    public Object get(String resource, String key) throws IOException {
        return of(resource).get(key);
    }

    /** {@code value} null removes the key. */
    public void set(String resource, String key, Object value) throws IOException {
        Json.encode(value); // fail now, not at flush, on what JSON can't hold
        Map<String, Object> map = of(resource);
        if (value == null) map.remove(key);
        else map.put(key, value);
        dirty.add(resource);
    }

    public List<String> keys(String resource) throws IOException {
        return List.copyOf(of(resource).keySet());
    }

    public void flush() {
        for (String resource : List.copyOf(dirty)) {
            Path file = dir.resolve(resource + ".json"), tmp = dir.resolve(resource + ".json.tmp");
            try {
                Files.createDirectories(dir);
                Files.writeString(tmp, Json.encode(data.get(resource)), StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                dirty.remove(resource);
            } catch (IOException e) {
                LuaRuntime.LOG.error("Couldn't save the store of {}", resource, e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> of(String resource) throws IOException {
        Map<String, Object> map = data.get(resource);
        if (map != null) return map;
        Path file = dir.resolve(resource + ".json");
        map = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            Object stored;
            try {
                stored = Json.decode(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new IOException(file + " is not valid JSON; fix or delete it");
            }
            if (stored instanceof Map<?, ?> m) map.putAll((Map<String, Object>) m);
        }
        data.put(resource, map);
        return map;
    }
}
