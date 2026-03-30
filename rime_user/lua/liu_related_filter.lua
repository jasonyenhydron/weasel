-- liu_related_filter.lua
-- 依 LikeIME related 資料重新排序候選，並將使用者實際上屏結果即時學習到覆蓋檔。

local M = {}

local data_cache = {
    loaded = false,
    map = {},
    overlay_map = {},
    base_path = nil,
    overlay_path = nil,
}

local function safe_trim(text)
    if not text then
        return ""
    end
    return (text:gsub("^%s+", ""):gsub("%s+$", ""))
end

local function unescape_field(text)
    return (
        text
        :gsub("\\t", "\t")
        :gsub("\\r", "\r")
        :gsub("\\n", "\n")
        :gsub("\\\\", "\\")
    )
end

local function escape_field(text)
    return (
        text
        :gsub("\\", "\\\\")
        :gsub("\t", "\\t")
        :gsub("\r", "\\r")
        :gsub("\n", "\\n")
    )
end

local function split_tsv_line(line)
    local pword, cword, score = line:match("^([^\t]+)\t([^\t]+)\t(-?%d+)$")
    if not pword or not cword or not score then
        return nil
    end
    return unescape_field(pword), unescape_field(cword), tonumber(score)
end

local function build_data_path(env)
    local configured = env.engine.schema.config:get_string(env.name_space .. "/data")
    local file_name = configured ~= "" and configured or "likeime_related.tsv"
    local user_dir = rime_api.get_user_data_dir()
    local shared_dir = rime_api.get_shared_data_dir()
    local base_user_path = user_dir .. "/" .. file_name
    local base_shared_path = shared_dir .. "/" .. file_name
    local overlay_path = base_user_path .. ".user"
    return base_user_path, base_shared_path, overlay_path
end

local function add_bucket_score(target_map, pword, cword, score)
    local bucket = target_map[pword]
    if not bucket then
        bucket = {}
        target_map[pword] = bucket
    end
    bucket[cword] = score
end

local function load_tsv_to_map(path, target_map)
    local fh = io.open(path, "r")
    if not fh then
        return false
    end

    for line in fh:lines() do
        if line ~= ""
            and line:sub(1, 1) ~= "#"
        then
            local pword, cword, score = split_tsv_line(line)
            if pword and cword then
                add_bucket_score(target_map, pword, cword, score)
            end
        end
    end
    fh:close()
    return true
end

local function load_related_data(env)
    local user_path, shared_path, overlay_path = build_data_path(env)
    local related_map = {}
    local overlay_map = {}

    data_cache.base_path = user_path
    data_cache.overlay_path = overlay_path

    if not load_tsv_to_map(user_path, related_map) then
        load_tsv_to_map(shared_path, related_map)
    end
    load_tsv_to_map(overlay_path, overlay_map)

    for pword, bucket in pairs(overlay_map) do
        for cword, score in pairs(bucket) do
            add_bucket_score(related_map, pword, cword, score)
        end
    end

    data_cache.loaded = true
    data_cache.map = related_map
    data_cache.overlay_map = overlay_map
end

local function persist_overlay()
    if not data_cache.overlay_path then
        return
    end

    local tmp_path = data_cache.overlay_path .. ".tmp"
    local fh = io.open(tmp_path, "w")
    if not fh then
        log.warning("liu_related_filter: unable to write overlay file.")
        return
    end

    fh:write("# LikeIME related user learning overlay\n")
    fh:write("# pword\\tcword\\tscore\n")

    local rows = {}
    for pword, bucket in pairs(data_cache.overlay_map) do
        for cword, score in pairs(bucket) do
            rows[#rows + 1] = {
                pword = pword,
                cword = cword,
                score = score,
            }
        end
    end

    table.sort(rows, function(a, b)
        if a.pword == b.pword then
            if a.score == b.score then
                return a.cword < b.cword
            end
            return a.score > b.score
        end
        return a.pword < b.pword
    end)

    for _, row in ipairs(rows) do
        fh:write(
            escape_field(row.pword)
                .. "\t"
                .. escape_field(row.cword)
                .. "\t"
                .. tostring(row.score)
                .. "\n"
        )
    end
    fh:close()

    os.remove(data_cache.overlay_path)
    os.rename(tmp_path, data_cache.overlay_path)
end

local function get_latest_committed_text(env)
    if env.last_committed_text and env.last_committed_text ~= "" then
        return env.last_committed_text
    end

    local context = env.engine.context
    if context.commit_history and not context.commit_history:empty() then
        local latest = context.commit_history:latest_text()
        if latest and latest ~= "" then
            return latest
        end
    end

    return ""
end

local function sanitize_comment(comment)
    if not comment or comment == "" then
        return ""
    end

    local cleaned = comment
        :gsub("%s*[〔［%[]聯想[〕］%]]", "")
        :gsub("%s*聯想", "")
        :gsub("^%s+", "")
        :gsub("%s+$", "")

    return cleaned
end

local function sanitize_candidate(cand)
    local comment = sanitize_comment(cand.comment or "")
    if comment == (cand.comment or "") then
        return cand
    end

    return cand:to_shadow_candidate(cand.type, cand.text, comment)
end

local function should_bypass(context)
    local input_text = context.input or ""
    if input_text == "" then
        return true
    end

    local first_char = input_text:sub(1, 1)
    if first_char == ";" or first_char == "`" or first_char == "'" or first_char == "," then
        return true
    end

    if context:get_option("ascii_mode") then
        return true
    end

    return false
end

local function should_learn(text)
    -- 避免將空白、超長字串或控制序列寫回聯想表。
    if not text or text == "" then
        return false
    end
    if text:find("[%c]") then
        return false
    end
    return utf8.len(text) ~= nil and utf8.len(text) <= 32
end

local function learn_related_pair(previous_text, current_text)
    if previous_text == current_text then
        return
    end

    local base_bucket = data_cache.map[previous_text]
    if not base_bucket then
        base_bucket = {}
        data_cache.map[previous_text] = base_bucket
    end

    local overlay_bucket = data_cache.overlay_map[previous_text]
    if not overlay_bucket then
        overlay_bucket = {}
        data_cache.overlay_map[previous_text] = overlay_bucket
    end

    local next_score = (overlay_bucket[current_text] or base_bucket[current_text] or 0) + 1
    overlay_bucket[current_text] = next_score
    base_bucket[current_text] = next_score
    persist_overlay()
end

function M.init(env)
    env.last_committed_text = ""
    env.max_promoted = env.engine.schema.config:get_int(env.name_space .. "/max_promoted")
    if not env.max_promoted or env.max_promoted <= 0 then
        env.max_promoted = 8
    end

    if not data_cache.loaded then
        load_related_data(env)
    end

    env.commit_notifier = env.engine.context.commit_notifier:connect(function(ctx)
        local current_text = safe_trim(ctx:get_commit_text())
        local previous_text = safe_trim(env.last_committed_text)
        if should_learn(previous_text) and should_learn(current_text) then
            learn_related_pair(previous_text, current_text)
        end
        env.last_committed_text = current_text
    end)
end

function M.fini(env)
    if env.commit_notifier then
        env.commit_notifier:disconnect()
        env.commit_notifier = nil
    end
end

function M.func(input, env)
    local context = env.engine.context

    local function yield_sanitized_all()
        for cand in input:iter() do
            yield(sanitize_candidate(cand))
        end
    end

    if should_bypass(context) then
        yield_sanitized_all()
        return
    end

    local previous_text = safe_trim(get_latest_committed_text(env))
    if previous_text == "" then
        yield_sanitized_all()
        return
    end

    local related_bucket = data_cache.map[previous_text]
    if not related_bucket then
        yield_sanitized_all()
        return
    end

    local promoted = {}
    local ordinary = {}
    for cand in input:iter() do
        cand = sanitize_candidate(cand)
        local score = related_bucket[cand.text]
        if score then
            promoted[#promoted + 1] = {
                candidate = cand,
                score = score,
            }
        else
            ordinary[#ordinary + 1] = cand
        end
    end

    table.sort(promoted, function(a, b)
        if a.score == b.score then
            return a.candidate.text < b.candidate.text
        end
        return a.score > b.score
    end)

    local promoted_count = math.min(#promoted, env.max_promoted)
    for idx = 1, promoted_count do
        yield(promoted[idx].candidate)
    end

    for idx = promoted_count + 1, #promoted do
        ordinary[#ordinary + 1] = promoted[idx].candidate
    end

    for _, cand in ipairs(ordinary) do
        yield(cand)
    end
end

return {
    liu_related_filter = {
        init = M.init,
        func = M.func,
        fini = M.fini,
    }
}
