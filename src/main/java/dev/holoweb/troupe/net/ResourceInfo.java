package dev.holoweb.troupe.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.holoweb.troupe.api.Side;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code resource.json} (comments allowed):
 * <pre>
 * { "troupe": ">=0.2.0", "dependencies": ["base"], "mods": { "client": ["create"], "server": ["create"] } }
 * </pre>
 * Unknown fields are ignored. {@code troupe}: the oldest Troupe the resource runs on (null: any).
 */
public record ResourceInfo(String name, List<String> dependencies, List<String> clientMods, List<String> serverMods, String minTroupe) {
    public static ResourceInfo parse(String name, String json) throws IOException {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) throw new IOException("resource.json must be an object");
            JsonObject o = root.getAsJsonObject();
            List<String> deps = names(o.get("dependencies"), "dependencies");
            if (deps.contains(name)) throw new IOException("a resource can't depend on itself");
            JsonObject mods = o.has("mods") && o.get("mods").isJsonObject() ? o.getAsJsonObject("mods") : new JsonObject();
            String min = o.has("troupe") && o.get("troupe").isJsonPrimitive() ? o.get("troupe").getAsString().replaceFirst("^>=\\s*", "") : null;
            if (min != null && !min.matches("\\d+\\.\\d+\\.\\d+")) throw new IOException("troupe must look like \">=0.2.0\"");
            return new ResourceInfo(name, deps, names(mods.get("client"), "mods.client"), names(mods.get("server"), "mods.server"), min);
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("bad resource.json: " + e.getMessage());
        }
    }

    /** Whether Troupe {@code version} ("0.2.1", "0.2.1+26.3"; null: unknown, anything goes) is new enough. */
    public boolean runsOn(String version) {
        if (minTroupe == null || version == null) return true;
        String[] need = minTroupe.split("\\."), have = version.split("\\D+");
        for (int i = 0; i < 3; i++) {
            int n = Integer.parseInt(need[i]), h = i < have.length && !have[i].isEmpty() ? Integer.parseInt(have[i]) : 0;
            if (h != n) return h > n;
        }
        return true;
    }

    public List<String> mods(Side side) {
        return side == Side.CLIENT ? clientMods : serverMods;
    }

    private static List<String> names(JsonElement e, String field) throws IOException {
        if (e == null || e.isJsonNull()) return List.of();
        if (!e.isJsonArray()) throw new IOException(field + " must be a list of names");
        List<String> out = new ArrayList<>();
        for (JsonElement item : (JsonArray) e) {
            String n = item.isJsonPrimitive() ? item.getAsString() : "";
            if (!Protocol.validName(n)) throw new IOException(field + ": bad name '" + n + "'");
            out.add(n);
        }
        return List.copyOf(out);
    }
}
