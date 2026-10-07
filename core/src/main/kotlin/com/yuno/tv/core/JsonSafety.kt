package com.yuno.tv.core

import com.yuno.tv.core.model.ErrorCode

/** Bound recursion before any external JSON enters a recursive parser. */
fun ensureSafeJson(body: String, maximumDepth: Int = 64) {
    var depth = 0; var quoted = false; var escaped = false
    for (character in body) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> { depth++; if (depth > maximumDepth) throw SourceException(ErrorCode.CONFIG_FORMAT) }
            '}', ']' -> { depth--; if (depth < 0) throw SourceException(ErrorCode.CONFIG_FORMAT) }
        }
    }
}
