package dev.holoweb.troupe;

import com.mojang.blaze3d.platform.InputConstants;
import dev.holoweb.troupe.api.Module;
import dev.holoweb.troupe.lua.ModuleRegistry;
import dev.holoweb.troupe.lua.ResourceHost;
import dev.holoweb.troupe.mixin.KeyMappingAccess;
import dev.holoweb.troupe.mixin.OptionsAccess;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Keys the player can rebind in the game's Controls screen, under "Troupe":
 * <pre>
 * keys.register('abrir_banco', { key = 'b', label = 'Abrir banco' }, fn)     (the prelude keeps fn)
 * </pre>
 * The game only knows key mappings made at startup, so these are added to its list while their resource runs and
 * taken out when it stops; what the player chose is kept per server in {@code troupe/keys.json} (options.txt forgets
 * mappings it doesn't know at startup). {@link #interact} is the one fixed mapping, for {@code interact.add} targets.
 */
final class ClientKeys {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");
    private static final int MAX = 32;

    //? if >=1.21.9 {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(ServerApi.id("troupe:troupe"));
    //?} else
    /*private static final String CATEGORY = "key.category.troupe.troupe";*/

    static KeyMapping interact;

    /** Mod init: the fixed mapping (the entries register it), which also makes the category known to the Controls screen. */
    static void init() {
        InputConstants.Key g = InputConstants.getKey("key.keyboard.g"); // its own type: the key codes aren't GLFW's on 26.x
        interact = new KeyMapping("key.troupe.interact", g.getType(), g.getValue(), CATEGORY);
    }

    /** Shows the resource's label in the Controls screen; the name the game indexes it by stays unique. */
    private static final class Labelled extends KeyMapping {
        private final String label;

        Labelled(String unique, String label) {
            super(unique, InputConstants.UNKNOWN.getType(), InputConstants.UNKNOWN.getValue(), CATEGORY);
            this.label = label;
        }

        @Override
        public String getName() {
            return label;
        }
    }

    private record Bind(String resource, String id, Labelled mapping, String unique, String saveKey) {}

    private final List<Bind> binds = new ArrayList<>();
    private final ModuleRegistry modules;
    private final Module keys;
    private final Path file;
    private Map<String, String> saved;
    private int ticks;

    ClientKeys(ModuleRegistry modules, ResourceHost host, Path file) {
        this.modules = modules;
        this.file = file;
        keys = modules.global("keys")
                .function("register", a -> {
                    String id = a.string(0);
                    Map<String, Object> opts = a.get(1) == null ? Map.of() : a.table(1);
                    if (!ID.matcher(id).matches()) throw new IllegalArgumentException("key ids use a-z, 0-9 and '_' (32 at most)");
                    String label = opts.get("label") == null ? id : String.valueOf(opts.get("label"));
                    if (label.isBlank() || label.length() > 48) throw new IllegalArgumentException("label must have 1..48 characters");
                    InputConstants.Key key = InputConstants.UNKNOWN;
                    if (opts.get("key") != null) {
                        key = InputConstants.getKey("key.keyboard." + opts.get("key"));
                        if (key == InputConstants.UNKNOWN) throw new IllegalArgumentException("unknown key '" + opts.get("key") + "' (e.g. 'b', 'f6', 'left.alt')");
                    }
                    remove(b -> b.resource().equals(a.resource()) && b.id().equals(id)); // registering again replaces it
                    if (binds.stream().filter(b -> b.resource().equals(a.resource())).count() >= MAX)
                        throw new IllegalArgumentException("at most " + MAX + " keys per resource");
                    String unique = "troupe." + a.resource() + "." + id;
                    var server = TroupeClient.server();
                    Bind bind = new Bind(a.resource(), id, new Labelled(unique, label), unique, (server == null ? "local" : server.ip) + "|" + a.resource() + "|" + id);
                    String chosen = saved().get(bind.saveKey());
                    try {
                        bind.mapping().setKey(chosen == null ? key : InputConstants.getKey(chosen));
                    } catch (RuntimeException e) {
                        bind.mapping().setKey(key);
                    }
                    binds.add(bind);
                    var options = Minecraft.getInstance().options;
                    KeyMapping[] all = Arrays.copyOf(options.keyMappings, options.keyMappings.length + 1);
                    all[all.length - 1] = bind.mapping();
                    ((OptionsAccess) options).troupe$setKeyMappings(all);
                    KeyMapping.resetMapping();
                    return null;
                })
                .event("action");
        host.onStop(resource -> remove(b -> b.resource().equals(resource)));
    }

    /** Leaving the server: none of its keys stay in the Controls screen. */
    void clear() {
        remove(b -> true);
    }

    private void remove(java.util.function.Predicate<Bind> which) {
        List<Bind> gone = binds.stream().filter(which).toList();
        if (gone.isEmpty()) return;
        remember();
        binds.removeAll(gone);
        var options = Minecraft.getInstance().options;
        List<KeyMapping> all = new ArrayList<>(Arrays.asList(options.keyMappings));
        for (Bind b : gone) {
            all.remove(b.mapping());
            KeyMappingAccess.troupe$all().remove(b.unique());
        }
        ((OptionsAccess) options).troupe$setKeyMappings(all.toArray(new KeyMapping[0]));
        KeyMapping.resetMapping();
    }

    /** Every client tick: presses with no screen open go to the resource; once a second, what the player rebound is saved. */
    void tick() {
        boolean free = ClientCompat.screen() == null && Minecraft.getInstance().player != null;
        for (Bind b : List.copyOf(binds))
            while (b.mapping().consumeClick()) if (free) modules.emitTo(b.resource(), keys, "action", b.id());
        if (++ticks % 20 == 0 && !binds.isEmpty()) remember();
    }

    private void remember() {
        boolean changed = false;
        for (Bind b : binds) {
            String now = b.mapping().saveString();
            if (!now.equals(saved().put(b.saveKey(), now))) changed = true;
        }
        if (!changed) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(saved), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Troupe.LOG.warn("Couldn't save {}", file, e);
        }
    }

    private Map<String, String> saved() {
        if (saved != null) return saved;
        saved = new TreeMap<>();
        try {
            if (Files.exists(file))
                for (var e : com.google.gson.JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject().entrySet())
                    saved.put(e.getKey(), e.getValue().getAsString());
        } catch (IOException | RuntimeException e) {
            Troupe.LOG.warn("Couldn't read {}", file, e);
        }
        return saved;
    }
}
