package com.ndxinvest.yjb.read

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

interface YjbReadStorage {
    suspend fun token(): CharArray?
    suspend fun saveToken(value: CharArray): Boolean
    suspend fun snapshot(): JsonObject?
    suspend fun saveSnapshot(value: JsonObject): Boolean
    suspend fun clear()
    suspend fun imports():JsonArray=JsonArray(emptyList())
    suspend fun saveImports(value:JsonArray):Boolean=true
}

/** No Room, Ledger, writes, worker or transaction execution dependency. */
class YjbReadRepository(private val api:YjbReadApi?,private val storage:YjbReadStorage,private val excludedImports:suspend()->Set<String> = {emptySet()},private val localSnapshot:(suspend ()->JsonObject)?=null,private val emit:(JsonObject)->Unit) {
    constructor(api:YjbReadApi,storage:YjbReadStorage,emit:(JsonObject)->Unit):this(api,storage,{emptySet()},null,emit)
    private var current:JsonObject?=null
    private var imports=JsonArray(emptyList())
    private var importsLoaded=false
    private suspend fun loadImports(){if(!importsLoaded){imports=storage.imports();importsLoaded=true}}
    suspend fun editHoldingStartDate(id:String,date:String) {
        operations.withLock {
            loadImports()
            val next=InitialPositionStore.edit(imports,id,date)
            if(!storage.saveImports(next))throw YjbFailure(ReadError.STORAGE)
            imports=next
            publish(lastAccount,lastHolding)
        }
    }
    suspend fun editInitialContributions(id:String,entries:JsonArray) {
        operations.withLock {
            loadImports()
            val next=InitialPositionStore.editContributions(imports,id,entries)
            if(!storage.saveImports(next))throw YjbFailure(ReadError.STORAGE)
            imports=next
            publish(lastAccount,lastHolding)
        }
    }
    private var lastAccount="NOT_CONNECTED"
    private var lastHolding="NOT_CONNECTED"
    private var epoch=0L
    private val operations=Mutex()
    suspend fun restore() {
        loadImports()
        if(localSnapshot!=null){refresh();return}
        current=try {storage.snapshot()?.let { YjbMapper.map(it) }}catch(_:Exception){null}
        val token=try{storage.token()}catch(_:Exception){null}
        if(token==null){current=null;publish("NOT_CONNECTED","NOT_CONNECTED");return}
        token.fill('\u0000')
        publish("CONNECTED",if(current==null)"INITIAL_LOADING" else "OFFLINE_CACHE")
        refresh()
    }
    fun connecting() { epoch++;publish("CONNECTING",if(current==null)"INITIAL_LOADING" else "REFRESHING") }
    fun loginFailed() { publish("LOGIN_FAILED",if(current==null)"ERROR" else "OFFLINE_CACHE","登录失败，请重试。") }
    suspend fun acceptLogin(token:CharArray) {
        val saved=try {
            epoch++
            operations.withLock {
                // Never reuse another account's last successful snapshot after a new login.
                storage.clear();current=null;storage.saveToken(token)
            }
        }catch(e:CancellationException){throw e}catch(_:Exception){false}finally{token.fill('\u0000')}
        if(!saved){loginFailed();return}
        publish("CONNECTED","INITIAL_LOADING");refresh()
    }
    suspend fun refresh() {
        if(!operations.tryLock())return
        val version=epoch
        try {
            val token=if(localSnapshot==null) storage.token()?:run{publish("NOT_CONNECTED","NOT_CONNECTED");return} else null
            publish("CONNECTED",if(current==null)"INITIAL_LOADING" else "REFRESHING")
            try {
                val raw=localSnapshot?.invoke()?:requireNotNull(api).snapshot(requireNotNull(token));val mapped=YjbMapper.map(raw)
                if(version!=epoch)return
                // Cache writes are awaited before disconnect can erase them.
                loadImports()
                val nextImports=InitialPositionStore.import(imports,raw,excludedImports())
                if(nextImports!=imports&&!storage.saveImports(nextImports))throw YjbFailure(ReadError.STORAGE)
                imports=nextImports
                val cached=localSnapshot!=null||storage.saveSnapshot(raw)
                if(version!=epoch)return
                current=mapped;publish("CONNECTED",if(mapped["holdings"]!!.jsonArray.isEmpty())"EMPTY" else "READY",if(cached)"" else "本次数据已获取，但缓存保存失败。")
            } catch(e:YjbFailure) {
                if(version!=epoch)return
                if(e.kind==ReadError.AUTH_EXPIRED)publish("AUTH_EXPIRED","AUTH_EXPIRED","养基宝登录已过期")
                else publish("CONNECTED",if(current!=null)"OFFLINE_CACHE" else "ERROR",if(e.kind==ReadError.RATE_LIMITED)"养基宝请求频繁，已暂停请求，请稍后重试。" else if(localSnapshot!=null)"暂时无法读取本地持仓，请重试。" else "暂时无法读取养基宝，请重试。")
            } catch(e:CancellationException) { throw e
            } catch(_:Exception) {
                if(version==epoch)publish("CONNECTED",if(current!=null)"OFFLINE_CACHE" else "ERROR",if(localSnapshot!=null)"暂时无法读取本地持仓，请重试。" else "暂时无法读取养基宝，请重试。")
            } finally { token?.fill('\u0000') }
        } catch(e:CancellationException) { throw e
        } catch(_:Exception) { if(version==epoch)publish("LOGIN_FAILED",if(current!=null)"OFFLINE_CACHE" else "ERROR","无法读取安全会话，请重新登录。")
        } finally { operations.unlock() }
    }
    suspend fun disconnect() {
        epoch++
        operations.withLock {
            current=null
            try{storage.clear();publish("NOT_CONNECTED","NOT_CONNECTED")}
            catch(e:CancellationException){throw e}
            catch(_:Exception){publish("LOGIN_FAILED","ERROR","安全会话未能清除，请重试断开连接。")}
        }
    }
    private fun publish(accountStatus:String,holdingStatus:String,error:String="") {
        lastAccount=accountStatus;lastHolding=holdingStatus
        val data=current ?: buildJsonObject {
            put("fundCatalog",JsonArray(emptyList()));put("holdings",JsonArray(emptyList()));put("details",JsonArray(emptyList()));put("overview",buildJsonObject {put("amount","--");put("metrics",JsonArray(List(4){JsonPrimitive("--")}))})
            put("displayName","");put("maskedId","");put("lastSyncAt","")
        }
        emit(buildJsonObject {put("data",data);put("initialPositions",imports);put("accountStatus",accountStatus);put("holdingsStatus",holdingStatus);put("errorMessage",error)})
    }
}
