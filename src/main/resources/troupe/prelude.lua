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

-- Server: methods of player handles (players.*, net handlers, events). Players are passed around as their id.
local P = host.player_methods
if players then
  function P:message(text) return relay(pcall(players.message, self.id, text)) end
  function P:pos() return relay(pcall(players.pos, self.id)) end
  function P:teleport(x, y, z, dim) return relay(pcall(players.teleport, self.id, x, y, z, dim)) end
  function P:give(item, count) return relay(pcall(players.give, self.id, item, count)) end
  function P:online() return relay(pcall(players.online, self.id)) end
end

-- Client: keys.bind('b', fn) runs fn when b is pressed with no screen open.
if keys then
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

-- resource('banco').sacar(10) calls what banco's main.lua returned, in banco's own state, and waits for it.
function resource(name)
  if type(name) ~= "string" then error("bad argument #1 to 'resource' (string expected, got " .. type(name) .. ")", 2) end
  return setmetatable({}, { __index = function(_, fn)
    return function(...) return await("export", name, fn, ...) end
  end })
end
