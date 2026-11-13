package com.ndxinvest.yjb.read

import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class InitialContributionsTest {
    private val rows=Json.parseToJsonElement("""[{"id":"initial:YJB_IMPORT:17","kind":"INITIAL_POSITION","source":"YJB_IMPORT","fundId":"17","importedAt":"2026-01-01T01:00:00Z","initialShares":"100.00001","initialUnitCost":"10.12345678","initialHoldingCost":"1012.3457792345678","holdingStartDate":"2025-01-01"}]""").jsonArray
    private fun entries(date:String="2025-06-01",amount:String="1012.3457792345678")=Json.parseToJsonElement("""[{"date":"$date","amount":"$amount","importedAt":"must-not-change","type":"BUY"}]""").jsonArray
    @Test fun changesOnlyAuxiliaryDatesAmountsAndPreservesObservationFacts() {
        val result=InitialPositionStore.editContributions(rows,"initial:YJB_IMPORT:17",entries(),LocalDate.parse("2026-10-06"))
        val before=rows[0].jsonObject;val after=result[0].jsonObject
        for((key,value) in before)assertEquals(value,after[key])
        assertEquals(setOf("date","amount"),after["initialContributions"]!!.jsonArray[0].jsonObject.keys)
        assertEquals("1012.3457792345678",after["initialContributions"]!!.jsonArray[0].jsonObject["amount"]!!.jsonPrimitive.content)
    }
    @Test fun mismatchCanBeStoredAsDraftAndEmptyListClearsDetail() {
        val draft=InitialPositionStore.editContributions(rows,"initial:YJB_IMPORT:17",entries(amount="12.5"))
        assertEquals("12.5",draft[0].jsonObject["initialContributions"]!!.jsonArray[0].jsonObject["amount"]!!.jsonPrimitive.content)
        val cleared=InitialPositionStore.editContributions(draft,"initial:YJB_IMPORT:17",JsonArray(emptyList()))
        assertTrue(cleared[0].jsonObject["initialContributions"]!!.jsonArray.isEmpty());assertEquals(rows[0].jsonObject["importedAt"],cleared[0].jsonObject["importedAt"])
    }
    @Test fun rejectsAfterImportInvalidDatesNonpositiveAmountsAndUnexpectedIdentity() {
        for(input in listOf(entries("2026-01-02"),entries("2025-02-29"),entries("1899-12-31"),entries(amount="0"),entries(amount="-1"),entries(amount="NaN"),entries(amount="1e10"),entries(amount="0."+"1".repeat(33))))assertFails {InitialPositionStore.editContributions(rows,"initial:YJB_IMPORT:17",input)}
        assertFails {InitialPositionStore.editContributions(rows,"not-found",entries())};assertEquals(1,rows.size)
    }
    @Test fun fallbackHoldingDateCannotBeLaterThanImport() {
        assertFails {InitialPositionStore.edit(rows,"initial:YJB_IMPORT:17","2026-01-02",LocalDate.parse("2026-10-06"))}
        val updated=InitialPositionStore.edit(rows,"initial:YJB_IMPORT:17","2025-01-01",LocalDate.parse("2026-10-06"))
        assertEquals(rows[0].jsonObject["importedAt"],updated[0].jsonObject["importedAt"])
    }
    @Test fun repositorySaveAndRestartAreLocalOnlyAndFailureDoesNotPublishUnsavedData()=runTest {
        var stored=rows;var saveWorks=true;var calls=0
        val storage=object:YjbReadStorage {
            override suspend fun token():CharArray?=null
            override suspend fun saveToken(value:CharArray)=error("Must not change credentials")
            override suspend fun snapshot():JsonObject?=null
            override suspend fun saveSnapshot(value:JsonObject)=error("Must not change holdings")
            override suspend fun clear()=error("Must not disconnect")
            override suspend fun imports()=stored
            override suspend fun saveImports(value:JsonArray):Boolean {if(saveWorks)stored=value;return saveWorks}
        }
        val api=YjbReadApi(YjbWire{calls++;error("No network permitted")},{charArrayOf('x')})
        val emitted=mutableListOf<JsonObject>();val repo=YjbReadRepository(api,storage,emitted::add)
        repo.editInitialContributions("initial:YJB_IMPORT:17",entries())
        val saved=stored;assertEquals(saved,emitted.last()["initialPositions"])
        saveWorks=false;val before=emitted.size
        val failure=assertFailsWith<YjbFailure>{repo.editInitialContributions("initial:YJB_IMPORT:17",entries(amount="5"))}
        assertEquals(ReadError.STORAGE,failure.kind);assertEquals(before,emitted.size);assertEquals(saved,stored)
        saveWorks=true
        val restarted=YjbReadRepository(api,storage,emitted::add)
        restarted.editInitialContributions("initial:YJB_IMPORT:17",saved[0].jsonObject["initialContributions"]!!.jsonArray)
        assertEquals(saved,emitted.last()["initialPositions"]);assertEquals(rows[0].jsonObject["importedAt"],stored[0].jsonObject["importedAt"])
        assertEquals(0,calls)
    }
}
