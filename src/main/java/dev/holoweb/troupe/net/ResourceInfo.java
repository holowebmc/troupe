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
 * { "troupe": ">=0.2.0", "dependencies": ["base"], "mods": { "client": ["create", "map?"], "server": ["bank >= 1.2"] } }
 * </pre>
 * {@code "version": "1.2.0"} is the resource's own; a dependency may ask for a minimum ({@code "base >= 1.2"}) or be
 * optional ({@code "extras?"}), like mod modules.
 * A mod module may ask for a minimum version ({@code "bank >= 1.2"}) and may be optional ({@code "map?"}: the resource
 * starts without it and checks {@code mods.has('map')}).
 * Unknown fields are ignored. {@code troupe}: the oldest Troupe the resource runs on (null: any).
 */
public record ResourceInfo(String name, List<String> dependencies, List<Need> clientMods, List<Need> serverMods, String minTroupe,
                           String version, List<Need> dependencyNeeds) {
    /** What this resource asks of its dependency {@code name}. */
    public Need dependency(String name) {
        for (Need n : dependencyNeeds) if (n.name().equals(name)) return n;
        return new Need(name, null, false);
    }

    /** A mod module the resource uses; {@code min} null: any version. */
    public record Need(String name, String min, boolean optional) {}

    private static final java.util.regex.Pattern NEED = java.util.regex.Pattern.compile("([a-z0-9_-]+)(\\?)?(?:\\s*>=\\s*(\\d{1,6}(?:\\.\\d{1,6}){0,2}))?");

    public static ResourceInfo parse(String name, String json) throws IOException {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) throw new IOException("resource.json must be an object");
            JsonObject o = root.getAsJsonObject();
            List<Need> needs = needs(o.get("dependencies"), "dependencies");
            List<String> deps = needs.stream().map(Need::name).toList();
            String version = o.has("version") && o.get("version").isJsonPrimitive() ? o.get("version").getAsString() : null;
            if (version != null && !version.matches("[0-9A-Za-z.+_-]{1,32}")) throw new IOException("bad version '" + version + "'");
            if (deps.contains(name)) throw new IOException("a resource can't depend on itself");
            JsonObject mods = o.has("mods") && o.get("mods").isJsonObject() ? o.getAsJsonObject("mods") : new JsonObject();
            String min = o.has("troupe") && o.get("troupe").isJsonPrimitive() ? o.get("troupe").getAsString().replaceFirst("^>=\\s*", "") : null;
            if (min != null && !min.matches("\\d+\\.\\d+\\.\\d+")) throw new IOException("troupe must look like \">=0.2.0\"");
            return new ResourceInfo(name, deps, needs(mods.get("client"), "mods.client"), needs(mods.get("server"), "mods.server"), min, version, needs);
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("bad resource.json: " + e.getMessage());
        }
    }

    /** Whether Troupe {@code version} ("0.2.1", "0.2.1+26.3"; null: unknown, anything goes) is new enough. */
    public boolean runsOn(String version) {
        return minTroupe == null || version == null || atLeast(version, minTroupe);
    }

    /** Whether version {@code have} ("1.2", "v1.2.0+26.3") is {@code need} ("1.2.0") or later; false when it has no numbers. */
    public static boolean atLeast(String have, String need) {
        String[] n = need.split("\\."), h = have.replaceFirst("^\\D+", "").split("\\D+");
        try {
            for (int i = 0; i < n.length; i++) {
                int want = Integer.parseInt(n[i]), got = i < h.length && !h[i].isEmpty() ? Integer.parseInt(h[i]) : i == 0 ? -1 : 0;
                if (got != want) return got > want;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static List<Need> needs(JsonElement e, String field) throws IOException {
        if (e == null || e.isJsonNull()) return List.of();
        if (!e.isJsonArray()) throw new IOException(field + " must be a list of names");
        List<Need> out = new ArrayList<>();
        for (JsonElement item : (JsonArray) e) {
            var m = NEED.matcher(item.isJsonPrimitive() ? item.getAsString().trim() : "");
            if (!m.matches() || !Protocol.validName(m.group(1))) throw new IOException(field + ": bad entry " + item + " (\"name\", \"name?\" or \"name >= 1.2\")");
            out.add(new Need(m.group(1), m.group(3), m.group(2) != null));
        }
        return List.copyOf(out);
    }

    public List<Need> mods(Side side) {
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
