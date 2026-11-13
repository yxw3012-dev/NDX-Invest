package com.ndxinvest.app.yjb

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ndxinvest.yjb.read.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only schema discovery. No user identity, token, holdings or response values are printed. */
@RunWith(AndroidJUnit4::class)
class NavReadProtocolProbe {
    @Test fun historyNavSchemaOnly() = runBlocking {
        val storage=AndroidYjbStorage(InstrumentationRegistry.getInstrumentation().targetContext)
        val token=storage.token()?:error("NO_SESSION")
        try {
            val raw=storage.snapshot()?:error("NO_SNAPSHOT")
            val fund=raw["groups"]!!.jsonArray.flatMap {it.jsonObject["funds"]!!.jsonArray}.first().jsonObject["fund_id"]!!.jsonPrimitive.content
            val api=YjbReadApi(HttpsYjbWire(),storage::protocolKey)
            val history=api.call("/market/v1/fund-nav/fund-history-nav?fund_id=$fund",token)
            fun shape(e:JsonElement,depth:Int=0):String=when {
                depth>4->"..."
                e is JsonObject->e.entries.joinToString(prefix="{",postfix="}"){(k,v)->k.filter {it.isLetterOrDigit()||it=='_'}+":"+shape(v,depth+1)}
                e is JsonArray->"array(${e.size}):"+(e.firstOrNull()?.let {shape(it,depth+1)}?:"empty")
                e is JsonNull->"null"
                else->if((e as JsonPrimitive).isString)"string" else "number-or-bool"
            }
            println("NAV_SCHEMA_ONLY="+shape(history).take(3000))
            org.junit.Assert.assertTrue("Official date format must be ISO date",history.jsonObject["list"]!!.jsonArray.all {runCatching {java.time.LocalDate.parse(it.jsonObject["day"]!!.jsonPrimitive.content)}.isSuccess})
        }finally {token.fill('\u0000')}
    }
}
