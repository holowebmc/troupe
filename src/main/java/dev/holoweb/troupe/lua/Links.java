package dev.holoweb.troupe.lua;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The server's {@link Network}: one {@link Link} per connected player with Troupe. Game thread only. */
public final class Links implements Network {
    private final ResourceHost host;
    private final Map<String, Link> byPlayer = new LinkedHashMap<>();

    public Links(ResourceHost host) {
        this.host = host;
    }

    /** A player finished joining: they get all global state, then START, then events and state as they happen. */
    public void add(Link link) {
        byPlayer.put(link.peer().id(), link);
        host.forEachGlobalState((resource, key, json) -> link.state(resource, null, key, json));
        link.start();
    }

    public Link get(String playerId) {
        return byPlayer.get(playerId);
    }

    /** The player left: their pending calls fail and their state is dropped. */
    public void remove(String playerId) {
        Link link = byPlayer.remove(playerId);
        if (link != null) link.close();
        host.playerLeft(playerId);
    }

    @Override
    public void emit(String resource, String player, String name, String json) {
        if (player == null) {
            for (Link l : List.copyOf(byPlayer.values())) l.emit(resource, null, name, json);
            return;
        }
        Link l = byPlayer.get(player);
        if (l != null) l.emit(resource, player, name, json);
    }

    @Override
    public void call(String resource, String player, String name, String json, Reply reply) {
        Link l = byPlayer.get(player);
        if (l == null) reply.reply(false, Json.encode("player isn't online or doesn't have Troupe"));
        else l.call(resource, player, name, json, reply);
    }

    @Override
    public void state(String resource, String player, String key, String json) {
        if (player == null) {
            for (Link l : List.copyOf(byPlayer.values())) l.state(resource, null, key, json);
            return;
        }
        Link l = byPlayer.get(player);
        if (l != null) l.state(resource, player, key, json);
    }

    @Override
    public void clear(String resource) {
        for (Link l : List.copyOf(byPlayer.values())) l.clear(resource);
    }
}
