package com.ndxinvest.yjb.buy
import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
class TransactionUndoTest {
 private class F {
  val fund=BuyFund("1","17","040046","synthetic")
  var time=Instant.parse("2026-10-09T01:00:00Z");var current=BuyBaseline(BigDecimal("100"),BigDecimal("2"));var posts=0;var apply=true;var error=false
  val bs=object:BuyStorage {var rows=emptyList<BuyTransaction>();override suspend fun read()=BuyCodec.decode(BuyCodec.encode(rows));override suspend fun write(rows:List<BuyTransaction>){this.rows=rows}}
  val ss=object:SellStorage {var rows=emptyList<SellTransaction>();override suspend fun read()=SellCodec.decode(SellCodec.encode(rows));override suspend fun write(rows:List<SellTransaction>){this.rows=rows}}
  val us=object:UndoStorage {var rows=JsonArray(emptyList());override suspend fun read()=rows;override suspend fun write(rows:JsonArray){this.rows=Json.parseToJsonElement(rows.toString()).jsonArray}}
  val gateway=object:BuyGateway {
   override suspend fun current(f:BuyFund)=current
   override suspend fun officialNav(f:BuyFund,d:LocalDate)=BigDecimal("2")
   override fun writeAvailable()=true
   override fun normalizeTarget(t:BuyBaseline)=BuyBaseline(t.shares,t.unitCost)
   override fun matches(a:BuyBaseline,b:BuyBaseline)=a.equivalent(b)
   override suspend fun postFinal(f:BuyFund,t:BuyBaseline){posts++;if(apply)current=t;if(error)throw BuyFailure(BuyProblem.NETWORK)}
  }
  fun buys()=SingleBuyRepository(bs,gateway,BundledBuyCalendar::forCode,now={time},readback=PositionReadback {})
  fun sells()=SingleSellRepository(ss,gateway,now={time},readback=PositionReadback {})
  fun undo()=TransactionUndo(bs,ss,us,gateway,now={time},readback=PositionReadback {})
  suspend fun buy(sync:Boolean=true):BuyTransaction {val t=buys().create(BuyInput(fund,BigDecimal("10"),BigDecimal.ZERO,LocalDate.parse("2026-10-08"),CutoffType.BEFORE_1500));return if(sync){buys().confirmNav(t.id);buys().sync(t.id)}else t}
 }
 @Test fun syncedBuyRestoresExactOldHoldingThenHidesAndCannotRepeatPost()=runTest {
  val f=F();val t=f.buy();assertEquals(1,f.posts);val q=f.undo().quote(setOf("1"),t.id)
  assertTrue(f.undo().confirm(setOf("1"),q));assertTrue(t.baseline.equivalent(f.current));assertEquals(BuyStatus.CANCELLED,f.bs.read().single().status)
  assertEquals(listOf(t.id),f.undo().deletedIds(setOf("1")));assertEquals(2,f.posts)
  assertTrue(f.undo().reconcile(setOf("1"),t.id));assertEquals(2,f.posts);assertFalse(f.undo().blocked(f.fund))
 }
 @Test fun unsentRecordOnlyCancelsLocally()=runTest {
  val f=F();val t=f.buy(false);val q=f.undo().quote(setOf("1"),t.id);assertNull(q.restore)
  assertTrue(f.undo().confirm(setOf("1"),q));assertEquals(0,f.posts);assertEquals(BuyStatus.CANCELLED,f.bs.read().single().status)
 }
 @Test fun externalChangeBeforeOrAfterQuoteNeverOverwrites()=runTest {
  val f=F();val t=f.buy();val q=f.undo().quote(setOf("1"),t.id);f.current=BuyBaseline(BigDecimal("120"),BigDecimal("2"))
  assertFailsWith<UndoFailure>{f.undo().quote(setOf("1"),t.id)};assertFailsWith<UndoFailure>{f.undo().confirm(setOf("1"),q)};assertEquals(1,f.posts)
 }
 @Test fun laterSyncedBlocksButCompatibleUnsentBuyCanBeRetained()=runTest {
  val f=F();val t=f.buy();f.time=f.time.plusSeconds(1);f.buy(false)
  assertEquals(1,f.undo().quote(setOf("1"),t.id).pendingBuys.size);assertEquals(1,f.posts)
  val g=F();val first=g.buy();g.time=g.time.plusSeconds(1);g.buy()
  assertFailsWith<UndoFailure>{g.undo().quote(setOf("1"),first.id)};assertEquals(2,g.posts)
 }
 @Test fun originalUncertainPostCannotBeDeleted()=runTest {
  val f=F();f.apply=false;val t=f.buy()
  assertEquals(BuyStatus.SYNCING,t.status);assertFailsWith<UndoFailure>{f.undo().quote(setOf("1"),t.id)};assertTrue(f.us.read().isEmpty())
 }
 @Test fun timeoutOnRollbackKeepsRecordAndBlocksAutomaticWriteAcrossRestart()=runTest {
  val f=F();val t=f.buy();val q=f.undo().quote(setOf("1"),t.id);f.apply=false;f.error=true
  assertFalse(f.undo().confirm(setOf("1"),q));assertEquals(BuyStatus.SYNCED,f.bs.read().single().status);assertTrue(f.undo().deletedIds(setOf("1")).isEmpty());assertTrue(f.undo().blocked(f.fund))
  assertFalse(f.undo().reconcile(setOf("1"),t.id));assertFailsWith<UndoFailure>{f.undo().quote(setOf("1"),t.id)};assertEquals(2,f.posts)
  f.current=q.restore!!;assertTrue(f.undo().reconcile(setOf("1"),t.id));assertEquals(2,f.posts);assertFalse(f.undo().blocked(f.fund))
 }
 @Test fun deliveredRollbackTimeoutIsGetVerified()=runTest {
  val f=F();val t=f.buy();val q=f.undo().quote(setOf("1"),t.id);f.error=true
  assertTrue(f.undo().confirm(setOf("1"),q));assertEquals(BuyStatus.CANCELLED,f.bs.read().single().status);assertEquals(2,f.posts)
 }
 @Test fun wrongAccountCannotQuoteOrReconcile()=runTest {
  val f=F();val t=f.buy();assertFailsWith<UndoFailure>{f.undo().quote(setOf("2"),t.id)};assertFailsWith<UndoFailure>{f.undo().reconcile(setOf("2"),t.id)};assertEquals(1,f.posts)
 }
 @Test fun sellRollbackRestoresSoldSharesAndOriginalCost()=runTest {
  val f=F();val t=f.sells().create(SellInput(f.fund,BigDecimal("10"),BigDecimal.ZERO,LocalDate.parse("2026-10-08"),CutoffType.BEFORE_1500));f.sells().confirmNav(t.id);f.sells().sync(t.id)
  val q=f.undo().quote(setOf("1"),t.id);assertTrue(f.undo().confirm(setOf("1"),q));assertTrue(t.baseline.equivalent(f.current));assertEquals(BuyStatus.CANCELLED,f.ss.read().single().status)
 }
 @Test fun rejectedConfirmationNeverDispatchesWhenFrozenQuoteChanged()=runTest {
  val f=F();val t=f.buy();val q=f.undo().quote(setOf("1"),t.id)
  assertFailsWith<UndoFailure>{f.undo().confirm(setOf("1"),q.copy(restore=BuyBaseline(BigDecimal("1"),BigDecimal("1"))))};assertEquals(1,f.posts)
 }
 @Test fun incompleteUndoBlocksBothAutomaticAndManualQueue()=runTest {
  val f=F();val t=f.buy();val q=f.undo().quote(setOf("1"),t.id);f.apply=false;f.undo().confirm(setOf("1"),q)
  val later=f.buy(false);f.bs.write(f.bs.read().map {if(it.id==later.id)it.copy(autoSync=true)else it})
  val queue=OrdinaryTradeAutomation(f.bs,f.ss,f.buys(),f.sells(),undoBlocked=f.undo()::blocked)
  queue.run(setOf("1"));queue.run(setOf("1"),onlyId=later.id);assertEquals(2,f.posts)
 }
 @Test fun failedAuditSaveCannotDispatchRollback()=runTest {
  val f=F();val t=f.buy();val bad=object:UndoStorage {override suspend fun read()=JsonArray(emptyList());override suspend fun write(rows:JsonArray){throw BuyFailure(BuyProblem.STORAGE)}}
  val undo=TransactionUndo(f.bs,f.ss,bad,f.gateway,now={f.time},readback=PositionReadback {})
  val q=undo.quote(setOf("1"),t.id);assertFailsWith<BuyFailure>{undo.confirm(setOf("1"),q)};assertEquals(1,f.posts);assertEquals(BuyStatus.SYNCED,f.bs.read().single().status)
 }
 @Test fun rollbackRetainsWaitingBuyAndSafelyRebasesItsFutureWrite()=runTest {
  val f=F();val wrong=f.buy();f.time=f.time.plusSeconds(1);val keep=f.buy(false)
  val q=f.undo().quote(setOf("1"),wrong.id);assertEquals(listOf(keep),q.pendingBuys)
  assertTrue(f.undo().confirm(setOf("1"),q));val retained=f.bs.read().single {it.id==keep.id}
  assertEquals(keep.input,retained.input);assertEquals(keep.baseline,retained.baseline);assertEquals(keep.status,retained.status)
  assertTrue(retained.executionBaseline!!.equivalent(wrong.baseline));assertTrue(retained.queuePredecessors.contains("undo:"+wrong.id))
  assertEquals(listOf(wrong.id),f.undo().deletedIds(setOf("1")))
  f.buys().confirmNav(keep.id);assertEquals(BuyStatus.SYNCED,f.buys().sync(keep.id).status)
  assertTrue(f.current.equivalent(wrong.submittedTarget!!));assertEquals(3,f.posts)
 }
 @Test fun alreadyConfirmedButUnsentBuyKeepsSettlementWhileTargetIsRebased()=runTest {
  val f=F();val wrong=f.buy();f.time=f.time.plusSeconds(1);val keep=f.buy(false);val ready=f.buys().confirmNav(keep.id)
  val q=f.undo().quote(setOf("1"),wrong.id);assertTrue(f.undo().confirm(setOf("1"),q));val retained=f.bs.read().single {it.id==keep.id}
  assertEquals(ready.confirmedShares,retained.confirmedShares);assertEquals(ready.feeAmount,retained.feeAmount);assertEquals(ready.confirmedNav,retained.confirmedNav)
  assertTrue(retained.target!!.shares<ready.target!!.shares);assertEquals(ready.input,retained.input)
 }
 @Test fun incompatiblePendingBaselineCannotBeSilentlyRebased()=runTest {
  val f=F();val wrong=f.buy();f.time=f.time.plusSeconds(1);val keep=f.buy(false)
  f.bs.write(f.bs.read().map {if(it.id==keep.id)it.copy(baseline=BuyBaseline(BigDecimal("999"),BigDecimal("2")))else it})
  assertFailsWith<UndoFailure>{f.undo().quote(setOf("1"),wrong.id)};assertEquals(1,f.posts)
 }
 @Test fun restartAfterLocalRebaseBeforeDoneReusesAuditWithoutAnotherPost()=runTest {
  val f=F();val wrong=f.buy();f.time=f.time.plusSeconds(1);val keep=f.buy(false)
  val failing=object:UndoStorage {
   override suspend fun read()=f.us.read()
   override suspend fun write(rows:JsonArray){if(rows.any {it.jsonObject["phase"]?.jsonPrimitive?.content=="DONE"})throw BuyFailure(BuyProblem.STORAGE);f.us.write(rows)}
  }
  val undo=TransactionUndo(f.bs,f.ss,failing,f.gateway,now={f.time},readback=PositionReadback {})
  val q=undo.quote(setOf("1"),wrong.id);assertFailsWith<BuyFailure>{undo.confirm(setOf("1"),q)}
  assertEquals(2,f.posts);assertTrue(f.undo().blocked(f.fund));assertTrue(f.undo().deletedIds(setOf("1")).isEmpty())
  assertTrue(f.undo().reconcile(setOf("1"),wrong.id));assertEquals(2,f.posts);assertFalse(f.undo().blocked(f.fund))
  assertEquals(keep.input,f.bs.read().single {it.id==keep.id}.input)
  assertTrue(f.bs.read().single {it.id==keep.id}.executionBaseline!!.equivalent(wrong.baseline))
 }
}
