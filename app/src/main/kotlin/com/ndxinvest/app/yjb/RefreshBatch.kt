package com.ndxinvest.app.yjb

import kotlinx.coroutines.CancellationException

/** One result for the user gesture; intermediate read/check results remain internal. */
internal object RefreshBatch {
    data class Result(val errors:List<String>) { val passed get()=errors.isEmpty() }
    suspend fun run(read:suspend ()->String,check:suspend ()->NativeDcaAutomation.CheckResult,
                    finalRead:suspend (Boolean)->String):Result {
        val errors=mutableListOf<String>()
        suspend fun stage(fallback:String,block:suspend ()->String) {
            try {block().takeIf {it.isNotBlank()}?.let {errors+=it}}
            catch(e:CancellationException){throw e}
            catch(_:Exception){errors+=fallback}
        }
        stage("持仓读取失败，请稍后重试",read)
        var changed=false
        if(errors.isEmpty())stage("定投检查失败，请稍后重试") {
            val result=check();changed=result.holdingsChanged
            if(result.passed)"" else result.error.ifBlank {"定投检查未完成"}
        }
        stage("本地记录或最终持仓读取失败") {finalRead(changed)}
        return Result(errors.distinct())
    }
}
