package com.ndxinvest.network

import kotlin.test.*

class ScopedCredentialAccessTest {
    @Test fun scopedCopyIsWipedOnSuccessExceptionAndClose() {
        val credential=ResolvedCredential.from("fake-session".toCharArray())
        var copy:CharArray?=null
        credential.withCharacters { copy=it;assertEquals("fake-session",it.concatToString()) }
        assertTrue(copy!!.all { it=='\u0000' })
        assertFailsWith<IllegalStateException> { credential.withCharacters { copy=it;error("test") } }
        assertTrue(copy!!.all { it=='\u0000' })
        credential.close()
        assertFailsWith<IllegalStateException> { credential.withCharacters { } }
        assertEquals("[REDACTED]",credential.toString())
    }
}
