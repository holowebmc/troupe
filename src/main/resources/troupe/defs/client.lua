---@meta
-- Troupe: what scripts have on the CLIENT only (shared.lua has what exists on both sides).
-- Editor definitions only (Lua Language Server); nothing here runs.

---A color: 0xRRGGBB (opaque) or 0xAARRGGBB.
---@alias Color integer

---Where on the screen x and y are measured from.
---@alias HudAnchor
---| "top_left"
---| "top"
---| "top_right"
---| "left"
---| "center"
---| "right"
---| "bottom_left"
---| "bottom"
---| "bottom_right"

-- ---- player (the local player; unrelated to the class Player)

---@class ClientPos : Pos
---@field yaw number
---@field pitch number

---What the crosshair is on: a block (x, y, z, id, face) or an entity (id, type).
---@class LookingAt
---@field kind "block"|"entity"
---@field id string block id, or the entity's UUID
---@field x? integer block only
---@field y? integer block only
---@field z? integer block only
---@field face? string block only: the side hit ('up', 'north'...)
---@field type? string entity only: its type id

player = {}

---The local player's name.
---@return string
function player.name() end

---The local player's position and rotation.
---@return ClientPos
function player.pos() end

---What the local player is looking at, or nil.
---@return LookingAt?
function player.looking_at() end

-- ---- entities

---@class NearEntity : Entity
---@field x number
---@field y number
---@field z number

entities = {}

---The entities this client knows about within radius blocks (default 16, max 128), nearest first (256 at most).
---@param radius? number
---@return NearEntity[]
function entities.near(radius) end

-- ---- chat

chat = {}

---Shows a line in the local chat.
---@param text string
function chat.print(text) end

-- ---- keys

---@class KeyOpts
---@field key? string default key, named as in keys.bind
---@field label? string name in the Controls screen (default: the id; 48 characters at most)

keys = {}

---Starts watching a key ('b', 'f6', 'left.alt') so keys.on_press reports it.
---@param key string
function keys.watch(key) end

---Runs handler when a watched key is pressed with no screen open.
---@param handler fun(key: string)
function keys.on_press(handler) end

---Runs handler when a key made with keys.register is pressed with no screen open.
---@param handler fun(id: string)
function keys.on_action(handler) end

---Runs fn when key ('b', 'f6', 'left.alt') is pressed with no screen open.
---@param key string
---@param fn fun()
function keys.bind(key, fn) end

---Like keys.bind, but the player can change the key in the game's Controls screen (id: a-z, 0-9, '_').
---@param id string
---@param opts KeyOpts?
---@param fn fun()
function keys.register(id, opts, fn) end

-- ---- hud

---@class HudOpts
---@field x? number offset from the anchor (default 0)
---@field y? number offset from the anchor (default 0)
---@field color? Color
---@field alpha? number 0..1, overrides the color's alpha
---@field anchor? HudAnchor default 'top_left'
---@field layer? integer higher layers are drawn on top (default 0)

---@class HudTextOpts : HudOpts
---@field shadow? boolean default true
---@field scale? number 0.1..16 (default 1)

---@class HudRectOpts : HudOpts
---@field w? number width in pixels (default 0)
---@field h? number height in pixels (default 0)

---@class HudImageOpts : HudOpts
---@field w? number width on screen (default 16)
---@field h? number height on screen (default 16)
---@field u? number left of the region in the texture (default 0)
---@field v? number top of the region in the texture (default 0)
---@field tex_w? number texture width (default w: the whole image fills w x h)
---@field tex_h? number texture height (default h)

hud = {}

---Shows or replaces a text on the HUD (color defaults to white).
---@param id string
---@param text string
---@param opts? HudTextOpts
function hud.text(id, text, opts) end

---Shows or replaces a rectangle on the HUD (color defaults to 0x80000000).
---@param id string
---@param opts? HudRectOpts
function hud.rect(id, opts) end

---Shows or replaces an image on the HUD (texture: 'ns:textures/gui/x.png').
---@param id string
---@param texture string
---@param opts? HudImageOpts
function hud.image(id, texture, opts) end

---Removes one HUD item of this resource.
---@param id string
function hud.remove(id) end

---Removes every HUD item of this resource.
function hud.clear() end

---Shows a toast notification.
---@param title string
---@param text? string
function hud.toast(title, text) end

-- ---- effects (only for this player)

---@class SoundOpts
---@field volume? number 0..1 (default 1)
---@field pitch? number 0.01..2 (default 1)

---@class ParticleOpts
---@field count? integer 1..200 (default 1)
---@field spread? number 0..16 blocks (default 0)
---@field speed? number default 0

effects = {}

---Plays a sound for this player.
---@param id string sound id
---@param opts? SoundOpts
function effects.sound(id, opts) end

---Spawns simple particles for this player.
---@param id string particle id
---@param x number
---@param y number
---@param z number
---@param opts? ParticleOpts
function effects.particles(id, x, y, z, opts) end

-- ---- draw (shapes in the world, kept until removed) and render (per frame)

---@class LineOpts
---@field from Pos
---@field to Pos
---@field color? Color default white
---@field alpha? number 0..1, overrides the color's alpha
---@field width? number 1..16 (default 2)
---@field dim? string only in this dimension

---@class BoxOpts
---@field min Pos
---@field max Pos
---@field color? Color default white
---@field alpha? number 0..1, overrides the color's alpha
---@field fill? boolean faces instead of edges (default false)
---@field dim? string only in this dimension

---@class BillboardOpts
---@field pos Pos
---@field size? number 0.01..64 blocks (default 1)
---@field color? Color default white
---@field alpha? number 0..1, overrides the color's alpha
---@field dim? string only in this dimension

---@class ModelOpts
---@field pos Pos
---@field yaw? number degrees, 0 faces south (default 0)
---@field scale? number 0..64 (default 1)
---@field animation? string plays from the start whenever it changes
---@field dim? string only in this dimension

---@class WorldTextOpts
---@field pos Pos
---@field color? Color default white
---@field alpha? number 0..1, overrides the color's alpha
---@field scale? number 0.1..16 (default 1)
---@field background? Color default 0x40000000
---@field see_through? boolean visible through blocks (default false)
---@field dim? string only in this dimension

draw = {}

---Draws or replaces a line in the world.
---@param id string
---@param opts LineOpts
function draw.line(id, opts) end

---Draws or replaces a box in the world.
---@param id string
---@param opts BoxOpts
function draw.box(id, opts) end

---Draws or replaces an image in the world, facing the camera.
---@param id string
---@param texture string
---@param opts BillboardOpts
function draw.billboard(id, texture, opts) end

---Draws or replaces a text in the world, facing the camera.
---@param id string
---@param text string
---@param opts WorldTextOpts
function draw.text(id, text, opts) end

---Draws or replaces a Blockbench model from models/<name>.bbmodel ('name', or 'resource:name') in the world.
---@param id string
---@param model string
---@param opts ModelOpts
function draw.model(id, model, opts) end

---Removes one shape of this resource.
---@param id string
function draw.remove(id) end

---Removes every shape of this resource.
function draw.clear() end

---@class CameraPoint : Pos
---@field yaw? number default 0
---@field pitch? number default 0

---@class CameraPathOpts
---@field ease? boolean start and stop gently (default true)
---@field hold? boolean stay on the last point instead of going back to the player (default false)

---@class HudScreen
---@field width integer
---@field height integer

render = {}

---Runs handler every frame; render.rect / render.text / render.image only work inside it.
---@param handler fun(screen: HudScreen)
function render.on_hud(handler) end

---Runs handler every frame (view: camera position); render.line / box / billboard / world_text only work inside it.
---@param handler fun(view: Pos)
function render.on_world(handler) end

---Draws a rectangle on the HUD for this frame (inside render.on_hud).
---@param opts? HudRectOpts
function render.rect(opts) end

---Draws a text on the HUD for this frame (inside render.on_hud).
---@param text string
---@param opts? HudTextOpts
function render.text(text, opts) end

---Draws an image on the HUD for this frame (inside render.on_hud).
---@param texture string
---@param opts? HudImageOpts
function render.image(texture, opts) end

---Draws a line in the world for this frame (inside render.on_world).
---@param opts LineOpts
function render.line(opts) end

---Draws a box in the world for this frame (inside render.on_world).
---@param opts BoxOpts
function render.box(opts) end

---Draws an image in the world for this frame (inside render.on_world).
---@param texture string
---@param opts BillboardOpts
function render.billboard(texture, opts) end

---Draws a text in the world for this frame (inside render.on_world).
---@param text string
---@param opts WorldTextOpts
function render.world_text(text, opts) end

---Turns on a post effect from the assets ('ns:name'); nil turns it off.
---@param id? string
function render.post(id) end

---Multiplies the player's FOV (0.1..3); nil restores it.
---@param multiplier? number
function render.fov(multiplier) end

---Shakes the view, fading out.
---@param intensity number degrees, up to 10
---@param seconds? number up to 60 (default 0.5)
function render.shake(intensity, seconds) end

---Fixes the camera at a point; nil gives it back to the player.
---@param point? CameraPoint
function render.camera(point) end

---Moves the camera through 2..256 points in seconds (up to 600).
---@param points CameraPoint[]
---@param seconds number
---@param opts? CameraPathOpts
function render.camera_path(points, seconds, opts) end

-- ---- ui (pages of the resource's ui/ folder; needs the Vitrine mod on the client)

---Options of ui.world and panel:move.
---@class UiPanelOpts
---@field pos? number[] { x, y, z } as a list (required in ui.world)
---@field yaw? number default 0
---@field pitch? number 90 lays the panel face up, -90 face down (default 0)
---@field width? number width in blocks, up to 64 (default 2)
---@field size? integer[] { width, height } of the page in pixels, 16..4096 (default { 1280, 720 }); ui.world only
---@field billboard? boolean always faces the camera (default false)
---@field distance? number hidden beyond it, up to 512 blocks (default 64)
---@field volume? number 0..1 (default 1)
---@field range? number where the page's sound fades out, up to 512 blocks (default 24)

---What a slot draws; exactly one of item, inventory, entity, entity_type, player, block, text, image, rect.
---@class UiSlotThing
---@field item? string item id
---@field inventory? integer a slot of the player's own inventory
---@field entity? string id of a living entity
---@field entity_type? string a stand-in of this entity type
---@field player? boolean the local player
---@field block? string block id
---@field text? string
---@field image? string texture id
---@field rect? boolean
---@field state? table<string, any> block state properties
---@field transform? any[] list of { 'translate', x, y, z } / { 'rotate', axis, degrees } / { 'scale', n }
---@field x? number
---@field y? number
---@field z? number
---@field scale? number|table a number or { x, y, z }
---@field yaw? number degrees
---@field pitch? number degrees
---@field roll? number degrees
---@field fit? boolean false: one pixel per block instead of fitted to the slot
---@field light? integer 0..15 (default 15)
---@field lighting? "entity"|"flat"|"3d"|"level"|"skin"
---@field enchanted? boolean items
---@field damage? any items
---@field model? any items: custom model data
---@field item_model? string items: id of an item model
---@field w? number images, rectangles
---@field h? number images, rectangles
---@field u0? number images
---@field v0? number images
---@field u1? number images
---@field v1? number images
---@field color? Color images, rectangles, texts
---@field shadow? boolean texts
---@field background? any texts
---@field body_yaw? number entities
---@field head_yaw? number entities
---@field head_pitch? number entities
---@field walk? any entities
---@field walk_speed? any entities
---@field swing? any entities
---@field pose? string entities: 'crouching', 'sleeping'...
---@field crouching? boolean entities
---@field upside_down? boolean entities
---@field hurt? any entities
---@field fire? any entities
---@field age? any entities
---@field natural? boolean entities: keep the way they face in the world
---@field equipment? table<string, string> stand-ins: e.g. { head = 'minecraft:iron_helmet' }
---@field baby? boolean stand-ins

---What a slot function receives every frame.
---@class UiSlotInfo
---@field width integer
---@field height integer
---@field args string[] what follows the key in data-vitrine

---A page on screen or in the world; the browser is created on the first show.
---@class UiPage
local UiPage = {}

---Shows the page.
function UiPage:show() end

---Hides the page (and takes the keyboard back from it).
function UiPage:hide() end

---Sends a message to the page (troupe.on in troupe.js); waits for the first show if needed.
---@param name string
---@param data? any
function UiPage:send(name, data) end

---Answers the page's troupe.call(name, data) with fn's first result.
---@param name string
---@param fn fun(data: any): any
function UiPage:handle(name, fn) end

---Sets the page's volume (0..1).
---@param volume number
function UiPage:volume(volume) end

---Whether the page has the keyboard.
---@return boolean
function UiPage:focused() end

---Closes the page; it can't be used afterwards.
function UiPage:close() end

---A page made by ui.screen.
---@class UiScreen : UiPage
local UiScreen = {}

---Gives the keyboard and mouse to the page (showing it), or takes them back with false.
---@param on? boolean default true
function UiScreen:focus(on) end

---Runs fn when the page loses the keyboard without the script asking (Esc, another screen).
---@param fn fun()
function UiScreen:on_unfocus(fn) end

---A page made by ui.world.
---@class UiPanel : UiPage
local UiPanel = {}

---Changes any of the panel's options except size; the rest stays.
---@param opts UiPanelOpts
function UiPanel:move(opts) end

ui = {}

---A page of ui/ on the screen (hidden until :show()).
---@param page string path inside ui/, e.g. 'index.html'
---@return UiScreen
function ui.screen(page) end

---A page of ui/ on a panel in the world (hidden until :show()).
---@param page string path inside ui/
---@param opts UiPanelOpts
---@return UiPanel
function ui.world(page, opts) end

---Sets what the game draws in the slot `key` of this resource's pages (Minecraft 26.2+): a thing, up to 16 things, a function asked every frame, or nil to clear.
---@param key string
---@param what? UiSlotThing|UiSlotThing[]|fun(slot: UiSlotInfo): (UiSlotThing|UiSlotThing[]|nil)
function ui.slot(key, what) end
