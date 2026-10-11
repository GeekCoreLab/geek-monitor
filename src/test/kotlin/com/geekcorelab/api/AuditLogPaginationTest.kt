package com.geekcorelab.api

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class AuditLogPaginationTest {
    @Test
    fun `exact final page has no next cursor`() {
        val entries = (1..2).map(::entry)

        val page = auditLogPage(entries, limit = 2)

        assertEquals(entries, page.items)
        assertNull(page.next)
    }

    @Test
    fun `next cursor points to last returned row only when another row exists`() {
        val entries = (1..3).map(::entry)

        val page = auditLogPage(entries, limit = 2)

        assertEquals(entries.take(2), page.items)
        assertEquals(AuditLogCursor(entries[1].createdAt, entries[1].id), page.next)
    }

    private fun entry(index: Int) = AuditLogEntry(
        id = UUID.nameUUIDFromBytes("audit-$index".toByteArray()).toString(),
        createdAt = "2026-10-11T00:00:${index.toString().padStart(2, '0')}Z",
        actorEmail = "admin@example.com",
        action = "test.$index",
        subjectType = "test",
        subjectId = index.toString(),
        summary = ObjectMapper().createObjectNode(),
    )
}
