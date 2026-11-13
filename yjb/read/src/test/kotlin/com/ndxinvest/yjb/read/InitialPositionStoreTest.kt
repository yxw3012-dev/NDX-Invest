package com.ndxinvest.yjb.read

import kotlinx.serialization.json.*
import java.time.*
import kotlin.test.*

class InitialPositionStoreTest {
    private val raw=Json.parseToJsonElement("""{"groups":[{"funds":[{"fund_id":"1","hold_share":"10","hold_cost":"2"},{"fund_id":"2","hold_share":"20"},{"fund_id":"3","hold_share":"0"}]}],"quotes":[],"lastSyncAt":"2026-10-02T10:00:00Z"}""").jsonObject
    @Test fun firstThreeImportsRepeatAndRestartAreIdempotent() {
        val rows=InitialPositionStore.import(JsonArray(emptyList()),raw);assertEquals(3,rows.size)
        assertEquals(rows,InitialPositionStore.import(rows,raw))
        val restarted=Json.parseToJsonElement(rows.toString()).jsonArray
        assertEquals(rows,InitialPositionStore.import(restarted,raw))
        for(r in rows){assertEquals("INITIAL_POSITION",r.jsonObject.text("kind"));assertEquals("YJB_IMPORT",r.jsonObject.text("source"));assertFalse(r.jsonObject.containsKey("transactionStatus"));assertNull(r.jsonObject.text("holdingStartDate"))}
    }
    @Test fun dateEditPreservesImportFactAndStatisticsCanReadDate() {
        val rows=InitialPositionStore.import(JsonArray(emptyList()),raw);val row=rows.first().jsonObject
        assertNull(InitialPositionStore.holdingDays(row,Instant.parse("2026-10-02T10:00:00Z")))
        val edited=InitialPositionStore.edit(rows,row.text("id")!!,"2026-10-01",LocalDate.parse("2026-10-02"))
        assertEquals(row.text("importedAt"),edited.first().jsonObject.text("importedAt"));assertEquals(1L,InitialPositionStore.holdingDays(edited.first().jsonObject,Instant.parse("2026-10-02T10:00:00Z")))
        assertEquals(edited,InitialPositionStore.import(edited,raw))
        assertFailsWith<YjbFailure>{InitialPositionStore.edit(rows,row.text("id")!!,"2026-02-30")}
    }
}
