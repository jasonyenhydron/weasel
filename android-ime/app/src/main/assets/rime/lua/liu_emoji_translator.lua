-- liu_emoji_translator.lua
-- 以匯出的 likeime_emoji.tsv 提供 emoji 查詢。
-- 查詢方式：,,e + 英文 tag 前綴，例如 ,,esmile。

local M = {}

local data_cache = {
    loaded = false,
    records = {},
}

local function unescape_field(text)
    return (
        text
        :gsub("\\t", "\t")
        :gsub("\\r", "\r")
        :gsub("\\n", "\n")
        :gsub("\\\\", "\\")
    )
end

local function split_tsv_line(line)
    local locale, tag, value = line:match("^([^\t]+)\t([^\t]+)\t([^\t]+)$")
    if not locale or not tag or not value then
        return nil
    end
    return locale, unescape_field(tag), unescape_field(value)
end

local function add_record(locale, tag, value)
    local locale_bucket = data_cache.records[locale]
    if not locale_bucket then
        locale_bucket = {}
        data_cache.records[locale] = locale_bucket
    end

    local tag_key = tag:lower()
    local tag_bucket = locale_bucket[tag_key]
    if not tag_bucket then
        tag_bucket = {
            tag = tag,
            values = {},
        }
        locale_bucket[tag_key] = tag_bucket
    end

    if not tag_bucket.values[value] then
        tag_bucket.values[value] = true
    end
end

local function load_data(env)
    local configured = env.engine.schema.config:get_string(env.name_space .. "/data")
    local file_name = configured ~= "" and configured or "likeime_emoji.tsv"
    local user_path = rime_api.get_user_data_dir() .. "/" .. file_name
    local shared_path = rime_api.get_shared_data_dir() .. "/" .. file_name

    local fh = io.open(user_path, "r")
    if not fh then
        fh = io.open(shared_path, "r")
    end

    data_cache.records = {}
    data_cache.loaded = true

    if not fh then
        log.warning("liu_emoji_translator: emoji data file not found.")
        return
    end

    for line in fh:lines() do
        if line ~= ""
            and line ~= "# LikeIME emoji export for Rime liur"
            and line ~= "# locale\\ttag\\tvalue"
        then
            local locale, tag, value = split_tsv_line(line)
            if locale and tag and value then
                add_record(locale, tag, value)
            end
        end
    end
    fh:close()
end

local function collect_matches(query, locale)
    local results = {}
    local seen = {}
    local locale_bucket = data_cache.records[locale]
    if not locale_bucket then
        return results
    end

    for key, item in pairs(locale_bucket) do
        if key:find(query, 1, true) == 1 then
            for value in pairs(item.values) do
                local dedupe_key = value .. "\t" .. item.tag
                if not seen[dedupe_key] then
                    seen[dedupe_key] = true
                    results[#results + 1] = {
                        value = value,
                        tag = item.tag,
                        exact = (key == query),
                    }
                end
            end
        end
    end

    table.sort(results, function(a, b)
        if a.exact ~= b.exact then
            return a.exact
        end
        if a.tag == b.tag then
            return a.value < b.value
        end
        return a.tag < b.tag
    end)

    return results
end

function M.init(env)
    env.max_candidates = env.engine.schema.config:get_int(env.name_space .. "/max_candidates")
    if not env.max_candidates or env.max_candidates <= 0 then
        env.max_candidates = 30
    end

    if not data_cache.loaded then
        load_data(env)
    end
end

function M.translator(input, seg, env)
    if not seg:has_tag("emoji_mode") then
        return
    end

    local query = (input or ""):lower()
    if query == "" then
        local hint = Candidate("emoji_hint", seg.start, seg._end, "請輸入英文標籤查詢 emoji", "")
        hint.preedit = "《Emoji》"
        yield(hint)
        return
    end

    local results = collect_matches(query, "en")
    local limit = math.min(#results, env.max_candidates)
    for i = 1, limit do
        local item = results[i]
        local cand = Candidate("emoji", seg.start, seg._end, item.value, item.tag)
        cand.preedit = "《Emoji》" .. input
        yield(cand)
    end
end

return M
