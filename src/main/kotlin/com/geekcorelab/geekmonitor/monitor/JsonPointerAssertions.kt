package com.geekcorelab.geekmonitor.monitor

import com.fasterxml.jackson.core.JsonPointer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

internal object JsonPointerAssertions {
    private val mapper = ObjectMapper()
    private val invalidEscape = Regex("~(?![01])")

    fun isValidPath(path: String): Boolean = path.startsWith("/") && !invalidEscape.containsMatchIn(path) &&
        runCatching { JsonPointer.compile(path) }.isSuccess

    fun isValidExpectedValue(value: String): Boolean = parse(value) != null

    fun isValidConfiguration(path: String?, expectedValue: String?): Boolean {
        if (path == null && expectedValue == null) return true
        if (path == null || expectedValue == null) return false
        return path.length <= 256 && isValidPath(path) && expectedValue.length <= 500 &&
            isValidExpectedValue(expectedValue)
    }

    fun matches(body: ByteArray, path: String, expectedValue: String): Boolean = runCatching {
        val pointer = JsonPointer.compile(path)
        val expected = parse(expectedValue) ?: return false
        val actual = parse(body.toString(Charsets.UTF_8))?.at(pointer) ?: return false
        !actual.isMissingNode && actual == expected
    }.getOrDefault(false)

    private fun parse(value: String): JsonNode? = runCatching {
        mapper.factory.createParser(value).use { parser ->
            val node = mapper.readTree<JsonNode>(parser) ?: return null
            if (parser.nextToken() != null) null else node
        }
    }.getOrNull()?.takeUnless { it.isMissingNode }
}
