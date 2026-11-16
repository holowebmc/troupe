Troupe lets a Minecraft server run its own scripts and interfaces on its players' clients.

A server keeps **resources**: folders with Lua code for the server, Lua code for the client, HTML pages and assets. Players download the client side when they join and run it in a sandbox. Each server brings its own gameplay, menus and HUD, and players don't need a modpack made for it.

> Troupe is in alpha. The scripting API can still change between versions.

## What a resource can do

- **Lua on both sides.** Client and server call each other and wait for the answer, and the arguments are checked before the handler runs. State set on the server is replicated to the clients that should see it.
- **Interfaces in HTML** through [Vitrine](https://modrinth.com/mod/holoweb-vitrine): on screen, or on panels placed in the world.
- **Game API.** Players, inventories, entities, world, chat, commands, bossbars, sidebar and teams on the server. HUD, shapes in the world, camera paths and post effects on the client.
- **Building blocks for gameplay.** Zones, interaction prompts, custom items, instances that hide players from each other, and keys that players rebind in Controls.
- **Blockbench models.** Drop a `.bbmodel` in a resource and dress any mob or player with it, animations included, or place it in the world. No resource pack, no client mod.
- **Storage and web.** On the server: a SQL database (SQLite built in, other JDBC drivers accepted), a key-value store and HTTP requests.
- **Restart without kicking anyone.** `/troupe restart bank` reloads one resource while players stay connected. Everything it registered is released when it stops.
- **Assets.** Textures, models and sounds in a resource's `assets/` folder reach the client as a resource pack.

A singleplayer world can carry a `resources/` folder too, so a map can come with its own scripts and interfaces.

## For players

- Install Troupe and [Fabric API](https://modrinth.com/mod/fabric-api). Add [Vitrine](https://modrinth.com/mod/holoweb-vitrine) to see the servers' interfaces (Windows x64 for now). Everything else works without it.
- The first time you join a server that runs scripts, Troupe asks before downloading anything. Servers with Troupe show a badge in the server list, with what they run and the size of the download.
- Scripts can draw on your screen and in the world, move the camera and open pages. They can't read or change your files, run Java, or reach the network. A script that uses too much time or memory is stopped.

### What Troupe connects to

Troupe talks to the server you joined. Two things can reach other addresses, and both are chosen by that server:

- Its files may come from a web address it names. That is a plain HTTP download, checked against the hashes the server sent.
- Its interface pages are web pages, which can load content from the internet like any site. This only happens with Vitrine installed.

## For server owners

Put Troupe and Fabric API in `mods/`, and your resources in `resources/`:

```
resources/bank/
  resource.json
  server/main.lua    # never leaves the server
  client/main.lua
  shared/config.lua  # loaded on both sides
  ui/index.html
  assets/
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

- `/troupe list|start|stop|restart|ensure <resource>` manage resources while the server runs.
- `/troupe stubs` writes definitions for the [Lua Language Server](https://luals.github.io), so your editor completes and checks the whole API.
- Each resource has limits on CPU time and memory.
- Players need Troupe to join a server that runs it.

## For mod developers

Other mods can expose their own API to scripts through `dev.holoweb.troupe.api`: modules with functions and events, object types with methods and properties, and extra methods on player and entity handles. The [README](https://github.com/holowebmc/troupe#readme) has the details.
