---@meta
-- Troupe: what every script has on BOTH sides (server and client).
-- Editor definitions only (Lua Language Server); nothing here runs.

---A player handle. The server adds methods and state to it (see server.lua).
---@class Player
---@field id string UUID as text
---@field name string

---An entity handle. The server adds methods and state to it (see server.lua).
---@class Entity
---@field id string UUID as text
---@field type string entity type id, e.g. 'minecraft:zombie'

---A position in the world.
---@class Pos
---@field x number
---@field y number
---@field z number

---A type name in a net.handle format; a trailing '?' makes the field optional.
---@alias NetType
---| "string"
---| "number"
---| "integer"
---| "boolean"
---| "table"
---| "any"
---| "string?"
---| "number?"
---| "integer?"
---| "boolean?"
---| "table?"
---| "any?"

---Format of net.handle arguments: a type name, { field = format } (an object; unnamed fields are rejected) or { format } (a list).
---@alias NetFormat NetType|table

---Options of net.call.
---@class NetCallOpts
---@field timeout? number seconds to wait for the reply (default 10)

-- ---- globals

---Prints a line to the log, prefixed with the resource name.
---@param ... any
function print(...) end

---Loads a Lua file once and returns what it returned: 'shared.config' is shared/config.lua of this resource,
---'base:shared.util' is shared/util.lua of a resource listed under dependencies (it runs in this resource's state).
---@param name string
---@return any
function require(name) end

---Logs a warning.
---@param ... any
function warn(...) end

-- ---- what Troupe adds to the standard library (Cobalt is Lua 5.2 with utf8, string.pack, table.move and goto)

---'integer' for a whole number, 'float' for any other number, nil for anything else. Numbers are doubles.
---@param x any
---@return "integer"|"float"|nil
function math.type(x) end

---x when it is a whole number (or a string of one), else nil.
---@param x any
---@return integer?
function math.tointeger(x) end

---Whether a < b comparing both as unsigned 32-bit numbers.
---@param a integer
---@param b integer
---@return boolean
function math.ult(a, b) end

---The largest whole number a double holds exactly (2^53).
---@type integer
math.maxinteger = nil
---@type integer
math.mininteger = nil

---Unsigned 32-bit operations (Lua 5.2's bit32; there are no & | ~ << >> operators).
bit32 = {}
---@param ... integer
---@return integer
function bit32.band(...) end
---@param ... integer
---@return integer
function bit32.bor(...) end
---@param ... integer
---@return integer
function bit32.bxor(...) end
---@param ... integer
---@return boolean
function bit32.btest(...) end
---@param x integer
---@return integer
function bit32.bnot(x) end
---@param x integer
---@param n integer
---@return integer
function bit32.lshift(x, n) end
---@param x integer
---@param n integer
---@return integer
function bit32.rshift(x, n) end
---@param x integer
---@param n integer
---@return integer
function bit32.arshift(x, n) end
---@param x integer
---@param n integer
---@return integer
function bit32.lrotate(x, n) end
---@param x integer
---@param n integer
---@return integer
function bit32.rrotate(x, n) end
---@param x integer
---@param field integer
---@param width? integer
---@return integer
function bit32.extract(x, field, width) end
---@param x integer
---@param value integer
---@param field integer
---@param width? integer
---@return integer
function bit32.replace(x, value, field, width) end

---Runs fn as a task of its own, after the current one yields or ends.
---@param fn function
---@param ... any arguments passed to fn
function async(fn, ...) end

---Runs fn when this resource stops.
---@param fn fun()
function on_stop(fn) end

---Returns a proxy of another resource: each function calls what its main.lua returned and waits for the result.
---@param name string
---@return table<string, fun(...: any): ...>
function resource(name) end

---Wraps fn so every call's arguments are checked against sig, e.g. '(amount: integer, to?: Player) -> boolean'.
---The signature also goes into the generated definitions of this resource's exports (/troupe stubs).
---@generic F: function
---@param sig string
---@param fn F
---@return F
function typed(sig, fn) end

-- ---- timer

timer = {}

---Pauses the current task for ms milliseconds.
---@param ms number
function timer.wait(ms) end

---Runs fn once, ms milliseconds from now.
---@param ms number
---@param fn fun()
---@return integer id for timer.cancel
function timer.after(ms, fn) end

---Runs fn every ms milliseconds.
---@param ms number
---@param fn fun()
---@return integer id for timer.cancel
function timer.every(ms, fn) end

---Stops a timer made by timer.after or timer.every.
---@param id integer
function timer.cancel(id) end

-- ---- mods

mods = {}

---Whether a mod's module exists and this resource can reach it.
---@param name string
---@return boolean
function mods.has(name) end

---The version of a mod's module, or nil when it is missing or has none.
---@param name string
---@return string?
function mods.version(name) end

---The table of functions of a mod's module.
---@param name string
---@return table
function mods.get(name) end

---Calls one function of a mod's module (async ones wait for their answer).
---@param name string
---@param fn string
---@param ... any
---@return any ...
function mods.call(name, fn, ...) end

---Listens to an event of a mod's module.
---@param name string
---@param event string
---@param handler function
function mods.on(name, event, handler) end

-- ---- json

json = {}

---Encodes a value as JSON text.
---@param value any
---@return string
function json.encode(value) end

---Decodes JSON text.
---@param text string
---@return any
function json.decode(text) end

-- ---- net

net = {}

---Handles a message or call from the other side. Server: (name, format, fn(player, args)), format required. Client: (name, fn(args)) or (name, format, fn(args)).
---@param name string
---@param format NetFormat
---@param fn fun(player: Player, args: any): any
---@overload fun(name: string, fn: fun(args: any): any)
---@overload fun(name: string, format: NetFormat, fn: fun(args: any): any)
function net.handle(name, format, fn) end

---Sends a message without waiting. Server: (player, name, args) to one player. Client: (name, args) to the server.
---@param player Player|string a player handle or its id
---@param name string
---@param args? any
---@overload fun(name: string, args?: any)
function net.emit(player, name, args) end

---Calls a handler on the other side and waits for its first result (errors on failure or timeout). Server: (player, name, args, opts). Client: (name, args, opts).
---@param player Player|string a player handle or its id
---@param name string
---@param args? any
---@param opts? NetCallOpts
---@return any
---@overload fun(name: string, args?: any, opts?: NetCallOpts): any
function net.call(player, name, args, opts) end

---Server only: sends a message to every player.
---@param name string
---@param args? any
function net.broadcast(name, args) end

-- ---- state

state = {}

---Global replicated state of this resource: the server writes (nil removes), clients only read.
---@type table<string, any>
state.global = {}

---Client only: this player's replicated state, read-only (the server writes it as player.state).
---@type table<string, any>
state.player = {}

---Client only: runs fn when a key of this player's state changes (nil: removed).
---@param key string
---@param fn fun(value: any)
function state.watch(key, fn) end

---Client only: runs fn when a key of the global state changes (nil: removed).
---@param key string
---@param fn fun(value: any)
function state.watch_global(key, fn) end

---Client only: runs fn when a key of the state of an entity in sight changes (nil: removed or out of sight).
---@param key string
---@param fn fun(id: string, value: any)
function state.watch_entity(key, fn) end

---Client only: the read-only state of an entity or player in sight (the server writes entity.state / player.public).
---@param id string
---@return table<string, any>
function state.entity(id) end
