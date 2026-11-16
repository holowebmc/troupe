---@meta
-- Troupe: server-side Lua API (what a resource's server scripts can call).
-- Players are passed to module functions as their id (player.id); handles have the same calls as methods.
-- Positions are loaded chunks only: nothing here loads or generates terrain. Durations are seconds.

-- ---------------------------------------------------------------------------------------------------------------------
-- Shapes

---A position by name ({ x = 1, y = 2, z = 3 }, what player:pos() returns) or in order ({ 1, 2, 3 }).
---@alias PosLike Pos|number[]

---@class EntityPos : Pos
---@field dim string  dimension id, e.g. 'minecraft:overworld'

---@class PlayerPos : EntityPos
---@field yaw number
---@field pitch number

---@class BlockRef
---@field x integer
---@field y integer
---@field z integer
---@field dim string
---@field id string  block id, e.g. 'minecraft:stone'

---@class BlockLook
---@field x integer
---@field y integer
---@field z integer
---@field id string  block id
---@field face string  the face being looked at: 'up', 'down', 'north', 'south', 'east', 'west'

---@class BlockState
---@field id string  block id
---@field props table<string, string>  property values by name, as text

---An item as scripts read it.
---@class Item
---@field id string  item id; the defined id for items made with items.define
---@field base? string  the game item behind a defined item
---@field count integer
---@field name string  displayed name
---@field slot? integer  0-based slot, in inventory and container listings
---@field lore? string[]
---@field enchantments? table<string, integer>  level by enchantment id
---@field damage? integer  durability used, for items that have durability
---@field max_damage? integer
---@field model? integer  custom model data

---An item as scripts write it.
---@class ItemSpec
---@field id string  item id (the game's or one from items.define)
---@field count? integer  wins over the count argument
---@field name? string
---@field lore? string[]
---@field enchantments? table<string, integer>  level (1..255) by enchantment id
---@field damage? integer  durability used
---@field model? integer  custom model data
---@field item_model? string  item model id (1.21.2+)
---@field glint? boolean  enchantment glint on or off (1.20.5+)

---An item id or an item table.
---@alias ItemLike string|ItemSpec

---@class ItemDefinition
---@field base string  the game item it is made of, e.g. 'minecraft:paper'
---@field name? string
---@field lore? string[]
---@field enchantments? table<string, integer>
---@field damage? integer
---@field model? integer  custom model data
---@field item_model? string  item model id (1.21.2+)
---@field glint? boolean

---@class Effect
---@field id string  effect id, e.g. 'minecraft:speed'
---@field seconds number  time left
---@field amplifier integer  0 is level I

---@class EffectOptions
---@field particles? boolean  default true

---@class Xp
---@field level integer
---@field progress number  0..1 towards the next level

---@class TitleOptions
---@field fade_in? number  seconds, default 0.5
---@field stay? number  seconds, default 3.5
---@field fade_out? number  seconds, default 1

---@class SoundOptions
---@field volume? number  0..16, default 1
---@field pitch? number  0..2, default 1

---@class WorldSoundOptions : SoundOptions
---@field dim? string  default the overworld

---@class ParticleOptions
---@field count? integer  1..1000, default 1
---@field spread? number  0..64, default 0
---@field speed? number  default 0
---@field dim? string  default the overworld

---@class NearOptions
---@field type? string  only entities of this type id

---@class DamageCause
---@field type string  the game's damage type name
---@field attacker? Player|Entity

---@alias EquipmentSlot 'head'|'chest'|'legs'|'feet'|'mainhand'|'offhand'
---@alias Weather 'clear'|'rain'|'thunder'
---@alias GameMode 'survival'|'creative'|'adventure'|'spectator'

---@class CommandOptions
---@field admin? boolean  operators only
---@field permission? string  permission node needed, e.g. 'bank.withdraw'

---@class ModelOptions
---@field scale? number  0..16 (default 1)
---@field idle? string  animation that plays while it stands
---@field walk? string  animation that plays while it moves

---@class BossbarOptions
---@field text? string
---@field color? 'pink'|'blue'|'red'|'green'|'yellow'|'purple'|'white'
---@field style? 'progress'|'notched_6'|'notched_10'|'notched_12'|'notched_20'
---@field progress? number  0..1

---@class TeamOptions
---@field prefix? string
---@field suffix? string
---@field color? string  chat color name: 'red', 'dark_aqua'...
---@field friendly_fire? boolean
---@field nametag? 'always'|'never'|'hide_for_other_teams'|'hide_for_own_team'

---@class InteractSpec
---@field block? PosLike  a block
---@field entity? Entity|string  an entity (handle or id)
---@field pos? PosLike  a point in the world
---@field radius? number  size of a pos target, up to 4 (default 0.75)

---@class InteractOptions
---@field label string  text shown to who looks at it, 1..64 characters
---@field distance? number  1..16, default 4
---@field dim? string  default the overworld

---@class HttpOptions
---@field method? string  default 'GET'
---@field headers? table<string, string>
---@field body? string
---@field timeout? number  seconds, up to 60 (default 10)

---@class HttpPostOptions
---@field headers? table<string, string>
---@field timeout? number  seconds, up to 60 (default 10)

---@class HttpResponse
---@field status integer
---@field body string  text (UTF-8), up to 4 MiB
---@field headers table<string, string>  names in lowercase

---@class DbResult
---@field changes integer  rows changed
---@field id? any  the generated key, when the statement made one

---One statement of db.batch: the SQL, or { sql, { params } }.
---@alias DbStatement string|any[]

-- ---------------------------------------------------------------------------------------------------------------------
-- Player handles

---@class Player
---@field state table  replicated to that player only (writable)
---@field public public table  replicated to the players who see them (writable)
local Player = {}

---Sends a chat message to the player.
---@param text string
function Player:message(text) end

---Where the player is.
---@return PlayerPos
function Player:pos() end

---Teleports the player, keeping where they look.
---@param x number
---@param y number
---@param z number
---@param dim? string  default the dimension they are in
---@return boolean
function Player:teleport(x, y, z, dim) end

---Gives an item; false when the inventory had no room for all of it.
---@param item ItemLike
---@param count? integer
---@return boolean
function Player:give(item, count) end

---Whether the player is still online.
---@return boolean
function Player:online() end

---The mod modules on the player's client.
---@return string[]
function Player:mods() end

---Whether the player's client has that mod module.
---@param name string
---@return boolean
function Player:has_mod(name) end

---The version of a mod module on the player's client, nil when it lacks it or it has none.
---@param name string
---@return string?
function Player:mod_version(name) end

---What a permissions mod says about the node; operators when none decides.
---@param node string
---@return boolean
function Player:has_permission(node) end

---@return number
function Player:health() end

---@return number
function Player:max_health() end

---Sets the health, clamped to 0..max.
---@param health number
function Player:set_health(health) end

---The food level, 0..20.
---@return integer
function Player:food() end

---Sets the food level, clamped to 0..20.
---@param food integer
function Player:set_food(food) end

---@return GameMode
function Player:gamemode() end

---Changes the game mode; false when nothing changed.
---@param mode GameMode
---@return boolean
function Player:set_gamemode(mode) end

---@return Xp
function Player:xp() end

---Gives experience points.
---@param points integer
function Player:give_xp(points) end

---Sets the experience level.
---@param level integer
function Player:set_level(level) end

---Applies an effect; false when it was not applied.
---@param id string  effect id, e.g. 'minecraft:speed'
---@param seconds number
---@param amplifier? integer  0..255, default 0
---@param opts? EffectOptions
---@return boolean
function Player:effect(id, seconds, amplifier, opts) end

---The effects the player has now.
---@return Effect[]
function Player:effects() end

---Removes one effect, or all of them without an id.
---@param id? string
function Player:clear_effects(id) end

---Shows a title in the middle of the screen.
---@param text string
---@param subtitle? string
---@param opts? TitleOptions
function Player:title(text, subtitle, opts) end

---Shows text above the hotbar.
---@param text string
function Player:actionbar(text) end

---Plays a sound for this player only, where they are.
---@param id string  sound id (the game's or one from a resource's assets)
---@param opts? SoundOptions
function Player:sound(id, opts) end

---Disconnects the player.
---@param reason? string
function Player:kick(reason) end

---Sets the header and footer of the player's tab list.
---@param header? string
---@param footer? string
function Player:tab(header, footer) end

---The block the player is looking at, nil when none is in reach.
---@param distance? number  up to 64, default 5
---@return BlockLook?
function Player:looking_at(distance) end

---Every item in the inventory, each with its slot.
---@return Item[]
function Player:items() end

---The item in a slot, nil when empty.
---@param slot integer  0-based
---@return Item?
function Player:item(slot) end

---Puts an item in a slot; nil empties it.
---@param slot integer  0-based
---@param item? ItemLike
---@param count? integer
function Player:set_item(slot, item, count) end

---How many of an item the player has.
---@param id string  item id
---@return integer
function Player:count(id) end

---Takes up to count of an item; returns how many were taken.
---@param id string  item id
---@param count? integer  default 1
---@return integer
function Player:take(id, count) end

---The item in the main hand, nil when empty.
---@return Item?
function Player:held() end

---The value of an attribute, nil when the player has none of it.
---@param id string  attribute id
---@return number?
function Player:attribute(id) end

---Sets the base value of an attribute.
---@param id string  attribute id
---@param value number
function Player:set_attribute(id, value) end

---Sets the player's motion, in blocks per tick.
---@param x number
---@param y number
---@param z number
function Player:set_velocity(x, y, z) end

---Turns the glowing outline on or off.
---@param on? boolean  default true
function Player:glow(on) end

---Puts the player on a vehicle; false when the game refused.
---@param vehicle Player|Entity|string  handle or entity id
---@return boolean
function Player:mount(vehicle) end

---Takes the player off what they ride.
function Player:dismount() end

---What the player rides, if anything.
---@return (Player|Entity)?
function Player:vehicle() end

---Who rides the player.
---@return (Player|Entity)[]
function Player:passengers() end

---Moves the player to an instance of this resource (only who is in the same one sees them); nil leaves it.
---@param name? string  1..64 characters
function Player:set_instance(name) end

---The instance of this resource the player is in, if any.
---@return string?
function Player:instance() end

---Draws the player as a Blockbench model from models/<name>.bbmodel ('name', or 'resource:name' for another resource's), for everyone who sees it; nil goes back to the normal look. The hitbox stays the same.
---@param name? string
---@param opts? ModelOptions
function Player:set_model(name, opts) end

---Plays an animation of the model over idle/walk until it ends (a looping one plays until the next call); nil stops it.
---@param name? string
function Player:animate(name) end

-- ---------------------------------------------------------------------------------------------------------------------
-- Entity handles

---@class Entity
---@field state table  replicated to the players who see the entity (writable)
local Entity = {}

---@return EntityPos
function Entity:pos() end

---Teleports the entity to a loaded chunk.
---@param x number
---@param y number
---@param z number
---@param dim? string  default the dimension it is in
---@return boolean
function Entity:teleport(x, y, z, dim) end

---Removes the entity from the world.
function Entity:remove() end

---Whether the entity is loaded and alive.
---@return boolean
function Entity:alive() end

---The displayed name.
---@return string
function Entity:name() end

---Sets a custom name, always visible; nil removes it.
---@param name? string
function Entity:set_name(name) end

---The health, nil for what isn't a living entity.
---@return number?
function Entity:health() end

---Sets the health, clamped to 0..max.
---@param health number
function Entity:set_health(health) end

---Puts an item in an equipment slot; nil empties it.
---@param slot EquipmentSlot
---@param item? ItemLike
---@param count? integer
function Entity:equip(slot, item, count) end

---What the entity wears and holds, by slot (empty slots are absent).
---@return table<EquipmentSlot, Item>
function Entity:equipment() end

---The value of an attribute, nil when the entity has none of it.
---@param id string  attribute id
---@return number?
function Entity:attribute(id) end

---Sets the base value of an attribute.
---@param id string  attribute id
---@param value number
function Entity:set_attribute(id, value) end

---Applies an effect; false when it was not applied.
---@param id string  effect id
---@param seconds number
---@param amplifier? integer  0..255, default 0
---@return boolean
function Entity:effect(id, seconds, amplifier) end

---Removes one effect, or all of them without an id.
---@param id? string
function Entity:clear_effects(id) end

---Sets the entity's motion, in blocks per tick.
---@param x number
---@param y number
---@param z number
function Entity:set_velocity(x, y, z) end

---Turns the glowing outline on or off.
---@param on? boolean  default true
function Entity:glow(on) end

---Puts the entity on a vehicle; false when the game refused.
---@param vehicle Player|Entity|string  handle or entity id
---@return boolean
function Entity:mount(vehicle) end

---Takes the entity off what it rides.
function Entity:dismount() end

---What the entity rides, if anything.
---@return (Player|Entity)?
function Entity:vehicle() end

---Who rides the entity.
---@return (Player|Entity)[]
function Entity:passengers() end

---Makes a mob walk to a place; false when it found no path.
---@param x number
---@param y number
---@param z number
---@param speed? number  up to 4, default 1
---@return boolean
function Entity:move_to(x, y, z, speed) end

---Turns a mob's AI on or off.
---@param on boolean
function Entity:set_ai(on) end

---Moves the entity to an instance of this resource; nil leaves it.
---@param name? string  1..64 characters
function Entity:set_instance(name) end

---The instance of this resource the entity is in, if any.
---@return string?
function Entity:instance() end

---Draws the entity as a Blockbench model from models/<name>.bbmodel ('name', or 'resource:name' for another resource's), for everyone who sees it; nil goes back to the normal look. The hitbox stays the same.
---@param name? string
---@param opts? ModelOptions
function Entity:set_model(name, opts) end

---Plays an animation of the model over idle/walk until it ends (a looping one plays until the next call); nil stops it.
---@param name? string
function Entity:animate(name) end

-- ---------------------------------------------------------------------------------------------------------------------
-- players

players = {}

---Everyone online.
---@return Player[]
function players.list() end

---The online player with that id or name.
---@param idOrName string
---@return Player?
function players.get(idOrName) end

---Whether a player with that id or name is online.
---@param idOrName string
---@return boolean
function players.online(idOrName) end

---The mod modules on the player's client.
---@param player string
---@return string[]
function players.mods(player) end

---The version of a mod module on the player's client, nil when it lacks it or it has none.
---@param player string
---@param name string
---@return string?
function players.mod_version(player, name) end

---Sends a chat message to the player.
---@param player string
---@param text string
function players.message(player, text) end

---Where the player is.
---@param player string
---@return PlayerPos
function players.pos(player) end

---Teleports the player, keeping where they look.
---@param player string
---@param x number
---@param y number
---@param z number
---@param dim? string  default the dimension they are in
---@return boolean
function players.teleport(player, x, y, z, dim) end

---What a permissions mod says about the node; operators when none decides.
---@param player string
---@param node string
---@return boolean
function players.has_permission(player, node) end

---Gives an item; false when the inventory had no room for all of it.
---@param player string
---@param item ItemLike
---@param count? integer
---@return boolean
function players.give(player, item, count) end

---@param player string
---@return number
function players.health(player) end

---@param player string
---@return number
function players.max_health(player) end

---Sets the health, clamped to 0..max.
---@param player string
---@param health number
function players.set_health(player, health) end

---The food level, 0..20.
---@param player string
---@return integer
function players.food(player) end

---Sets the food level, clamped to 0..20.
---@param player string
---@param food integer
function players.set_food(player, food) end

---@param player string
---@return GameMode
function players.gamemode(player) end

---Changes the game mode; false when nothing changed.
---@param player string
---@param mode GameMode
---@return boolean
function players.set_gamemode(player, mode) end

---@param player string
---@return Xp
function players.xp(player) end

---Gives experience points.
---@param player string
---@param points integer
function players.give_xp(player, points) end

---Sets the experience level.
---@param player string
---@param level integer
function players.set_level(player, level) end

---Applies an effect; false when it was not applied.
---@param player string
---@param id string  effect id, e.g. 'minecraft:speed'
---@param seconds number
---@param amplifier? integer  0..255, default 0
---@param opts? EffectOptions
---@return boolean
function players.effect(player, id, seconds, amplifier, opts) end

---The effects the player has now.
---@param player string
---@return Effect[]
function players.effects(player) end

---Removes one effect, or all of them without an id.
---@param player string
---@param id? string
function players.clear_effects(player, id) end

---Shows a title in the middle of the screen.
---@param player string
---@param text string
---@param subtitle? string
---@param opts? TitleOptions
function players.title(player, text, subtitle, opts) end

---Shows text above the hotbar.
---@param player string
---@param text string
function players.actionbar(player, text) end

---Plays a sound for this player only, where they are.
---@param player string
---@param id string  sound id
---@param opts? SoundOptions
function players.sound(player, id, opts) end

---Disconnects the player.
---@param player string
---@param reason? string
function players.kick(player, reason) end

---Sets the header and footer of the player's tab list.
---@param player string
---@param header? string
---@param footer? string
function players.tab(player, header, footer) end

---The block the player is looking at, nil when none is in reach.
---@param player string
---@param distance? number  up to 64, default 5
---@return BlockLook?
function players.looking_at(player, distance) end

---Every item in the inventory, each with its slot.
---@param player string
---@return Item[]
function players.items(player) end

---The item in a slot, nil when empty.
---@param player string
---@param slot integer  0-based
---@return Item?
function players.item(player, slot) end

---Puts an item in a slot; nil empties it.
---@param player string
---@param slot integer  0-based
---@param item? ItemLike
---@param count? integer
function players.set_item(player, slot, item, count) end

---How many of an item the player has.
---@param player string
---@param id string  item id
---@return integer
function players.count(player, id) end

---Takes up to count of an item; returns how many were taken.
---@param player string
---@param id string  item id
---@param count? integer  default 1
---@return integer
function players.take(player, id, count) end

---The item in the main hand, nil when empty.
---@param player string
---@return Item?
function players.held(player) end

---The value of an attribute, nil when the player has none of it.
---@param player string
---@param id string  attribute id
---@return number?
function players.attribute(player, id) end

---Sets the base value of an attribute.
---@param player string
---@param id string  attribute id
---@param value number
function players.set_attribute(player, id, value) end

---Sets the player's motion, in blocks per tick.
---@param player string
---@param x number
---@param y number
---@param z number
function players.set_velocity(player, x, y, z) end

---Turns the glowing outline on or off.
---@param player string
---@param on? boolean  default true
function players.glow(player, on) end

---Puts the player on a vehicle; false when the game refused.
---@param player string
---@param vehicle string  entity id
---@return boolean
function players.mount(player, vehicle) end

---Takes the player off what they ride.
---@param player string
function players.dismount(player) end

---What the player rides, if anything.
---@param player string
---@return (Player|Entity)?
function players.vehicle(player) end

---Who rides the player.
---@param player string
---@return (Player|Entity)[]
function players.passengers(player) end

---Moves the player to an instance of this resource; nil leaves it.
---@param player string
---@param name? string  1..64 characters
function players.set_instance(player, name) end

---The instance of this resource the player is in, if any.
---@param player string
---@return string?
function players.instance(player) end

---Draws the player as a Blockbench model from models/<name>.bbmodel; nil goes back to the normal look.
---@param player string
---@param name? string
---@param opts? ModelOptions
function players.set_model(player, name, opts) end

---Plays an animation of the player's model over idle/walk; nil stops it.
---@param player string
---@param name? string
function players.animate(player, name) end

---A player joined; also runs for everyone already online when the resource starts.
---@param handler fun(player: Player)
function players.on_join(handler) end

---A player left the server.
---@param handler fun(player: Player)
function players.on_leave(handler) end

---A player is breaking a block; returning false cancels.
---@param handler fun(player: Player, block: BlockRef): boolean?
function players.on_break(handler) end

---A player is placing a block; returning false cancels.
---@param handler fun(player: Player, block: BlockRef): boolean?
function players.on_place(handler) end

---A player used (right-clicked) a block with the main hand; returning false cancels.
---@param handler fun(player: Player, block: BlockRef): boolean?
function players.on_use_block(handler) end

---A player used the item in the main hand (nil with an empty hand); returning false cancels.
---@param handler fun(player: Player, item: Item?): boolean?
function players.on_use_item(handler) end

---A player used (right-clicked) an entity or another player; returning false cancels.
---@param handler fun(player: Player, target: Player|Entity): boolean?
function players.on_use_entity(handler) end

---A player attacked an entity or another player; returning false cancels.
---@param handler fun(player: Player, target: Player|Entity): boolean?
function players.on_attack(handler) end

---A player is about to take damage; returning false cancels.
---@param handler fun(player: Player, amount: number, cause: DamageCause): boolean?
function players.on_damage(handler) end

---A player died.
---@param handler fun(player: Player, cause: DamageCause)
function players.on_death(handler) end

---A player respawned.
---@param handler fun(player: Player)
function players.on_respawn(handler) end

---A player changed dimension.
---@param handler fun(player: Player, from: string, to: string)
function players.on_dimension(handler) end

-- ---------------------------------------------------------------------------------------------------------------------
-- entities

entities = {}

---Spawns an entity in a loaded chunk; nil when the game refused.
---@param type string  entity type id, e.g. 'minecraft:villager'
---@param x number
---@param y number
---@param z number
---@param dim? string  default the overworld
---@return Entity?
function entities.spawn(type, x, y, z, dim) end

---The entities (not players) within radius of a point, up to 256.
---@param x number
---@param y number
---@param z number
---@param radius number  up to 128
---@param dim? string  default the overworld
---@param opts? NearOptions
---@return Entity[]
function entities.near(x, y, z, radius, dim, opts) end

---The loaded entity with that id.
---@param id string
---@return Entity?
function entities.get(id) end

---Whether the entity is loaded and alive.
---@param id string
---@return boolean
function entities.alive(id) end

---The entity's type id.
---@param id string
---@return string
function entities.type(id) end

---@param id string
---@return EntityPos
function entities.pos(id) end

---Teleports the entity to a loaded chunk.
---@param id string
---@param x number
---@param y number
---@param z number
---@param dim? string  default the dimension it is in
---@return boolean
function entities.teleport(id, x, y, z, dim) end

---Removes the entity from the world.
---@param id string
function entities.remove(id) end

---The displayed name.
---@param id string
---@return string
function entities.name(id) end

---Sets a custom name, always visible; nil removes it.
---@param id string
---@param name? string
function entities.set_name(id, name) end

---The health, nil for what isn't a living entity.
---@param id string
---@return number?
function entities.health(id) end

---Sets the health, clamped to 0..max.
---@param id string
---@param health number
function entities.set_health(id, health) end

---Puts an item in an equipment slot; nil empties it.
---@param id string
---@param slot EquipmentSlot
---@param item? ItemLike
---@param count? integer
function entities.equip(id, slot, item, count) end

---What the entity wears and holds, by slot (empty slots are absent).
---@param id string
---@return table<EquipmentSlot, Item>
function entities.equipment(id) end

---The value of an attribute, nil when the entity has none of it.
---@param id string
---@param attribute string  attribute id
---@return number?
function entities.attribute(id, attribute) end

---Sets the base value of an attribute.
---@param id string
---@param attribute string  attribute id
---@param value number
function entities.set_attribute(id, attribute, value) end

---Applies an effect; false when it was not applied.
---@param id string
---@param effect string  effect id
---@param seconds number
---@param amplifier? integer  0..255, default 0
---@return boolean
function entities.effect(id, effect, seconds, amplifier) end

---Removes one effect, or all of them without an effect id.
---@param id string
---@param effect? string
function entities.clear_effects(id, effect) end

---Sets the entity's motion, in blocks per tick.
---@param id string
---@param x number
---@param y number
---@param z number
function entities.set_velocity(id, x, y, z) end

---Turns the glowing outline on or off.
---@param id string
---@param on? boolean  default true
function entities.glow(id, on) end

---Puts the entity on a vehicle; false when the game refused.
---@param id string
---@param vehicle string  entity id
---@return boolean
function entities.mount(id, vehicle) end

---Takes the entity off what it rides.
---@param id string
function entities.dismount(id) end

---What the entity rides, if anything.
---@param id string
---@return (Player|Entity)?
function entities.vehicle(id) end

---Who rides the entity.
---@param id string
---@return (Player|Entity)[]
function entities.passengers(id) end

---Makes a mob walk to a place; false when it found no path.
---@param id string
---@param x number
---@param y number
---@param z number
---@param speed? number  up to 4, default 1
---@return boolean
function entities.move_to(id, x, y, z, speed) end

---Turns a mob's AI on or off.
---@param id string
---@param on boolean
function entities.set_ai(id, on) end

---Moves the entity to an instance of this resource; nil leaves it.
---@param id string
---@param name? string  1..64 characters
function entities.set_instance(id, name) end

---The instance of this resource the entity is in, if any.
---@param id string
---@return string?
function entities.instance(id) end

---Draws the entity as a Blockbench model from models/<name>.bbmodel; nil goes back to the normal look.
---@param id string
---@param name? string
---@param opts? ModelOptions
function entities.set_model(id, name, opts) end

---Plays an animation of the entity's model over idle/walk; nil stops it.
---@param id string
---@param name? string
function entities.animate(id, name) end

---A living entity (not a player) is about to take damage; returning false cancels.
---@param handler fun(entity: Entity, amount: number, cause: DamageCause): boolean?
function entities.on_damage(handler) end

---A living entity (not a player) died.
---@param handler fun(entity: Entity, cause: DamageCause)
function entities.on_death(handler) end

-- ---------------------------------------------------------------------------------------------------------------------
-- chat, command

chat = {}

---Sends a chat message to everyone.
---@param text string
function chat.broadcast(text) end

---A player sent a chat message.
---@param handler fun(player: Player, message: string)
function chat.on_message(handler) end

command = {}

---Adds /name; the handler gets who ran it (nil from the console) and the words after the name.
---@param name string  a-z, 0-9, '_' and '-', up to 32 characters
---@param handler fun(player: Player?, args: string[])
---@param opts? CommandOptions
function command.register(name, handler, opts) end

-- ---------------------------------------------------------------------------------------------------------------------
-- world

world = {}

---The id of the block there, nil when the chunk isn't loaded.
---@param x number
---@param y number
---@param z number
---@param dim? string  default the overworld
---@return string?
function world.block(x, y, z, dim) end

---The block there with its properties, nil when the chunk isn't loaded.
---@param x number
---@param y number
---@param z number
---@param dim? string  default the overworld
---@return BlockState?
function world.block_state(x, y, z, dim) end

---Sets a block; false when the chunk isn't loaded or nothing changed.
---@param x number
---@param y number
---@param z number
---@param id string  block id
---@param dim? string  default the overworld
---@param props? table<string, any>  block properties, e.g. { facing = 'north', lit = true }
---@return boolean
function world.set_block(x, y, z, id, dim, props) end

---Fills a loaded box (up to 32768 blocks) with a block; returns how many blocks changed.
---@param x1 number
---@param y1 number
---@param z1 number
---@param x2 number
---@param y2 number
---@param z2 number
---@param id string  block id
---@param dim? string  default the overworld
---@param props? table<string, any>  block properties
---@return integer
function world.fill(x1, y1, z1, x2, y2, z2, id, dim, props) end

---The items in the container (chest, barrel...) there, each with its slot; nil when there is none.
---@param x number
---@param y number
---@param z number
---@param dim? string  default the overworld
---@return Item[]?
function world.container(x, y, z, dim) end

---How many slots the container there has; nil when there is none.
---@param x number
---@param y number
---@param z number
---@param dim? string  default the overworld
---@return integer?
function world.container_size(x, y, z, dim) end

---Puts an item in a slot of the container there; nil empties the slot.
---@param x number
---@param y number
---@param z number
---@param slot integer  0-based
---@param item? ItemLike
---@param count? integer
---@param dim? string  default the overworld
function world.set_container_item(x, y, z, slot, item, count, dim) end

---The time of day in ticks (0 sunrise, 6000 noon, 12000 sunset, 18000 midnight).
---@param dim? string  default the overworld
---@return integer
function world.time(dim) end

---Sets the time of day, in ticks.
---@param ticks integer
---@param dim? string  default the overworld
function world.set_time(ticks, dim) end

---The weather now.
---@return Weather
function world.weather() end

---Changes the weather for some time.
---@param kind Weather
---@param seconds? number  default 300
function world.set_weather(kind, seconds) end

---Plays a sound at a place, for who is near.
---@param id string  sound id (the game's or one from a resource's assets)
---@param x number
---@param y number
---@param z number
---@param opts? WorldSoundOptions
function world.sound(id, x, y, z, opts) end

---Shows particles at a place (simple particle types only); returns how many players they were sent to.
---@param id string  particle id, e.g. 'minecraft:flame'
---@param x number
---@param y number
---@param z number
---@param opts? ParticleOptions
---@return integer
function world.particles(id, x, y, z, opts) end

-- ---------------------------------------------------------------------------------------------------------------------
-- items

items = {}

---Defines an item of this resource: from then on its id works wherever an item id does.
---@param id string  like 'bank:card'
---@param spec ItemDefinition
function items.define(id, spec) end

---The ids of every defined item, sorted.
---@return string[]
function items.defined() end

-- ---------------------------------------------------------------------------------------------------------------------
-- store: per resource, kept on disk

store = {}

---The value saved under a key.
---@param key string
---@return any
function store.get(key) end

---Saves a value (anything JSON can hold) under a key; nil removes it.
---@param key string
---@param value any
function store.set(key, value) end

---Removes a key.
---@param key string
function store.delete(key) end

---Every key this resource has saved.
---@return string[]
function store.keys() end

-- ---------------------------------------------------------------------------------------------------------------------
-- db: one SQL database for every resource; calls wait for the result

db = {}

---Stands for NULL in a list of parameters.
---@type table
db.null = nil

---Runs a SELECT and waits for its rows (up to 10000); parameters as a list or one by one (a nil is a NULL).
---@param sql string
---@param ... any
---@return table<string, any>[]
function db.query(sql, ...) end

---Runs a statement and waits for it; parameters as a list or one by one (a nil is a NULL).
---@param sql string
---@param ... any
---@return DbResult
function db.execute(sql, ...) end

---Runs statements as one transaction (all or nothing); returns the rows changed by each.
---@param statements DbStatement[]
---@return integer[]
function db.batch(statements) end

-- ---------------------------------------------------------------------------------------------------------------------
-- http: calls wait for the response; any status is a result, only no response is an error

http = {}

---Makes an HTTP request and waits for the response.
---@param url string  http or https
---@param opts? HttpOptions
---@return HttpResponse
function http.request(url, opts) end

---The same as http.request (GET unless opts.method says otherwise).
---@param url string
---@param opts? HttpOptions
---@return HttpResponse
function http.get(url, opts) end

---Sends a POST with a body and waits for the response.
---@param url string
---@param body? string
---@param opts? HttpPostOptions
---@return HttpResponse
function http.post(url, body, opts) end

-- ---------------------------------------------------------------------------------------------------------------------
-- zone

---@class Zone
---@field id integer
local Zone = {}

---Runs the handler when a player walks in; returns the zone.
---@param handler fun(player: Player, zone: Zone)
---@return Zone
function Zone:on_enter(handler) end

---Runs the handler when a player walks out or leaves the server; returns the zone.
---@param handler fun(player: Player, zone: Zone)
---@return Zone
function Zone:on_leave(handler) end

---The players inside now.
---@return Player[]
function Zone:players() end

---Removes the zone (not a leave for who is inside).
function Zone:remove() end

zone = {}

---A box between two opposite corners.
---@param a PosLike
---@param b PosLike
---@param dim? string  default the overworld
---@return Zone
function zone.box(a, b, dim) end

---A sphere around a point.
---@param center PosLike
---@param radius number
---@param dim? string  default the overworld
---@return Zone
function zone.sphere(center, radius, dim) end

---Internal: not available to scripts (the prelude removes it); use zone.box or zone.sphere.
---@deprecated
---@param spec table
---@return integer
function zone.create(spec) end

---Internal: not available to scripts (the prelude removes it); use Zone:remove.
---@deprecated
---@param id integer
function zone.remove(id) end

---Internal: not available to scripts (the prelude removes it); use Zone:players.
---@deprecated
---@param id integer
---@return Player[]
function zone.players(id) end

---Internal: not available to scripts (the prelude removes it); use Zone:on_enter.
---@deprecated
---@param handler fun(id: integer, player: Player)
function zone.on_enter(handler) end

---Internal: not available to scripts (the prelude removes it); use Zone:on_leave.
---@deprecated
---@param handler fun(id: integer, player: Player)
function zone.on_leave(handler) end

-- ---------------------------------------------------------------------------------------------------------------------
-- interact

---@class InteractTarget
---@field id integer
local InteractTarget = {}

---Ends the target.
function InteractTarget:remove() end

interact = {}

---Shows a label to players looking at the target from close enough; their Interact key runs the handler.
---@param spec InteractSpec
---@param opts InteractOptions
---@param handler fun(player: Player, target: InteractTarget)
---@return InteractTarget
function interact.add(spec, opts, handler) end

---Internal: not available to scripts (the prelude removes it); use InteractTarget:remove.
---@deprecated
---@param id integer
function interact.remove(id) end

---Internal: not available to scripts (the prelude removes it); pass the handler to interact.add.
---@deprecated
---@param handler fun(id: integer, player: Player)
function interact.on_use(handler) end

-- ---------------------------------------------------------------------------------------------------------------------
-- bossbar

---@class Bossbar
---@field id string
local Bossbar = {}

---Changes the bar (only the keys given).
---@param opts BossbarOptions
function Bossbar:set(opts) end

---Shows the bar to a player.
---@param player Player|string
function Bossbar:show(player) end

---Hides the bar from a player.
---@param player Player|string
function Bossbar:hide(player) end

---Removes the bar for everyone.
function Bossbar:remove() end

bossbar = {}

---Makes a bossbar (purple, full, shown to nobody yet).
---@param text string
---@param opts? BossbarOptions
---@return Bossbar
function bossbar.create(text, opts) end

---Changes a bar by its id; the same as Bossbar:set.
---@param id string
---@param opts BossbarOptions
function bossbar.set(id, opts) end

---Shows a bar to a player by its id; the same as Bossbar:show.
---@param id string
---@param player string
function bossbar.show(id, player) end

---Hides a bar from a player by its id; the same as Bossbar:hide.
---@param id string
---@param player string
function bossbar.hide(id, player) end

---Removes a bar by its id; the same as Bossbar:remove.
---@param id string
function bossbar.remove(id) end

-- ---------------------------------------------------------------------------------------------------------------------
-- sidebar: one for everyone; the last set wins

sidebar = {}

---Shows the sidebar with a title and up to 15 lines.
---@param title string
---@param lines string[]
function sidebar.set(title, lines) end

---Removes the sidebar.
function sidebar.clear() end

-- ---------------------------------------------------------------------------------------------------------------------
-- teams

teams = {}

---Creates a team of this resource, or changes it (only the keys given).
---@param name string  a-z, 0-9, '_' and '-', up to 16 characters
---@param opts? TeamOptions
function teams.set(name, opts) end

---Puts a player in a team of this resource.
---@param name string
---@param player Player|string
---@return boolean
function teams.add(name, player) end

---Takes a player out of the team they are in; false when they were in none.
---@param player Player|string
---@return boolean
function teams.remove(player) end

---Deletes a team of this resource.
---@param name string
function teams.delete(name) end
