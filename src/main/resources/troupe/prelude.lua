-- Runs first in every resource state. Its locals stay invisible to resource code.
local load_module = ...
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
