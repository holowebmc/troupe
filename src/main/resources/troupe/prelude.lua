-- Runs first in every resource state. Its locals stay invisible to resource code.
local load_module, host = ...
local yield, pack, unpack = coroutine.yield, table.pack, table.unpack
local TOKEN = host.token

-- Hands the current task to the host until it has an answer (timer, network, another resource). Works anywhere in a
-- task started by Troupe (main.lua, handlers, async), but not inside a coroutine made with coroutine.create/wrap.
local function await(kind, ...)
  if not host.in_task() then error(kind .. " can't wait inside coroutine.create/wrap (use async)", 3) end
  local r = pack(yield(TOKEN, kind, ...))
  if not r[1] then error(r[2], 3) end
  return unpack(r, 2, r.n)
end

local loaded, loading = {}, {}

-- require('shared.config') loads shared/config.lua of this resource, once.
function require(name)
  if type(name) ~= "string" then error("bad argument #1 to 'require' (string expected, got " .. type(name) .. ")", 2) end
  local value = loaded[name]
  if value ~= nil then return value end
  if loading[name] then error("require cycle at '" .. name .. "'", 2) end
  local chunk, err = load_module(name)
  if not chunk then error(err, 2) end
  loading[name] = true
  local ok, result = pcall(chunk, name)
  loading[name] = nil
  if not ok then error(result, 0) end
  if result == nil then result = true end
  loaded[name] = result
  return result
end

-- Functions a mod registered as async (Module.async) wait for their answer like timer.wait does.
do
  local raw_call, raw_get, done = mods.call, mods.get, {}
  function mods.get(mod)
    local t = raw_get(mod)
    if not done[t] then
      done[t] = true
      for _, fn in ipairs(host.async(mod)) do
        t[fn] = function(...) return await("module", mod, fn, ...) end
      end
    end
    return t
  end
  function mods.call(mod, fn, ...)
    local f = rawget(mods.get(mod), fn)
    if f then return f(...) end
    return raw_call(mod, fn, ...)   -- raises "no function"
  end
end

-- Cobalt is Lua 5.2: what libraries written for 5.3 look for in math. Numbers are doubles, so "integer" means whole.
function math.type(x)
  if type(x) ~= "number" then return nil end
  return (x % 1 == 0 and x == x and x ~= math.huge and x ~= -math.huge) and "integer" or "float"
end
function math.tointeger(x)
  if type(x) == "string" then x = tonumber(x) end
  return math.type(x) == "integer" and x or nil
end
math.maxinteger, math.mininteger = 2 ^ 53, -(2 ^ 53)
function math.ult(a, b) return (a % 2 ^ 32) < (b % 2 ^ 32) end
function warn(...) print("warning:", ...) end

-- typed('(amount: integer, note?: string) -> integer', fn): fn with its arguments checked on every call, and its
-- signature in the editor definitions (/troupe stubs). For exports and anything else other code calls.
function typed(sig, fn)
  if type(sig) ~= "string" then error("bad argument #1 to 'typed' (string expected, got " .. type(sig) .. ")", 2) end
  if type(fn) ~= "function" then error("bad argument #2 to 'typed' (function expected, got " .. type(fn) .. ")", 2) end
  local check = host.check
  local function checked(...)
    local problem = check(checked, ...)
    if problem then error(problem, 2) end
    return fn(...)
  end
  host.typed(sig, checked)
  return checked
end

-- async(fn, ...) runs fn as a task of its own, after the current one yields or ends.
async = host.spawn

timer = {}
function timer.wait(ms) await("wait", ms) end

local cancelled, next_id = {}, 0
local function start_timer(ms, fn, every)
  if type(fn) ~= "function" then error("bad argument #2 (function expected, got " .. type(fn) .. ")", 3) end
  next_id = next_id + 1
  local id = next_id
  async(function()
    repeat
      timer.wait(ms)
      if cancelled[id] then break end
      fn()
    until not every
    cancelled[id] = nil
  end)
  return id
end
function timer.after(ms, fn) return start_timer(ms, fn, false) end
function timer.every(ms, fn) return start_timer(ms, fn, true) end
function timer.cancel(id) cancelled[id] = true end

-- net.handle / net.emit (and net.broadcast on the server) come from Java; calls wait for the reply.
function net.call(...) return await("call", ...) end

-- relay(pcall(f, ...)) inside a wrapper: an error points at whoever called the wrapper, not at this file.
local function relay(ok, ...)
  if ok then return ... end
  local msg = ...
  if type(msg) == "string" then msg = msg:gsub("^troupe:%d+: ", "") end
  error(msg, 3)
end

-- Players and entities can be passed as handles or ids.
local function pid(p) if type(p) == "table" then return p.id end return p end

-- Server: methods of player handles (players.*, net handlers, events). Players are passed around as their id.
local P = host.player_methods
if players then
  -- player:fn(...) is players.fn(player.id, ...)
  for _, fn in ipairs({ "message", "pos", "teleport", "give", "online", "mods", "mod_version", "has_permission",
                        "health", "max_health", "set_health", "food", "set_food", "gamemode", "set_gamemode",
                        "xp", "give_xp", "set_level", "effect", "effects", "clear_effects",
                        "title", "actionbar", "sound", "kick", "tab", "looking_at",
                        "items", "item", "set_item", "count", "take", "held",
                        "attribute", "set_attribute", "set_velocity", "glow", "dismount", "vehicle", "passengers",
                        "set_instance", "instance" }) do
    local f = players[fn]
    P[fn] = function(self, ...) return relay(pcall(f, self.id, ...)) end
  end
  local mount = players.mount
  function P:mount(vehicle) return relay(pcall(mount, self.id, pid(vehicle))) end
  function P:has_mod(name)
    for _, m in ipairs(self:mods()) do if m == name then return true end end
    return false
  end
end

-- Server: entity handles ({ id, type }, wherever they come from) get entity:fn(...) = entities.fn(entity.id, ...).
local E = host.entity_methods
if entities then
  for _, fn in ipairs({ "pos", "teleport", "remove", "alive", "name", "set_name", "health", "set_health",
                        "equip", "equipment", "attribute", "set_attribute", "effect", "clear_effects",
                        "set_velocity", "glow", "dismount", "vehicle", "passengers", "move_to", "set_ai",
                        "set_instance", "instance" }) do
    local f = entities[fn]
    E[fn] = function(self, ...) return relay(pcall(f, self.id, ...)) end
  end
  local mount = entities.mount
  function E:mount(vehicle) return relay(pcall(mount, self.id, pid(vehicle))) end
end

-- Server: zone.box(a, b, dim?) / zone.sphere(center, radius, dim?) return an object:
-- z:on_enter(fn(player, z)), z:on_leave(fn(player, z)), z:players(), z:remove().
if zone then
  local Z, zones = {}, {}
  Z.__index = Z
  local create, remove, inside = zone.create, zone.remove, zone.players
  local function new(spec)
    local z = setmetatable({ id = relay(pcall(create, spec)), entering = {}, leaving = {} }, Z)
    zones[z.id] = z
    return z
  end
  function zone.box(a, b, dim) return new({ box = { a, b }, dim = dim }) end
  function zone.sphere(center, radius, dim) return new({ sphere = center, radius = radius, dim = dim }) end
  function Z:on_enter(fn) table.insert(self.entering, fn) return self end
  function Z:on_leave(fn) table.insert(self.leaving, fn) return self end
  function Z:players() return inside(self.id) end
  function Z:remove() zones[self.id] = nil remove(self.id) end
  zone.on_enter(function(id, p)
    local z = zones[id]
    if z then for _, fn in ipairs(z.entering) do async(fn, p, z) end end
  end)
  zone.on_leave(function(id, p)
    local z = zones[id]
    if z then for _, fn in ipairs(z.leaving) do async(fn, p, z) end end
  end)
  zone.create, zone.remove, zone.players, zone.on_enter, zone.on_leave = nil, nil, nil, nil, nil
end

-- Server: interact.add({ block = pos } | { entity = e } | { pos = pos, radius = r }, { label, distance, dim }, fn(player, target))
-- shows the label to players looking at it from close enough; their Interact key runs fn. target:remove() ends it.
if interact then
  local T, targets = {}, {}
  T.__index = T
  local add, remove = interact.add, interact.remove
  function interact.add(spec, opts, fn)
    if type(fn) ~= "function" then error("bad argument #3 to 'add' (function expected, got " .. type(fn) .. ")", 2) end
    if type(spec) == "table" and type(spec.entity) == "table" then spec = { entity = spec.entity.id } end
    local t = setmetatable({ id = relay(pcall(add, spec, opts or {})), fn = fn }, T)
    targets[t.id] = t
    return t
  end
  function T:remove() targets[self.id] = nil remove(self.id) end
  interact.on_use(function(id, p)
    local t = targets[id]
    if t then async(t.fn, p, t) end
  end)
  interact.remove, interact.on_use = nil, nil
end

-- Server: bossbar.create(...) returns an object: bar:set{...}, bar:show(p), bar:hide(p), bar:remove().
if bossbar then
  local B = {}
  B.__index = B
  local create, set, show, hide, remove = bossbar.create, bossbar.set, bossbar.show, bossbar.hide, bossbar.remove
  function B:set(opts) return relay(pcall(set, self.id, opts)) end
  function B:show(p) return relay(pcall(show, self.id, pid(p))) end
  function B:hide(p) return relay(pcall(hide, self.id, pid(p))) end
  function B:remove() return relay(pcall(remove, self.id)) end
  function bossbar.create(...) return setmetatable({ id = relay(pcall(create, ...)) }, B) end
end

if teams then
  local add, rm = teams.add, teams.remove
  function teams.add(name, p) return relay(pcall(add, name, pid(p))) end
  function teams.remove(p) return relay(pcall(rm, pid(p))) end
end

-- Client: keys.bind('b', fn) runs fn when b is pressed with no screen open.
if keys then
  -- keys.register('abrir', { key = 'b', label = 'Abrir banco' }, fn): the same, but the player can change the key
  -- in the game's Controls screen.
  local register, actions = keys.register, {}
  if register then
    keys.on_action(function(id)
      if actions[id] then actions[id]() end
    end)
    function keys.register(id, opts, fn)
      if type(fn) ~= "function" then error("bad argument #3 to 'register' (function expected, got " .. type(fn) .. ")", 2) end
      relay(pcall(register, id, opts or {}))
      actions[id] = fn
    end
  end
  local bound = {}
  keys.on_press(function(key)
    for _, fn in ipairs(bound[key] or {}) do fn() end
  end)
  function keys.bind(key, fn)
    if type(fn) ~= "function" then error("bad argument #2 to 'bind' (function expected, got " .. type(fn) .. ")", 2) end
    relay(pcall(keys.watch, key))
    bound[key] = bound[key] or {}
    table.insert(bound[key], fn)
  end
end

-- Server: http.request(url, { method, headers, body, timeout }) waits for { status, headers, body }.
if http then
  function http.request(url, opts) return await("module", "http", "request", url, opts or {}) end
  http.get = http.request
  function http.post(url, body, opts)
    opts = opts or {}
    return http.request(url, { method = "POST", body = body, headers = opts.headers, timeout = opts.timeout })
  end
end

-- Server: db.query / db.execute / db.batch wait for the database.
-- Parameters go as a list, db.query(sql, { 'a', db.null }), or one by one, db.query(sql, 'a', nil): written one by
-- one, a nil is a NULL (Lua knows how many arguments there are, but not where a list with nils ends).
if db then
  local NULL = setmetatable({}, { __tostring = function() return "db.null" end })
  db.null = NULL
  -- Java gets a list with no holes: every NULL is an empty table (no real parameter is a table).
  local function fill(n, get)
    local out = {}
    for i = 1, n do
      local v = get(i)
      if v == nil or v == NULL then v = {} end
      out[i] = v
    end
    return out
  end
  local function list(t)
    local n = 0
    for k in pairs(t) do if type(k) == "number" and k > n then n = k end end   -- a nil in the middle doesn't cut it short
    return fill(n, function(i) return t[i] end)
  end
  local function params(...)
    local n, first = select("#", ...), ...
    if n == 1 and type(first) == "table" and first ~= NULL then return list(first) end
    local args = pack(...)
    return fill(n, function(i) return args[i] end)
  end
  function db.query(sql, ...) return await("module", "db", "query", sql, params(...)) end
  function db.execute(sql, ...) return await("module", "db", "execute", sql, params(...)) end
  function db.batch(statements)
    if type(statements) ~= "table" then error("bad argument #1 to 'batch' (table expected, got " .. type(statements) .. ")", 2) end
    local out = {}
    for i, st in ipairs(statements) do
      if type(st) == "table" then out[i] = { st[1], list(type(st[2]) == "table" and st[2] or {}) } else out[i] = st end
    end
    return await("module", "db", "batch", out)
  end
end

-- resource('banco').sacar(10) calls what banco's main.lua returned, in banco's own state, and waits for it.
function resource(name)
  if type(name) ~= "string" then error("bad argument #1 to 'resource' (string expected, got " .. type(name) .. ")", 2) end
  return setmetatable({}, { __index = function(_, fn)
    return function(...) return await("export", name, fn, ...) end
  end })
end
