package com.ndxinvest.app.yjb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import org.junit.Test

class RefreshBatchTest {
    @Test fun allChecksFinishBeforeSingleSuccess()=runTest {
        val order=mutableListOf<String>()
        val result=RefreshBatch.run({order+="read";""},{order+="check";NativeDcaAutomation.CheckResult(holdingsChanged=true)},{changed->assertTrue(changed);order+="verify";""})
        assertTrue(result.passed);assertEquals(listOf("read","check","verify"),order)
    }
    @Test fun checkFailureIsRetainedEvenWhenFinalReadSucceeds()=runTest {
        val result=RefreshBatch.run({""},{NativeDcaAutomation.CheckResult(false,true,"定投同步失败")},{assertTrue(it);""})
        assertFalse(result.passed);assertEquals(listOf("定投同步失败"),result.errors)
    }
    @Test fun initialReadFailureSkipsTradingCheckAndKeepsFailure()=runTest {
        val result=RefreshBatch.run({"持仓读取失败"},{error("must not execute")},{assertFalse(it);""})
        assertFalse(result.passed)
    }
    @Test fun finalReadAndLocalStorageErrorsPreventSuccess()=runTest {
        val result=RefreshBatch.run({""},{NativeDcaAutomation.CheckResult()},{throw IllegalStateException("private server body")})
        assertEquals(listOf("本地记录或最终持仓读取失败"),result.errors)
    }
    @Test fun normalNoDueOrNoNavCheckIsSuccessfulWithoutChangedHoldings()=runTest {
        val result=RefreshBatch.run({""},{NativeDcaAutomation.CheckResult()},{assertFalse(it);""})
        assertTrue(result.passed)
    }
    @Test fun cancellationPropagatesWithoutFalseResult()=runTest {
        assertFailsWith<CancellationException> {RefreshBatch.run({throw CancellationException()},{error("must not execute")},{""})}
    }
}
