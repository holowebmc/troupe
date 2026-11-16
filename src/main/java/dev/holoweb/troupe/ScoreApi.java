package dev.holoweb.troupe;

import dev.holoweb.troupe.api.Args;
import dev.holoweb.troupe.lua.ModuleRegistry;
import dev.holoweb.troupe.lua.ResourceHost;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bossbars, the sidebar, teams and the tab list (the prelude wraps bossbars as objects):
 * <pre>
 * local bar = bossbar.create(text, { color = 'purple', style = 'progress', progress = 1 })
 *   bar:set({ text, color, style, progress })  bar:show(player)  bar:hide(player)  bar:remove()
 * sidebar.set(title, { 'line 1', 'line 2', ... })  sidebar.clear()          (one sidebar for everyone; last set wins)
 * teams.set(name, { prefix, suffix, color, friendly_fire, nametag })  teams.add(name, player)  teams.remove(player)
 *   teams.delete(name)                                                      (nametag: always, never, hide_for_other_teams...)
 * player:tab(header, footer)
 * </pre>
 * What a resource made goes away when it stops. Colors: bossbar pink, blue, red, green, yellow, purple, white; teams
 * use chat colors (red, dark_aqua...). Styles: progress, notched_6, notched_10, notched_12, notched_20.
 */
final class ScoreApi {
    private static final String SIDEBAR = "troupe_sidebar";
    private static final int MAX_LINES = 15;

    private record Bar(String resource, ServerBossEvent event) {}

    private final ServerApi api;
    private final Map<String, Bar> bars = new HashMap<>();
    private final Map<String, String> teamOwners = new HashMap<>();
    private String sidebarOwner;
    private int nextBar;

    ScoreApi(ModuleRegistry modules, ResourceHost host, ServerApi api) {
        this.api = api;

        modules.global("bossbar")
                .function("create", a -> {
                    String id = "bar" + (++nextBar);
                    ServerBossEvent e = Compat.bossBar(Component.literal(a.string(0)), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
                    bars.put(id, new Bar(a.resource(), e));
                    if (a.get(1) != null) apply(e, a.table(1));
                    return id;
                })
                .function("set", a -> {
                    apply(bar(a).event(), a.table(1));
                    return null;
                })
                .function("show", a -> {
                    bar(a).event().addPlayer(online(a.string(1)));
                    return null;
                })
                .function("hide", a -> {
                    ServerPlayer p = api.find(a.string(1));
                    if (p != null) bar(a).event().removePlayer(p);
                    return null;
                })
                .function("remove", a -> {
                    Bar b = bars.remove(a.string(0));
                    if (b != null) b.event().removeAllPlayers();
                    return null;
                });

        modules.global("sidebar")
                .function("set", a -> {
                    List<Object> lines = a.list(1);
                    if (lines.size() > MAX_LINES) throw new IllegalArgumentException("at most " + MAX_LINES + " lines");
                    Scoreboard board = api.server().getScoreboard();
                    clearSidebar(board);
                    Objective o = Compat.sidebar(board, SIDEBAR, Component.literal(a.string(0)));
                    Set<String> used = new HashSet<>();
                    for (int i = 0; i < lines.size(); i++) {
                        String line = String.valueOf(lines.get(i));
                        while (!used.add(line)) line += "§r"; // the same text twice needs two holders
                        Compat.score(board, o, line, lines.size() - i);
                    }
                    sidebarOwner = a.resource();
                    return null;
                })
                .function("clear", a -> {
                    clearSidebar(api.server().getScoreboard());
                    return null;
                });

        modules.global("teams")
                .function("set", a -> {
                    String name = a.string(0);
                    if (!name.matches("[a-z0-9_-]{1,16}")) throw new IllegalArgumentException("team names use a-z, 0-9, '_' and '-' (up to 16)");
                    Scoreboard board = api.server().getScoreboard();
                    PlayerTeam team = board.getPlayerTeam(name);
                    if (team == null) {
                        team = board.addPlayerTeam(name);
                        teamOwners.put(name, a.resource());
                    } else if (!a.resource().equals(teamOwners.get(name))) {
                        throw new IllegalArgumentException("team '" + name + "' isn't this resource's");
                    }
                    Map<String, Object> o = a.get(1) == null ? Map.of() : a.table(1);
                    if (o.containsKey("prefix")) team.setPlayerPrefix(Component.literal(String.valueOf(o.get("prefix"))));
                    if (o.containsKey("suffix")) team.setPlayerSuffix(Component.literal(String.valueOf(o.get("suffix"))));
                    if (o.containsKey("color")) {
                        try {
                            Compat.teamColor(team, o.get("color") == null ? null : name(o.get("color"), "color"));
                        } catch (IllegalArgumentException e) {
                            throw new IllegalArgumentException("unknown color '" + o.get("color") + "'");
                        }
                    }
                    if (o.get("friendly_fire") instanceof Boolean b) team.setAllowFriendlyFire(b);
                    if (o.containsKey("nametag")) team.setNameTagVisibility(constant(Team.Visibility.class, o.get("nametag"), "nametag"));
                    return null;
                })
                .function("add", a -> {
                    PlayerTeam team = ownTeam(a);
                    ServerPlayer p = online(a.string(1));
                    return api.server().getScoreboard().addPlayerToTeam(p.getScoreboardName(), team);
                })
                .function("remove", a -> api.server().getScoreboard().removePlayerFromTeam(online(a.string(0)).getScoreboardName()))
                .function("delete", a -> {
                    PlayerTeam team = ownTeam(a);
                    api.server().getScoreboard().removePlayerTeam(team);
                    teamOwners.remove(team.getName());
                    return null;
                });

        modules.global("players").function("tab", a -> {
            api.player(a).connection.send(new ClientboundTabListPacket(Component.literal(a.optString(1, "")), Component.literal(a.optString(2, ""))));
            return null;
        });

        host.onStop(resource -> {
            bars.values().removeIf(b -> {
                if (!b.resource().equals(resource)) return false;
                b.event().removeAllPlayers();
                return true;
            });
            if (api.serverOrNull() == null) return;
            Scoreboard board = api.server().getScoreboard();
            if (resource.equals(sidebarOwner)) clearSidebar(board);
            teamOwners.entrySet().removeIf(e -> {
                if (!e.getValue().equals(resource)) return false;
                PlayerTeam t = board.getPlayerTeam(e.getKey());
                if (t != null) board.removePlayerTeam(t);
                return true;
            });
        });
    }

    void left(ServerPlayer player) {
        for (Bar b : bars.values()) b.event().removePlayer(player);
    }

    private void clearSidebar(Scoreboard board) {
        Objective o = board.getObjective(SIDEBAR);
        if (o != null) board.removeObjective(o);
        sidebarOwner = null;
    }

    private Bar bar(Args a) {
        Bar b = bars.get(a.string(0));
        if (b == null) throw new IllegalArgumentException("that bossbar was removed");
        return b;
    }

    private ServerPlayer online(String id) {
        ServerPlayer p = api.find(id);
        if (p == null) throw new IllegalArgumentException("player isn't online");
        return p;
    }

    private PlayerTeam ownTeam(Args a) {
        String name = a.string(0);
        PlayerTeam team = api.server().getScoreboard().getPlayerTeam(name);
        if (team == null || !a.resource().equals(teamOwners.get(name))) throw new IllegalArgumentException("no team '" + name + "' from this resource");
        return team;
    }

    private static void apply(ServerBossEvent e, Map<String, Object> o) {
        if (o.containsKey("text")) e.setName(Component.literal(String.valueOf(o.get("text"))));
        if (o.containsKey("color")) e.setColor(constant(BossEvent.BossBarColor.class, o.get("color"), "color"));
        if (o.containsKey("style")) e.setOverlay(constant(BossEvent.BossBarOverlay.class, o.get("style"), "style"));
        if (o.get("progress") instanceof Number n) e.setProgress((float) Math.max(0, Math.min(1, n.doubleValue())));
    }

    private static <E extends Enum<E>> E constant(Class<E> type, Object value, String what) {
        try {
            return Enum.valueOf(type, name(value, what));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown " + what + " '" + value + "'");
        }
    }

    /** An enum constant name from a script's lowercase word. */
    private static String name(Object value, String what) {
        if (!(value instanceof String s) || !s.matches("[a-z0-9_]+")) throw new IllegalArgumentException("bad " + what + " '" + value + "'");
        return s.toUpperCase(Locale.ROOT);
    }
}
