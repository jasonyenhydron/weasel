-- liu_emoji_translator.lua
-- 讀取匯出的 emoji TSV，提供 LikeIME 風格的 tag contains 查詢。

local M = {}

local cache = {}

local function unescape_field(text)
    return (
        text
        :gsub("\\t", "\t")
        :gsub("\\r", "\r")
        :gsub("\\n", "\n")
        :gsub("\\\\", "\\")
    )
end

local function load_tsv(path)
    local fh = io.open(path, "r")
    if not fh then
        return nil
    end

    local rows = {}
    for line in fh:lines() do
        if line ~= "" and line:sub(1, 1) ~= "#" then
            local tag, value = line:match("^([^\t]+)\t([^\t]+)$")
            if tag and value then
                rows[#rows + 1] = {
                    tag = unescape_field(tag),
                    value = unescape_field(value),
                }
            end
        end
    end
    fh:close()
    return rows
end

local function get_data_rows(env)
    local file_name = env.engine.schema.config:get_string(env.name_space .. "/data")
    if file_name == "" then
        file_name = "likeime_emoji_tw.tsv"
    end
    local cache_key = file_name
    if cache[cache_key] then
        return cache[cache_key]
    end

    local user_path = rime_api.get_user_data_dir() .. "/" .. file_name
    local shared_path = rime_api.get_shared_data_dir() .. "/" .. file_name
    local rows = load_tsv(user_path) or load_tsv(shared_path) or {}
    cache[cache_key] = rows
    return rows
end

function M.translator(input, seg, env)
    if not seg:has_tag("emoji_lookup") then
        return
    end

    local rows = get_data_rows(env)
    local start, _end = seg.start, seg._end
    local needle = string.lower(input or "")
    local limit = env.engine.schema.config:get_int(env.name_space .. "/limit")
    if not limit or limit <= 0 then
        limit = 12
    end

    if needle == "" then
        local hint = Candidate("emoji_hint", start, _end, "請輸入 emoji 關鍵字", "例如：臉、笑、face")
        hint.quality = 1000
        yield(hint)
        return
    end

    local count = 0
    for _, row in ipairs(rows) do
        if string.find(string.lower(row.tag), needle, 1, true) then
            local cand = Candidate("emoji_lookup", start, _end, row.value, row.tag)
            cand.quality = 800
            yield(cand)
            count = count + 1
            if count >= limit then
                break
            end
        end
    end

    if count == 0 then
        local empty = Candidate("emoji_hint", start, _end, "查無 emoji", "請改用其他關鍵字")
        empty.quality = 100
        yield(empty)
    end
end

return M
