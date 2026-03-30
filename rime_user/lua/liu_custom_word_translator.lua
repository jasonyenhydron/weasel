-- liu_custom_word_translator.lua
-- 自定詞翻譯器：讀取 openxiami_CustomWord.dict.yaml 並產生候選。
-- 支援 source 與 score 排序，避免冷門字因檔案順序被排到前面。

local trie = nil
local exact_matches = nil

local MIN_COMPLETION_LEN = 4
local MAX_COMPLETION_RESULTS = 10

local function new_node()
    return { children = {}, words = nil, completions = nil, completion_keys = nil }
end

local function source_rank(source_name)
    if source_name == "custom_user" then
        return 0
    end
    return 1
end

local function compare_items(a, b)
    if a.source_rank ~= b.source_rank then
        return a.source_rank < b.source_rank
    end
    if a.score ~= b.score then
        return a.score > b.score
    end
    if a.code ~= b.code then
        return a.code < b.code
    end
    return a.text < b.text
end

local function append_completion(node, item)
    local completions = node.completions
    local completion_keys = node.completion_keys
    if not completions then
        completions = {}
        node.completions = completions
    end
    if not completion_keys then
        completion_keys = {}
        node.completion_keys = completion_keys
    end

    local completion_key = item.code .. "\t" .. item.text
    if completion_keys[completion_key] then
        return
    end
    completion_keys[completion_key] = true
    completions[#completions + 1] = item
    table.sort(completions, compare_items)
    while #completions > MAX_COMPLETION_RESULTS do
        local removed = table.remove(completions)
        completion_keys[removed.code .. "\t" .. removed.text] = nil
    end
end

local function trie_insert(root, item)
    local node = root
    for i = 1, #item.code do
        append_completion(node, item)
        local char = item.code:sub(i, i)
        if not node.children[char] then
            node.children[char] = new_node()
        end
        node = node.children[char]
    end

    if not node.words then
        node.words = {}
    end
    node.words[#node.words + 1] = item
    table.sort(node.words, compare_items)
end

local function trie_find_node(root, prefix)
    local node = root
    for i = 1, #prefix do
        local char = prefix:sub(i, i)
        if not node.children[char] then
            return nil
        end
        node = node.children[char]
    end
    return node
end

local function parse_entry_line(line)
    local word, code, source_name, score = line:match("^([^\t]+)\t([^\t]+)\t([^\t]+)\t(-?%d+)$")
    if not word or not code then
        word, code = line:match("^([^\t]+)\t([^\t]+)")
    end
    if not word or not code then
        return nil
    end

    local code_lower = code:lower()
    local source_value = source_name or "custom"
    local score_value = tonumber(score) or 0
    return {
        text = word,
        code = code_lower,
        source = source_value,
        source_rank = source_rank(source_value),
        score = score_value,
    }
end

local function load_custom_words()
    if trie then
        return trie, exact_matches
    end

    trie = new_node()
    exact_matches = {}

    local user_dir = rime_api and rime_api.get_user_data_dir and rime_api.get_user_data_dir() or ""
    local shared_dir = rime_api and rime_api.get_shared_data_dir and rime_api.get_shared_data_dir() or ""

    local paths = {}
    if user_dir ~= "" then
        paths[#paths + 1] = user_dir .. "/openxiami_CustomWord.dict.yaml"
    end
    if shared_dir ~= "" then
        paths[#paths + 1] = shared_dir .. "/openxiami_CustomWord.dict.yaml"
    end

    for _, path in ipairs(paths) do
        local file = io.open(path, "r")
        if file then
            local in_data = false
            for line in file:lines() do
                line = line:gsub("\r$", "")
                if line == "..." then
                    in_data = true
                elseif in_data and #line > 0 and line:byte(1) ~= 35 then
                    local item = parse_entry_line(line)
                    if item then
                        trie_insert(trie, item)
                        local list = exact_matches[item.code]
                        if not list then
                            list = {}
                            exact_matches[item.code] = list
                        end
                        list[#list + 1] = item
                    end
                end
            end
            file:close()

            for _, list in pairs(exact_matches) do
                table.sort(list, compare_items)
            end
            break
        end
    end

    return trie, exact_matches
end

local function get_next_char_hint(item, input_len)
    if #item.code > input_len then
        local next_char = item.code:sub(input_len + 1, input_len + 1)
        local source_hint = item.source == "custom_user" and "U" or "C"
        return "▸⟨" .. next_char .. "⟩ " .. source_hint .. ":" .. tostring(item.score)
    end
    return item.source == "custom_user" and "U:" .. tostring(item.score) or "C:" .. tostring(item.score)
end

local function translator(input, seg, env)
    if seg:has_tag("abc") or seg:has_tag("mkst") then
        local root, matches = load_custom_words()
        local input_lower = input:lower()
        local input_len = #input_lower
        local start, _end = seg.start, seg._end

        local exact_list = matches[input_lower]
        if exact_list then
            for i = 1, #exact_list do
                local item = exact_list[i]
                local cand = Candidate("custom", start, _end, item.text, "")
                cand.quality = 999 - i
                yield(cand)
            end
        end

        if input_len >= MIN_COMPLETION_LEN then
            local node = trie_find_node(root, input_lower)
            if node and node.completions then
                for _, item in ipairs(node.completions) do
                    if #item.code > input_len then
                        local hint = get_next_char_hint(item, input_len)
                        local cand = Candidate("custom_completion", start, _end, item.text, hint)
                        cand.quality = 500 + math.max(0, 100 - item.source_rank * 50 - math.min(item.score, 50))
                        yield(cand)
                    end
                end
            end
        end
    end
end

return translator
