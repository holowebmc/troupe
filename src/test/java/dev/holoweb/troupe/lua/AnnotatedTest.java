package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.api.Args;
import dev.holoweb.troupe.api.LuaFunction;
import dev.holoweb.troupe.api.LuaProperty;
import dev.holoweb.troupe.api.Module;
import dev.holoweb.troupe.api.Side;
import dev.holoweb.troupe.net.ResourceInfo;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

/** What a mod writes with annotations, a listener for somebody else's callback, java.* and the LuaLS definitions. */
class AnnotatedTest {
    private final List<Object> seen = new ArrayList<>();

    public static final class Wallet {
        @LuaProperty
        public final String owner;
        @LuaProperty
        public long limit = 100;
        private long coins;

        Wallet(String owner) {
            this.owner = owner;
        }

        @LuaProperty("coins")
        public long coins() {
            return coins;
        }

        @LuaProperty("coins")
        public void setCoins(long value) {
            if (value < 0) throw new IllegalArgumentException("coins can't be negative");
            coins = value;
        }

        @LuaFunction
        public long add(int amount, String note) {
            coins += amount;
            return coins;
        }

        public long size() {
            return coins;
        }
    }

    public static final class Api {
        final CompletableFuture<Object> later = new CompletableFuture<>();
        final Wallet wallet = new Wallet("ana");

        @LuaFunction
        public Wallet open() {
            return wallet;
        }

        @LuaFunction("describe")
        public String text(Wallet w, boolean loud, Args all) {
            return w.owner + (loud ? "!" : "") + " " + all.count() + " " + all.resource();
        }

        @LuaFunction
        public CompletionStage<Object> load(String id) {
            return later;
        }
    }

    /** Shaped like another mod's callbacks. */
    public interface Arrived {
        void arrived(String who, Thread unknown, long at);
    }

    public interface Allow {
        boolean allow(String who);
    }

    private ResourceHost host(Side side) {
        ResourceHost host = new ResourceHost(side, Runnable::run);
        host.modules().module("probe").function("see", a -> seen.add(a.get(0)));
        return host;
    }

    private void run(ResourceHost host, String main) throws Exception {
        host.setAvailable(Map.of("r", new ResourceHost.Entry(ResourceInfo.parse("r", "{}"),
                Map.of(host.side().folder() + "/main.lua", ("local see = mods.get('probe').see\n" + main).getBytes(StandardCharsets.UTF_8))::get)));
        host.start("r");
    }

    @Test
    void annotatedMethodsBecomeFunctionsMethodsAndProperties() throws Exception {
        ResourceHost host = host(Side.CLIENT);
        Api api = new Api();
        host.modules().type(Wallet.class, "wallet");
        host.modules().module("bank", api);
        run(host, """
                local bank = mods.get('bank')
                local w = bank.open()
                see(w.owner); see(w:add(5, 'gift')); see(w:add(1)); see(w.coins)
                w.coins = 40; w.limit = 7; see(w.coins + w.limit)
                see(select(2, pcall(function() w.coins = -1 end)))
                see(select(2, pcall(function() w.owner = 'x' end)))
                see(select(2, pcall(w.add, w, 'five')))
                see(w.size)
                see(bank.describe(w, true, 'extra'))
                see(select(2, pcall(bank.describe, w)))
                see(bank.load('a'))""");
        assertEquals(List.of("ana", 5L, 6L, 6L, 47L), seen.subList(0, 5));
        assertTrue(seen.get(5).toString().contains("coins can't be negative"), seen.get(5).toString());
        assertTrue(seen.get(6).toString().contains("no property 'owner' to set"), seen.get(6).toString());
        assertTrue(seen.get(7).toString().contains("bad argument #1 (number expected, got string)"), seen.get(7).toString());
        assertNull(seen.get(8), "only what is annotated is reachable");
        assertEquals("ana! 3 r", seen.get(9));
        assertTrue(seen.get(10).toString().contains("bad argument #2 (boolean expected, got nil)"), seen.get(10).toString());
        assertEquals(11, seen.size());
        api.later.complete("loaded");
        assertEquals("loaded", seen.get(11));
    }

    @Test
    void aListenerTurnsAnotherModsCallbackIntoAnEvent() throws Exception {
        ResourceHost host = host(Side.CLIENT);
        Module m = host.modules().module("trains");
        Arrived arrived = m.listener("arrived", Arrived.class);
        Allow allow = m.listener("board", Allow.class);
        run(host, """
                mods.on('trains', 'arrived', function(who, unknown, at) see(who); see(unknown == nil); see(at) end)
                mods.on('trains', 'board', function(who) return who ~= 'bob' end)""");
        arrived.arrived("ana", Thread.currentThread(), 7);
        assertEquals(List.of("ana", true, 7L), seen);
        assertTrue(allow.allow("ana"));
        assertFalse(allow.allow("bob"));
        assertNotEquals(arrived.hashCode(), 0);
        assertTrue(arrived.toString().contains("trains.arrived"));
    }

    @Test
    void javaHandlesAndModObjectsCrossBothWays() throws Exception {
        ResourceHost host = host(Side.SERVER);
        host.setJavaAllow(List.of("java.util"));
        Api api = new Api();
        host.modules().type(Wallet.class, "wallet");
        host.modules().module("bank", api);
        host.modules().module("lists").function("size", a -> a.as(0, List.class).size());
        run(host, """
                local list = java.new('java.util.ArrayList')
                list:add(mods.get('bank').open())     -- a mod's object into Java
                see(mods.get('lists').size(list))      -- a java.* object into a module
                see(tostring(list:get(0)) ~= nil)""");
        assertEquals(List.of(1L, true), seen);
    }

    @Test
    void definitionsDescribeWhatModsAdded() {
        ResourceHost host = host(Side.SERVER);
        host.modules().type(Wallet.class, "wallet").property("tier", "integer", w -> 1L);
        host.modules().module("bank", new Api())
                .function("pay", "(to: Player, amount: integer, note?: string) -> boolean", a -> true)
                .event("paid", "(player: Player, amount: integer)");
        host.modules().method("player", "wallet", "(currency?: string) -> wallet", a -> null);
        String stubs = host.modules().stubs();
        for (String expected : List.of("---@meta", "---@class wallet", "---@field coins integer", "---@field owner string", "---@field tier integer",
                "function wallet_:add(", "---@return wallet\nfunction mod_bank.open() end", "---@class mods.bank",
                "---@param to Player\n---@param amount integer\n---@param note? string\n---@return boolean\nfunction mod_bank.pay(to, amount, note) end",
                "---@overload fun(name: \"bank\"): mods.bank", "function mod_probe.see(...) end",
                "---@overload fun(name: \"bank\", event: \"paid\", handler: fun(player: Player, amount: integer))",
                "---@class Player\nlocal Player_ = {}\n---@param currency? string\n---@return wallet\nfunction Player_:wallet(currency) end"))
            assertTrue(stubs.contains(expected), expected + " missing in:\n" + stubs);
        assertFalse(stubs.contains("json.encode"), "Troupe's own API is in /troupe/defs");
    }

    @Test
    void declaredSignaturesAreCheckedBeforeTheCall() throws Exception {
        ResourceHost host = host(Side.CLIENT);
        host.modules().type(Wallet.class, "wallet").method("spend", "(amount: integer)", (w, a) -> w.coins -= a.integer(0));
        Wallet wallet = new Wallet("ana");
        host.modules().module("bank")
                .function("open", "() -> wallet", a -> wallet)
                .function("pay", "(to: Player, amount: integer, note?: string, mode?: \"fast\"|\"slow\", where?: Pos) -> boolean", a -> true)
                .function("log", "(level: string|integer, ...: any)", a -> a.count())
                .function("use", "(w: wallet, tags?: string[])", a -> a.as(0, Wallet.class).owner);
        assertThrows(IllegalArgumentException.class, () -> host.modules().module("bank").function("bad", "amount: integer", a -> null));
        run(host, """
                local bank = mods.get('bank')
                local function err(...) return (select(2, pcall(...))) end
                see(bank.pay('some-id', 5)); see(bank.pay({ id = 'x', name = 'Ana' }, 5, 'gift', 'fast', { x = 1 }))
                see(err(bank.pay, 'id', 'five')); see(err(bank.pay, 'id', 1.5)); see(err(bank.pay, 'id'))
                see(err(bank.pay, 'id', 1, 2)); see(err(bank.pay, 'id', 1, nil, 'medium')); see(err(bank.pay, 'id', 1, nil, nil, nil, 'extra'))
                see(bank.log('info', 1, 2, 3)); see(bank.log(2)); see(err(bank.log, true))
                see(bank.use(bank.open(), { 'a' })); see(err(bank.use, { owner = 'fake' })); see(err(bank.use, bank.open(), 'a'))
                see(err(bank.open().spend, bank.open(), 'lots'))""");
        assertEquals(List.of(true, true), seen.subList(0, 2));
        assertTrue(seen.get(2).toString().endsWith("bank.pay: bad argument #2 'amount' (integer expected, got string)"), seen.get(2).toString());
        assertTrue(seen.get(3).toString().contains("(integer expected, got number)"), seen.get(3).toString());
        assertTrue(seen.get(4).toString().contains("(integer expected, got nil)"), seen.get(4).toString());
        assertTrue(seen.get(5).toString().contains("#3 'note' (string expected, got integer)"), seen.get(5).toString());
        assertTrue(seen.get(6).toString().contains("#4 'mode'"), seen.get(6).toString());
        assertTrue(seen.get(7).toString().contains("too many arguments (5 expected, got 6)"), seen.get(7).toString());
        assertEquals(List.of(4L, 1L), seen.subList(8, 10));
        assertTrue(seen.get(10).toString().contains("#1 'level' (string|integer expected, got boolean)"), seen.get(10).toString());
        assertEquals("ana", seen.get(11));
        assertTrue(seen.get(12).toString().contains("#1 'w' (wallet expected, got table)"), seen.get(12).toString());
        assertTrue(seen.get(13).toString().contains("#2 'tags' (string[] expected, got string)"), seen.get(13).toString());
        assertTrue(seen.get(14).toString().contains("wallet:spend: bad argument #1 'amount'"), seen.get(14).toString());
    }

    @Test
    void resourcesTypeTheirOwnExportsAndHandlers() throws Exception {
        ResourceHost host = host(Side.SERVER);
        run(host, """
                net.handle('deposit', { amount = 'integer', note = 'string?', tags = { 'string' } }, function(player, args) end)
                local function err(...) return (select(2, pcall(...))) end
                local withdraw = typed('(amount: integer, to?: Player) -> boolean', function(amount, to) return amount > 0 end)
                see(withdraw(5)); see(err(withdraw, 'five')); see(err(typed, 'amount', print))
                return { withdraw = withdraw, balance = function(player, currency) return 0 end, rate = 2, ['not-a-name'] = true }""");
        assertEquals(true, seen.get(0));
        assertTrue(seen.get(1).toString().endsWith("bad argument #1 'amount' (integer expected, got string)"), seen.get(1).toString());
        assertTrue(seen.get(2).toString().contains("bad signature"), seen.get(2).toString());
        String stubs = host.resourceStubs();
        for (String expected : List.of("---@class resource.r\n---@field rate number\nlocal res_r = {}",
                "---@param player? any\n---@param currency? any\nfunction res_r.balance(player, currency) end",
                "---@param amount integer\n---@param to? Player\n---@return boolean\nfunction res_r.withdraw(amount, to) end",
                "---@overload fun(name: \"r\"): resource.r",
                "---@overload fun(name: \"deposit\", args: { amount: integer, note?: string, tags: string[] })\nfunction net.emit(...) end",
                "function net.call(...) end"))
            assertTrue(stubs.contains(expected), expected + " missing in:\n" + stubs);
    }
}
