-- liu_custom_word_filter.lua
-- 自訂詞候選排序：
-- 1. 同為加詞候選時，優先顯示字數較短的詞
-- 2. 你明確選過的加詞，會持續累積權重並往前排
-- 3. 學習只作用於 openxiami_CustomWord.dict.yaml 產生的候選，不影響主字典

local common = require("liu_common")
local learning = require("liu_custom_word_learning")

local is_kana = common.is_kana
local is_extended_charset = common.is_extended_charset
local MAX_BUFFER = 30

local function utf8_len(text)
    local len = utf8.len(text or "")
    if len then
        return len
    end
    return #(text or "")
end

local function is_ascii_english(text)
    local len = #text
    if len == 0 or string.byte(text, 1) > 127 then return false end
    local has_letter = false
    for i = 1, len do
        local b = string.byte(text, i)
        if b > 126 or b < 32 then return false end
        if not has_letter and ((b >= 65 and b <= 90) or (b >= 97 and b <= 122)) then
            has_letter = true
        end
    end
    return has_letter
end

local function get_cjk_type(text)
    for _, code in utf8.codes(text) do
        if is_extended_charset(code) then return 2 end
        if is_kana(code) then return 1 end
    end
    return 0
end

local function is_completion_candidate(cand)
    local comment = cand.comment or ""
    return comment ~= "" and (comment:sub(1, 1) == "~" or comment:find("▸", 1, true))
end

local function sort_custom_candidates(cands, input_code)
    table.sort(cands, function(a, b)
        local a_len = utf8_len(a.text)
        local b_len = utf8_len(b.text)
        if a_len ~= b_len then
            return a_len < b_len
        end

        local a_weight = learning.get_weight(input_code, a.text)
        local b_weight = learning.get_weight(input_code, b.text)
        if a_weight ~= b_weight then
            return a_weight > b_weight
        end

        local a_quality = a.quality or 0
        local b_quality = b.quality or 0
        if a_quality ~= b_quality then
            return a_quality > b_quality
        end

        return a.text < b.text
    end)
end

local function filter(input, env)
    learning.ensure_loaded()

    local xiami_exact = {}
    local custom_exact = {}
    local custom_completion = {}
    local kana_cands = {}
    local ext_cands = {}
    local english_cands = {}
    local exact_flushed = false
    local show_extended = env.engine.context:get_option("extended_charset")
    local input_code = string.lower(env.engine.context.input or "")

    for cand in input:iter() do
        local ctype = cand.type

        if ctype == "custom" then
            if exact_flushed then
                yield(cand)
            else
                custom_exact[#custom_exact + 1] = cand
            end
        elseif ctype == "custom_completion" then
            if #custom_completion < MAX_BUFFER then
                custom_completion[#custom_completion + 1] = cand
            end
        elseif is_ascii_english(cand.text) then
            if #english_cands < MAX_BUFFER then
                english_cands[#english_cands + 1] = cand
            end
        else
            local cjk_type = get_cjk_type(cand.text)

            if cjk_type == 2 then
                if show_extended and #ext_cands < MAX_BUFFER then
                    ext_cands[#ext_cands + 1] = cand
                end
            elseif cjk_type == 1 then
                if #kana_cands < MAX_BUFFER then
                    kana_cands[#kana_cands + 1] = cand
                end
            else
                if is_completion_candidate(cand) then
                    if not exact_flushed then
                        sort_custom_candidates(custom_exact, input_code)
                        exact_flushed = true
                        for i = 1, #xiami_exact do yield(xiami_exact[i]) end
                        xiami_exact = {}
                        for i = 1, #custom_exact do yield(custom_exact[i]) end
                        custom_exact = {}
                    end
                    yield(cand)
                else
                    if exact_flushed then
                        yield(cand)
                    else
                        xiami_exact[#xiami_exact + 1] = cand
                    end
                end
            end
        end
    end

    sort_custom_candidates(custom_exact, input_code)
    sort_custom_candidates(custom_completion, input_code)

    for i = 1, #xiami_exact do yield(xiami_exact[i]) end
    for i = 1, #custom_exact do yield(custom_exact[i]) end
    for i = 1, #custom_completion do yield(custom_completion[i]) end
    for i = 1, #kana_cands do yield(kana_cands[i]) end
    for i = 1, #ext_cands do yield(ext_cands[i]) end
    for i = 1, #english_cands do yield(english_cands[i]) end
end

return filter
