# Changelog

## Unreleased

- `hud.native(false, keep?)` hides the game's HUD and other mods' HUD layers (keeping the ones listed), for servers that draw their whole interface with Troupe and Vitrine. NeoForge only for now.
- Key releases: `keys.bind(key, fn, release)` and `keys.register(id, opts, fn, release)` also run a function when the key is let go, so a script can show something only while a key is held. Also `keys.on_release` and `keys.on_action_release`.
- `keys.block(name)` turns off one of the game's or a mod's key mappings (the inventory, swapping hands, a mod's action) while the resource runs.
- `player.skip_empty_slots(true)`: the hotbar selection skips empty slots, so the hand is never empty while there's something to hold.

## 0.2.0

- NeoForge 1.21.1 (alpha): a separate jar, `troupe-0.2.0+1.21.1-neoforge.jar`, for NeoForge 21.1 (any build). Same resources, Lua API and protocol as the Fabric jars. On NeoForge, permission nodes fall back to the op level, and mods register their Troupe modules as a `META-INF/services` entry.
- Needs Vitrine 0.3.0 for pages on NeoForge (the first Vitrine with a NeoForge jar); on Fabric, Vitrine 0.2.1 and up still work.
- Blockbench models: put a `.bbmodel` in a resource's `models/` and dress any entity or player with it (`entity:set_model('npc', { idle, walk })`, `entity:animate('wave')`), or place one in the world on a client (`draw.model`). Textures and animations come from the project file; nothing to export. Cubes (per-face or box UV) and meshes; linear, catmull-rom, bezier and step keyframes, with simple Molang. Animations follow the game clock, so every player sees the same frame. Dressed entities keep their name tag and a shadow.

## 0.1.0

First release, for Minecraft 1.20–1.20.1, 1.21–1.21.1, 1.21.11 and 26.3.

- Resources live on the server (`resources/<name>/` with `server/`, `client/`, `shared/`, `ui/`, `assets/`). Clients download the client side on join, into an encrypted cache, and run it.
- Lua on both sides, one sandboxed state per resource: `net` (validated handlers, calls that wait), replicated `state`, `timer`, exports between resources, automatic cleanup on stop, restart with players connected.
- Game API: players, inventory, entities, world, chat, commands, bossbars, sidebar, teams; HUD, world shapes, camera and post effects on the client.
- UI pages through [Vitrine](https://github.com/holowebmc/vitrine) (`ui.screen`, `ui.world`, slots drawn by the game).
- Server side: `store`, a SQL database (`db`, SQLite built in, other JDBC drivers accepted), `http`, `json`, permission nodes.
- Zones (`zone.box`, `zone.sphere`), interaction targets with an on-screen prompt (`interact.add`), state on entities seen only by who is near (`entity.state`, `player.public`), instances that hide players and entities from each other, items defined by resources (`items.define`), keys players rebind in Controls (`keys.register`), camera paths (`render.camera_path`).
- Singleplayer worlds can bring their own `resources/` folder: maps with scripts and interfaces, after the player allows them.
- Mods extend the Lua side through `dev.holoweb.troupe.api`: modules with functions and events, their own object types (`Modules.type`), converters for their classes (`Modules.convert`), methods on player and entity handles, functions that answer later (`Module.async`), all of it also through annotations (`@LuaFunction`, `@LuaProperty`), and listeners that turn another mod's callback into a script event. Signatures can be declared (`"(amount: integer, note?: string) -> boolean"`) and are checked on every call; `typed(sig, fn)` does the same in Lua.
- Resources lend each other Lua code (`require('base:shared.util')`), state a `version`, and depend on a minimum version or optionally (`"base >= 1.2"`, `"extras?"`). `bit32` and the `math` functions 5.3 libraries look for are there.
- Editor support: `/troupe stubs` writes Lua Language Server definitions of the whole API, of what mods added and of what resources export. Resources ask for module versions (`"bank >= 1.2"`, `"map?"` when optional); on the client, scripts only reach the mod modules the server announced, and the player sees them before joining.
- Limits per resource on CPU time and memory; a resource that goes over is stopped.
- Players are asked before a server's scripts run for the first time; servers show a Troupe badge in the server list.
- Optional HTTP download of the client files (`download` in `config/troupe.json`).
