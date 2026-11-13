# Troupe

Troupe is a Fabric mod that lets a Minecraft server run its own scripts and interfaces on its players' clients. Resources live on the server; each one has server code, client code, shared code, UI pages and assets. Clients download the client side when they join and run it in a sandbox.

- **One folder per resource.** `server/` never leaves the server; `client/`, `shared/`, `ui/` and `assets/` go to the players.
- **Lua on both sides,** with calls between client and server that wait for the answer, replicated state, timers and exports between resources.
- **Interfaces in HTML** through [Vitrine](https://github.com/holowebmc/vitrine): on screen or on a panel in the world.
- **Restart without kicking anyone.** Everything a resource registers is released when it stops; connected clients follow.

## For players

- Install Troupe and Fabric API. Install [Vitrine](https://github.com/holowebmc/vitrine) too to see the servers' interfaces (Windows x64 for now); without it everything else works.
- The first time you join a server that runs scripts, Troupe asks before downloading anything. Servers with Troupe show a badge in the server list.
- Scripts can draw on your screen and in the world, move the camera and open pages. They can't read or change your files, run Java, or reach the network. A script that uses too much time or memory is stopped.
- What Troupe connects to: the server you joined. Two things can reach other addresses, both chosen by that server: its files may come from a web address it names (a plain HTTP download, checked against the hashes the server sent), and its interface pages are web pages, which can load content from the internet like any site (only with Vitrine installed).
- Minecraft 1.20–1.20.1, 1.21–1.21.1, 1.21.11 and 26.3, one jar for each.

## For server owners

Put Troupe and Fabric API in `mods/` and resources in `resources/`:

```
resources/bank/
  resource.json      # { "troupe": ">=0.1.0", "dependencies": ["base"] }
  server/main.lua
  client/main.lua
  shared/config.lua  # require('shared.config') on both sides
  ui/index.html
  assets/            # textures, models, sounds: a resource pack on the client
```

```lua
-- server/main.lua
net.handle('withdraw', { amount = 'number' }, function(player, args)
  local rows = db.query('SELECT balance FROM accounts WHERE id = ?', { player.id })
  if #rows == 0 or rows[1].balance < args.amount then error('not enough money', 0) end
  db.execute('UPDATE accounts SET balance = balance - ? WHERE id = ?', { args.amount, player.id })
  player.state.balance = rows[1].balance - args.amount   -- replicated to that player
  return player.state.balance
end)
```

```lua
-- client/main.lua
local page = ui.screen('index.html')
keys.bind('b', function() page:show(); page:focus(true) end)
page:handle('withdraw', function(args) return net.call('withdraw', args) end)
state.watch('balance', function(v) page:send('balance', v) end)
```

`/troupe list|start|stop|restart|ensure <resource>` manage resources while the server runs. `config/troupe.json` holds the server's settings: the database (SQLite in `troupe/data.db` by default; any JDBC URL with its driver's jar in `mods/`), an optional HTTP download for the client files, and `java.*` access for server scripts.

The same with annotations: `modules.module("bank", new BankApi())` exposes every `@LuaFunction` method of the object, and `modules.type(Account.class, "account")` reads `@LuaFunction` and `@LuaProperty` from the class. `module.listener("join", SomeCallback.class)` gives an implementation of another mod's callback interface that fires a script event. Functions and events can declare their signature (`module.function("pay", "(to: Player, amount: integer) -> boolean", fn)`): calls that don't fit fail with the argument's name, and editors show it.

Resources share Lua code too: `require('base:shared.util')` loads `shared/util.lua` of a resource listed under `dependencies` (`"base >= 1.2"` asks for a version, `"extras?"` is optional; a resource states its own with `"version"`). The language is Lua 5.2 with `utf8`, `string.pack`, `table.move`, `goto` and `bit32`; there are no 5.3 integer operators (`//`, `&`, `<<`).

### Editor support

`/troupe stubs` writes definitions for the [Lua Language Server](https://luals.github.io) into `troupe/stubs/` and points `resources/.luarc.json` at them: the whole Troupe API with parameters and return types, what the installed mods added, and what the running resources offer each other (`resource('bank').withdraw`, the arguments of each net event). In Lua, `typed('(amount: integer) -> boolean', fn)` checks a function's arguments on every call and types it for the other resources.

A resource lists the mod modules it uses in `resource.json` (`"mods": { "client": ["bank >= 1.2", "map?"] }`): a minimum version, or `?` for one it can do without. On the client a script reaches only the modules its server announced, and the player sees which of their mods those are before joining.

Other mods expose their own API to scripts by registering a module (`dev.holoweb.troupe.api`, entrypoint `troupe`); scripts call it with `mods.call` or `mods.get`. A mod can also give scripts its own object types (methods and properties), teach Troupe how its classes convert, add methods to player and entity handles, and register functions the script waits on:

```java
public class BankTroupe implements TroupeModule {
    public void register(Modules modules) {
        modules.type(Account.class, "account")
                .property("balance", Account::balance)
                .method("deposit", (account, args) -> account.deposit(args.integer(0)));
        modules.module("bank")
                .function("of", args -> Bank.of(args.as(0, ServerPlayer.class)))   // account:deposit(5) in Lua
                .async("load", args -> Bank.loadAsync(args.string(0)));            // a CompletionStage
        modules.method("player", "account", args -> Bank.of(args.as(0, ServerPlayer.class)));
    }
}
```

## Building and verifying

`./gradlew build` tests and builds a jar per Minecraft version. It needs Vitrine's jars: from Maven local (`./gradlew publishToMavenLocal` in Vitrine's repository) or in `libs/`, taken from Vitrine's release.

[`release.yml`](.github/workflows/release.yml) builds every jar from a tagged commit and uploads them to Modrinth and to the [GitHub release](https://github.com/holowebmc/troupe/releases). To check a download, compare its SHA-512 with `SHA512SUMS` in the release, or run `gh attestation verify troupe-<version>.jar --repo holowebmc/troupe`.

## License

Apache License 2.0. The jar includes [Cobalt](https://github.com/cc-tweaked/Cobalt) (MIT), [fabric-permissions-api](https://github.com/lucko/fabric-permissions-api) (MIT) and [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) (Apache 2.0).
