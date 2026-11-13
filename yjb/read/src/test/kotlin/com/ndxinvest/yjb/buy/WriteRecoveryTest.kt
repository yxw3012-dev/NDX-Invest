package com.ndxinvest.yjb.buy

import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class WriteRecoveryTest {
    private class F {
        val fund=BuyFund("1","17","040046","synthetic")
        var time=Instant.parse("2026-10-09T01:00:00Z")
        var position=BuyBaseline(BigDecimal("63.18"),BigDecimal("8.13549680"))
        var preflight:BuyProblem?=null;var postFailure:BuyProblem?=null
        var applyPost=true;var posts=0;var readsFail=false
        val targets=mutableListOf<BuyBaseline>()
        val bs=object:BuyStorage {var rows=emptyList<BuyTransaction>();override suspend fun read()=BuyCodec.decode(BuyCodec.encode(rows));override suspend fun write(rows:List<BuyTransaction>){this.rows=BuyCodec.decode(BuyCodec.encode(rows))}}
        val ss=object:SellStorage {var rows=emptyList<SellTransaction>();override suspend fun read()=SellCodec.decode(SellCodec.encode(rows));override suspend fun write(rows:List<SellTransaction>){this.rows=SellCodec.decode(SellCodec.encode(rows))}}
        val gateway=object:BuyGateway {
            override suspend fun current(f:BuyFund):BuyBaseline {if(readsFail)throw BuyFailure(BuyProblem.NETWORK);return position}
            override suspend fun officialNav(f:BuyFund,date:LocalDate)=BigDecimal("8.4736")
            override fun writeAvailable()=true
            override fun normalizeTarget(target:BuyBaseline)=BuyBaseline(target.shares.setScale(2),target.unitCost.setScale(8,java.math.RoundingMode.HALF_UP))
            override fun matches(a:BuyBaseline,b:BuyBaseline)=normalizeTarget(a).equivalent(normalizeTarget(b))
            override suspend fun postFinal(f:BuyFund,t:BuyBaseline){posts++;targets+=t;if(applyPost)position=t;postFailure?.let {throw BuyFailure(it)}}
            override suspend fun postFinalGuarded(f:BuyFund,t:BuyBaseline,beforeSend:suspend()->Unit){preflight?.let {throw BuyFailure(it)};beforeSend();postFinal(f,t)}
        }
        fun buys()=SingleBuyRepository(bs,gateway,BundledBuyCalendar::forCode,now={time},readback=PositionReadback {})
        fun sells()=SingleSellRepository(ss,gateway,now={time},readback=PositionReadback {})
        fun queue()=OrdinaryTradeAutomation(bs,ss,buys(),sells())
        suspend fun ready():BuyTransaction {val t=buys().create(BuyInput(fund,BigDecimal("5"),BigDecimal.ZERO,LocalDate.parse("2026-10-08"),CutoffType.BEFORE_1500),autoSync=true);return buys().confirmNav(t.id)}
        suspend fun unresolved():BuyTransaction {val t=ready();applyPost=false;postFailure=BuyProblem.NETWORK;val result=buys().sync(t.id);time=time.plusSeconds(1200);return result}
    }
    @Test fun preflightFailuresNeverMarkSentAndCanRetrySafely()=runTest {
        for(problem in listOf(BuyProblem.AUTH_EXPIRED,BuyProblem.RATE_LIMITED,BuyProblem.NETWORK,BuyProblem.ACCOUNT_MISMATCH)) {
            val f=F();val t=f.ready();f.preflight=problem
            val failed=f.buys().sync(t.id);assertEquals(BuyStatus.READY_TO_SYNC,failed.status);assertFalse(failed.postMayHaveBeenSent)
            assertEquals(WriteStage.NOT_SENT,failed.writeStage);assertEquals(problem,failed.writeProblem);assertEquals(0,f.posts)
            f.preflight=null;assertEquals(BuyStatus.SYNCED,f.buys().sync(t.id).status);assertEquals(1,f.posts)
        }
    }
    @Test fun unknownWriteKeepsErrorAndAutomaticChecksNeverResend()=runTest {
        val f=F();val t=f.unresolved();assertEquals(BuyStatus.SYNCING,t.status);assertEquals(BuyProblem.WRITE_PENDING,t.problem)
        assertEquals(BuyProblem.NETWORK,t.writeProblem);assertEquals(WriteStage.MAY_HAVE_SENT,t.writeStage)
        repeat(3){f.buys().restore();f.queue().run(setOf("1"))}
        val row=f.bs.read().single();assertEquals(1,f.posts);assertEquals(t.submittedTarget,row.submittedTarget);assertEquals(BuyProblem.NETWORK,row.writeProblem)
        assertTrue(WriteDiagnostics.needsReview(row.postMayHaveBeenSent,row.status,row.writeStartedAt,row.recoveryAttempts,row.createdAt,f.time))
    }
    @Test fun recoveryReusesFrozenTargetAndRecordsOneExplicitAttempt()=runTest {
        val f=F();val t=f.unresolved();val quote=f.queue().reviewRecovery(setOf("1"),t.id)!!
        f.applyPost=true;f.postFailure=null;f.queue().recoverConfirmed(setOf("1"),quote)
        val result=f.bs.read().single();assertEquals(BuyStatus.SYNCED,result.status);assertEquals(2,f.posts)
        assertEquals(f.targets[0],f.targets[1]);assertEquals(1,result.recoveryAttempts.size);assertEquals(t.baseline,result.baseline)
        assertEquals(t.confirmedShares,result.confirmedShares);assertEquals(t.confirmedNav,result.confirmedNav);assertEquals(WriteStage.ACKNOWLEDGED,result.writeStage)
        f.queue().run(setOf("1"));assertEquals(2,f.posts)
    }
    @Test fun staleRecoveryQuoteCannotAuthorizeAnotherPost()=runTest {
        val f=F();val t=f.unresolved();val quote=f.queue().reviewRecovery(setOf("1"),t.id)!!
        f.queue().recoverConfirmed(setOf("1"),quote);assertEquals(2,f.posts);f.time=f.time.plusSeconds(1200)
        assertFailsWith<BuyFailure>{f.queue().recoverConfirmed(setOf("1"),quote)};assertEquals(2,f.posts)
    }
    @Test fun successAlreadyVisibleOnlyMarksSyncedWithoutPost()=runTest {
        val f=F();val t=f.unresolved();f.position=t.submittedTarget!!
        assertNull(f.queue().reviewRecovery(setOf("1"),t.id));assertEquals(BuyStatus.SYNCED,f.bs.read().single().status);assertEquals(1,f.posts)
    }
    @Test fun externalChangeBeforeReviewAndAfterConfirmationBothBlockWrite()=runTest {
        val f=F();val t=f.unresolved();f.position=BuyBaseline(BigDecimal("70"),BigDecimal("8"))
        assertNull(f.queue().reviewRecovery(setOf("1"),t.id));assertEquals(BuyStatus.EXTERNAL_CHANGED,f.bs.read().single().status);assertEquals(1,f.posts)
        val other=F();val u=other.unresolved();val quote=other.queue().reviewRecovery(setOf("1"),u.id)!!
        other.position=BuyBaseline(BigDecimal("70"),BigDecimal("8"));other.queue().recoverConfirmed(setOf("1"),quote)
        assertEquals(BuyStatus.EXTERNAL_CHANGED,other.bs.read().single().status);assertEquals(1,other.posts)
    }
    @Test fun secondUncertainWriteAndWrongAccountBlockRecovery()=runTest {
        val f=F();val t=f.unresolved();f.bs.write(f.bs.read()+t.copy(id="other",createdAt=t.createdAt.plusSeconds(1)))
        assertFailsWith<BuyFailure>{f.queue().reviewRecovery(setOf("1"),t.id)};assertEquals(1,f.posts)
        assertFailsWith<BuyFailure>{f.queue().reviewRecovery(setOf("2"),t.id)};assertEquals(1,f.posts)
    }
    @Test fun preflightDuringRecoveryPreservesOriginalUncertaintyAndDoesNotWrite()=runTest {
        val f=F();val t=f.unresolved();val quote=f.queue().reviewRecovery(setOf("1"),t.id)!!;f.preflight=BuyProblem.RATE_LIMITED
        f.queue().recoverConfirmed(setOf("1"),quote);val row=f.bs.read().single()
        assertTrue(row.postMayHaveBeenSent);assertEquals(WriteStage.MAY_HAVE_SENT,row.writeStage);assertEquals(BuyProblem.RATE_LIMITED,row.writeProblem)
        assertEquals(BuyStatus.SYNCING,row.status);assertTrue(row.recoveryAttempts.isEmpty());assertEquals(1,f.posts)
    }
    @Test fun legacyJournalRemainsUncertainAndNeverAutoResetsMarker()=runTest {
        val f=F();f.unresolved();val legacy=BuyCodec.encode(f.bs.read()).map {JsonObject(it.jsonObject.filterKeys {k->k !in setOf("writeStage","writeProblem","writeStartedAt","recoveryAttempts")})}
        f.bs.write(BuyCodec.decode(JsonArray(legacy)));f.buys().restore();f.queue().run(setOf("1"))
        val row=f.bs.read().single();assertNull(row.writeStage);assertTrue(row.postMayHaveBeenSent);assertEquals(BuyStatus.SYNCING,row.status);assertEquals(1,f.posts)
    }
    @Test fun recoveryReadFailureNeverPosts()=runTest {
        val f=F();val t=f.unresolved();val quote=f.queue().reviewRecovery(setOf("1"),t.id)!!;f.readsFail=true
        assertFailsWith<BuyFailure>{f.queue().recoverConfirmed(setOf("1"),quote)};assertEquals(1,f.posts)
    }
    @Test fun acknowledgedPostWithStaleReadIsStillNotSynced()=runTest {
        val f=F();val t=f.ready();f.applyPost=false;val row=f.buys().sync(t.id)
        assertEquals(BuyStatus.SYNCING,row.status);assertEquals(WriteStage.ACKNOWLEDGED,row.writeStage);assertEquals(BuyProblem.WRITE_PENDING,row.problem)
    }
    @Test fun sellUsesSamePreflightAndFrozenRecoveryRules()=runTest {
        val f=F();val created=f.sells().create(SellInput(f.fund,BigDecimal("1"),BigDecimal.ZERO,LocalDate.parse("2026-10-08"),CutoffType.BEFORE_1500),autoSync=true)
        f.sells().confirmNav(created.id);f.preflight=BuyProblem.AUTH_EXPIRED
        assertFalse(f.sells().sync(created.id).postMayHaveBeenSent);assertEquals(0,f.posts)
        f.preflight=null;f.applyPost=false;f.postFailure=BuyProblem.NETWORK
        val pending=f.sells().sync(created.id);f.time=f.time.plusSeconds(1200)
        val quote=f.queue().reviewRecovery(setOf("1"),created.id)!!;f.applyPost=true;f.postFailure=null;f.queue().recoverConfirmed(setOf("1"),quote)
        val done=f.ss.read().single();assertEquals(BuyStatus.SYNCED,done.status);assertEquals(1,done.recoveryAttempts.size)
        assertEquals(pending.submittedTarget,done.submittedTarget);assertEquals(pending.baseline,done.baseline);assertEquals(2,f.posts)
    }
    @Test fun actualWriterPreparesSignatureBeforeMarkerAndDispatchesAfterMarker()=runTest {
        var marked=false;var requests=0
        val writer=YjbFinalPositionWriter(VerifiedWritePrecision("SYNTHETIC",2,8),{"fake-session".toCharArray()},{assertFalse(marked);"fake-key".toCharArray()},
            com.ndxinvest.yjb.read.YjbWire {request->assertTrue(marked);assertEquals("POST",request.method);requests++;com.ndxinvest.yjb.read.ReadResponse(200,"{\"code\":200}")})
        writer.postGuarded(BuyFund("1","17","040046","synthetic"),BuyBaseline(BigDecimal("63.77"),BigDecimal("8.1386"))){assertEquals(0,requests);marked=true}
        assertEquals(1,requests)
    }
    @Test fun signatureFailureAndFailedDurableMarkerNeverReachTransport()=runTest {
        var marked=false;var requests=0
        val wire=com.ndxinvest.yjb.read.YjbWire {requests++;com.ndxinvest.yjb.read.ReadResponse(200,"{\"code\":200}")}
        val bad=YjbFinalPositionWriter(VerifiedWritePrecision("SYNTHETIC",2,8),{"fake-session".toCharArray()},{throw BuyFailure(BuyProblem.STORAGE)},wire)
        assertFailsWith<BuyFailure>{bad.postGuarded(BuyFund("1","17","040046","synthetic"),BuyBaseline(BigDecimal("1"),BigDecimal("2"))){marked=true}}
        assertFalse(marked);assertEquals(0,requests)
        val good=YjbFinalPositionWriter(VerifiedWritePrecision("SYNTHETIC",2,8),{"fake-session".toCharArray()},{"fake-key".toCharArray()},wire)
        assertFailsWith<BuyFailure>{good.postGuarded(BuyFund("1","17","040046","synthetic"),BuyBaseline(BigDecimal("1"),BigDecimal("2"))){throw BuyFailure(BuyProblem.STORAGE)}}
        assertEquals(0,requests)
    }
}
