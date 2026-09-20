"""Run with Python + lupa; mocks KOReader UI, using the real companion Lua module."""
from pathlib import Path
from lupa.lua51 import LuaRuntime

lua = LuaRuntime()
lua.execute('''
package.preload["ui/widget/container/widgetcontainer"] = function()
    local base = {}
    function base:extend(t) t.__index = t; return setmetatable(t, {__index=self}) end
    function base:new(t) setmetatable(t, self); t:init(); return t end
    return base
end
package.preload["ui/event"] = function() return {new=function(_, name, value) return {name=name,value=value} end} end
package.preload["ui/uimanager"] = function() return {show=function() end} end
package.preload["ui/widget/infomessage"] = function() return {new=function(_, t) return t end} end
''')
bridge = lua.execute((Path(__file__).parent / "media-portal.koplugin/main.lua").read_text())
lua.globals().Bridge = bridge
lua.execute('''
local settings = {}
local events = {}
local native = {}
local ui = {
    menu={registerToMainMenu=function() end},
    document={info={has_pages=false},getXPointer=function() return "/original" end},
    doc_settings={
        saveSetting=function(_,k,v) settings[k]=v end,
        readSetting=function(_,k) return settings[k] end,
        delSetting=function(_,k) settings[k]=nil end,
    },
    handleEvent=function(_,e) table.insert(events,e) end,
    kosync={syncToProgress=function(_,p) table.insert(native,p) end},
}
local plugin=Bridge:new{ui=ui}
plugin:onReadSettings()
plugin:onReadSettings() -- Installing the hook twice must not nest it.
ui.kosync:syncToProgress("/body/DocFragment[4]/body/p[3]")
assert(#native==1 and #events==0)
ui.kosync:syncToProgress("nmp-percent-v1:0.45")
assert(#events==1 and events[1].name=="GotoPercent" and events[1].value==45)
assert(settings.media_portal_previous_xpointer=="/original")
for _,bad in ipairs({"nmp-percent-v1:1.1", "nmp-percent-v1:-1", "nmp-percent-v1:nan", "nmp-percent-v2:0.5", "nmp-percent-v1:0.2x"}) do ui.kosync:syncToProgress(bad) end
assert(#events==1 and #native==1)
ui.document.info.has_pages=true
ui.kosync:syncToProgress("nmp-percent-v1:0.3")
assert(#events==1)
ui.document.info.has_pages=false
ui.kosync:syncToProgress("nmp-percent-v1:0")
ui.kosync:syncToProgress("nmp-percent-v1:1")
assert(events[2].value==0 and events[3].value==100)
local menu={}; plugin:addToMainMenu(menu)
menu.media_portal.sub_item_table[2].callback()
assert(events[4].name=="GotoXPointer" and events[4].value=="/original")
assert(settings.media_portal_previous_xpointer==nil)
''')
print("PASS: Lua 5.1 native bookmarks; percentage conversion; invalid input; PDF guard; repeated hook; undo")
