-- liu_custom_word_learning.lua
-- 自訂詞排序學習共用模組：
-- 1. 讀寫 custom_word_weights.tsv
-- 2. 記錄本次候選明確選字
-- 3. 提供排序所需的學習權重查詢

local M = {}

local LEARN_FILE = "custom_word_weights.tsv"

local data_cache = {
    loaded = false,
    weights = {},
    learned_path = nil,
}

local runtime_state = {
    pending_selection = nil,
}

local function get_weight_key(code, text)
    return string.lower(code or "") .. "\t" .. (text or "")
end

function M.ensure_loaded()
    if data_cache.loaded then
        return
    end

    data_cache.loaded = true
    data_cache.weights = {}
    data_cache.learned_path = rime_api.get_user_data_dir() .. "/" .. LEARN_FILE

    local fh = io.open(data_cache.learned_path, "r")
    if not fh then
        return
    end

    for line in fh:lines() do
        if line ~= "" and line:sub(1, 1) ~= "#" then
            local code, text, score = line:match("^([^\t]+)\t([^\t]+)\t(-?%d+)$")
            if code and text and score then
                data_cache.weights[get_weight_key(code, text)] = tonumber(score) or 0
            end
        end
    end
    fh:close()
end

local function append_weight_delta(code, text, delta)
    M.ensure_loaded()

    local key = get_weight_key(code, text)
    data_cache.weights[key] = (data_cache.weights[key] or 0) + delta

    local path = data_cache.learned_path
    local exists = io.open(path, "r")
    if exists then
        exists:close()
    else
        local init_fh = io.open(path, "w")
        if init_fh then
            init_fh:write("# code\ttext\tscore\n")
            init_fh:close()
        end
    end

    local append_fh = io.open(path, "a")
    if append_fh then
        append_fh:write(string.lower(code or ""), "\t", text or "", "\t", tostring(delta), "\n")
        append_fh:close()
    end
end

function M.get_weight(code, text)
    M.ensure_loaded()
    return data_cache.weights[get_weight_key(code, text)] or 0
end

function M.record_pending_selection(code, text)
    runtime_state.pending_selection = {
        code = string.lower(code or ""),
        text = text or "",
    }
end

function M.learn_from_commit(committed_text)
    local pending = runtime_state.pending_selection
    runtime_state.pending_selection = nil

    if not pending or pending.text == "" then
        return
    end

    if committed_text ~= pending.text then
        return
    end

    -- 明確選字後提高該碼對該詞的權重，讓常選詞更容易排前。
    append_weight_delta(pending.code, pending.text, 1)
end

function M.clear_pending()
    runtime_state.pending_selection = nil
end

return M
