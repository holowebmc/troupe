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

Other mods expose their own API to scripts by registering a module (`dev.holoweb.troupe.api`, entrypoint `troupe`); scripts call it with `mods.call`.

## Building and verifying

`./gradlew build` tests and builds a jar per Minecraft version. It needs Vitrine's jars: from Maven local (`./gradlew publishToMavenLocal` in Vitrine's repository) or in `libs/`, taken from Vitrine's release.

[`release.yml`](.github/workflows/release.yml) builds every jar from a tagged commit and uploads them to Modrinth and to the [GitHub release](https://github.com/holowebmc/troupe/releases). To check a download, compare its SHA-512 with `SHA512SUMS` in the release, or run `gh attestation verify troupe-<version>.jar --repo holowebmc/troupe`.

## License

Apache License 2.0. The jar includes [Cobalt](https://github.com/cc-tweaked/Cobalt) (MIT), [fabric-permissions-api](https://github.com/lucko/fabric-permissions-api) (MIT) and [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) (Apache 2.0).
