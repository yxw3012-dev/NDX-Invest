package com.ndxinvest.app.market

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AndroidMarketCacheTest {
    @Test fun atomicPublicSnapshotRestoresAndCorruptionCannotBecomeFacts()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val payload=buildJsonObject {put("version",1);put("publicFact","6.7050123456789")}
        val first=AndroidMarketCache(context)
        assertTrue(first.write(payload));assertEquals(payload,AndroidMarketCache(context).read())
        val file=File(context.noBackupFilesDir,"ndx-market-read-v1.json")
        file.writeText("{truncated")
        assertNull(AndroidMarketCache(context).read())
    }
}
