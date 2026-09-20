local WidgetContainer = require("ui/widget/container/widgetcontainer")
local Event = require("ui/event")
local UIManager = require("ui/uimanager")
local InfoMessage = require("ui/widget/infomessage")

local Bridge = WidgetContainer:extend { name = "media_portal", is_doc_only = true }
local function notify(text)
    UIManager:show(InfoMessage:new { text = text })
end

function Bridge:init()
    self.ui.menu:registerToMainMenu(self)
end

-- All plugin instances exist by ReadSettings, before KOReader's automatic reader-ready pull.
function Bridge:onReadSettings()
    local sync = self.ui.kosync
    if not sync or type(sync.syncToProgress) ~= "function" or self.original then return end
    self.original = sync.syncToProgress
    local original = self.original
    local ui = self.ui
    sync.syncToProgress = function(instance, progress)
        if type(progress) ~= "string" or progress:sub(1, 4) ~= "nmp-" then
            return original(instance, progress)
        end
        local value = progress:match("^nmp%-percent%-v1:([%d%.]+)$")
        local fraction = value and tonumber(value)
        if not fraction or fraction ~= fraction or fraction < 0 or fraction > 1 or ui.document.info.has_pages then
            notify("Folio: unsupported position. Your place has not changed.")
            return
        end
        ui.doc_settings:saveSetting("media_portal_previous_xpointer", ui.document:getXPointer())
        ui:handleEvent(Event:new("GotoPercent", fraction * 100))
    end
end

function Bridge:addToMainMenu(items)
    items.media_portal = {
        text = "Folio bridge",
        sub_item_table = {
            { text = "Bridge status", callback = function()
                notify(self.original and "Bridge ready. Use Binary document matching and the same sync account in both apps. Resume is approximate; completion status is separate."
                    or "Enable KOReader's Progress sync plugin and reopen this book.")
            end },
            { text = "Undo last Folio jump", enabled_func = function()
                return self.ui.doc_settings:readSetting("media_portal_previous_xpointer") ~= nil
            end, callback = function()
                local previous = self.ui.doc_settings:readSetting("media_portal_previous_xpointer")
                if previous then
                    self.ui:handleEvent(Event:new("GotoXPointer", previous))
                    self.ui.doc_settings:delSetting("media_portal_previous_xpointer")
                end
            end },
        },
    }
end
return Bridge
