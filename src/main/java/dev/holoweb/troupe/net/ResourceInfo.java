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
 * { "dependencies": ["base"], "mods": { "client": ["create"], "server": ["create"] } }
 * </pre>
 * Unknown fields are ignored.
 */
public record ResourceInfo(String name, List<String> dependencies, List<String> clientMods, List<String> serverMods) {
    public static ResourceInfo parse(String name, String json) throws IOException {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) throw new IOException("resource.json must be an object");
            JsonObject o = root.getAsJsonObject();
            List<String> deps = names(o.get("dependencies"), "dependencies");
            if (deps.contains(name)) throw new IOException("a resource can't depend on itself");
            JsonObject mods = o.has("mods") && o.get("mods").isJsonObject() ? o.getAsJsonObject("mods") : new JsonObject();
            return new ResourceInfo(name, deps, names(mods.get("client"), "mods.client"), names(mods.get("server"), "mods.server"));
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("bad resource.json: " + e.getMessage());
        }
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
