package com.ndxinvest.app.yjb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ndxinvest.app.BuildConfig
import com.ndxinvest.yjb.buy.BuyBaseline
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/** Explicit read-only restore check. No network, POST, transaction creation or history edits. */
@RunWith(AndroidJUnit4::class)
class WritePrecisionRestoreDeviceTest {
    @Test fun realEncryptedProofRestoresNativeProductionGate()=runBlocking {
        assumeTrue(BuildConfig.FLAVOR=="production" && InstrumentationRegistry.getArguments().getString("ndxCheckRealPrecision")=="READ_ONLY")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=AndroidWriteEvidenceStore(context)
        val proof=store.verified() ?: error("REAL_PRECISION_PROOF_MISSING")
        assertEquals(2,proof.profile.shareScale);assertEquals(8,proof.profile.unitCostScale)
        val before=AndroidBuyStorage(context).read()
        val writer=AndroidVerifiedYjbWriter(AndroidYjbStorage(context),store)
        writer.restore();assertTrue(writer.evidence.isNotBlank())
        val target=writer.normalize(BuyBaseline("100.12345678".toBigDecimal(),"1.123456789".toBigDecimal()))
        assertEquals("100.12",target.shares.toPlainString());assertEquals("1.12345679",target.unitCost.toPlainString())
        assertEquals(before,AndroidBuyStorage(context).read())
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
            putString("ndxPrecisionRestore","REAL_PROOF_RESTORED=true SHARE_SCALE=2 COST_SCALE=8 NO_NETWORK=true BUY_HISTORY_UNCHANGED=true")
        })
    }
}
