-- liu_custom_word_processor.lua
-- 記錄自訂詞候選的明確選字，供 liu_custom_word_filter.lua 排序學習使用。

local learning = require("liu_custom_word_learning")

local M = {}

function M.init(env)
    learning.ensure_loaded()

    if not env.commit_notifier then
        env.commit_notifier = env.engine.context.commit_notifier:connect(function(ctx)
            local committed_text = (ctx:get_commit_text() or ""):gsub("^%s+", ""):gsub("%s+$", "")
            if committed_text ~= "" then
                learning.learn_from_commit(committed_text)
            else
                learning.clear_pending()
            end
        end)
    end
end

function M.fini(env)
    if env.commit_notifier then
        env.commit_notifier:disconnect()
        env.commit_notifier = nil
    end
end

function M.func(key, env)
    local context = env.engine.context
    local composition = context.composition
    if not composition or composition:empty() then
        return 2
    end

    local seg = composition:back()
    if not seg or not seg.menu or seg.menu:candidate_count() <= 0 then
        return 2
    end

    local key_repr = key:repr()
    local selected_index = nil

    if key_repr == "space" or key_repr == "Return" or key_repr == "KP_Enter" then
        selected_index = seg.selected_index
    else
        local digit = key_repr:match("^[1-9]$")
        if not digit then
            digit = key_repr:match("^KP_([1-9])$")
        end
        if digit then
            local page_size = env.engine.schema.page_size or 5
            selected_index = math.floor(seg.selected_index / page_size) * page_size + tonumber(digit) - 1
        end
    end

    if selected_index and selected_index >= 0 and selected_index < seg.menu:candidate_count() then
        local cand = seg.menu:get_candidate_at(selected_index)
        if cand and (cand.type == "custom" or cand.type == "custom_completion") then
            learning.record_pending_selection(context.input or "", cand.text)
        end
    end

    return 2
end

return M
