const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const root=path.resolve(__dirname,'..'),source=fs.readFileSync(path.join(root,'index.html'),'utf8'),adapter=fs.readFileSync(path.join(root,'production-read.js'),'utf8');
function runtime(native){const commands=[],window={crypto:require('node:crypto').webcrypto},c={window,document:{createElement:()=>({}),head:{append(){}},addEventListener(){}},v2PlanStatus:p=>p?'计划':'未设置'};for(const s of [...source.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].slice(0,2))vm.runInNewContext(s[1],c);window.NDXYjbRead={post:a=>commands.push(a),prepareCreate:text=>JSON.stringify({...JSON.parse(text),requestId:window.crypto.randomUUID()}),acknowledgeCreate:()=>true,...native};vm.runInNewContext(adapter,c);return {window,commands};}

test('native personal statistics update only derived displays and preserve YJB values and transactions',()=>{
 const {window:w}=runtime(),data={holdings:[{id:'yjb:1:17',fundId:'17',amount:'¥1100',profit:'¥100'}],details:[{id:'yjb:1:17',amount:'¥1100',cost:'¥1000',shares:'100',avgNav:'10',profit:'¥100',profitRate:'10%'}],overview:{amount:'¥1100',metrics:['¥1000','¥100','10%','--']}};
 const stat={annualized:'+10.00%',realizedProfit:'¥0.00',cumulativeProfit:'+¥100.00',cumulativeRate:'+10.00%',holdingStartDate:'2025-10-05',holdingDays:365,valuationDate:'2026-10-05',message:''};
 w.YjbReadApply({data,initialPositions:[],transactions:[],localStatistics:{funds:{'yjb:1:17':stat},overview:{annualized:'+10.00%'}},accountStatus:'CONNECTED',holdingsStatus:'READY'});
 const s=w.V2Store.getState();assert.equal(s.details[0].annualized,stat.annualized);assert.equal(s.details[0].holdingStartDate,stat.holdingStartDate);assert.equal(s.overview.metrics[3],stat.annualized);assert.equal(s.details[0].amount,'¥1100');assert.equal(s.details[0].shares,'100');assert.equal(s.details[0].cost,'¥1000');assert.equal(s.details[0].profit,'¥100');assert.equal(s.transactions.length,0);
 w.YjbReadApply({data,localStatistics:{funds:{'yjb:1:17':{...stat,annualized:'--',message:'统计区间未满 90 天'}},overview:{annualized:'--'}},accountStatus:'CONNECTED',holdingsStatus:'OFFLINE_CACHE'});
 assert.equal(w.V2Store.getState().details[0].annualized,'--');assert.match(w.V2Store.getState().details[0].statisticsMessage,/90/);assert.equal(w.V2Store.getState().holdingsState.isCached,true);
});
test('initial contribution command never enters trade pipeline and response uses a separate local acknowledgement',()=>{
 const {window:w,commands}=runtime();let result;w.V2InitialContributions={result:r=>result=r};
 const id='initial:YJB_IMPORT:17',requestId=w.crypto.randomUUID(),entries=[{date:'2025-01-01',amount:'1000.12345678'}];
 assert.equal(w.V2Store.saveInitialContributions(id,entries,requestId).ok,true);assert.equal(commands.length,1);assert.match(commands[0],/^initial-contributions:/);assert.equal(JSON.parse(commands[0].slice('initial-contributions:'.length)).entries[0].amount,entries[0].amount);
 w.YjbReadApply({contributionResult:{id,requestId,ok:true}});assert.equal(result.ok,true);assert.equal(w.V2Store.getState().transactions.length,0);assert.equal(w.V2Store.getState().holdings.length,0);
});

test('production SELL contract preserves holding authority and separates calculation from cash receipt',()=>{
 const spec=JSON.parse(fs.readFileSync(path.join(root,'contracts/production-transactions.json')));
 assert.equal(spec.holdingAuthority,'YJB');assert.equal(spec.historyAuthority,'LOCAL');assert.equal(spec.sell.costPolicy,'KEEP_UNIT_COST');
 assert.equal(spec.sell.cashReceiptConfirmed,false);assert.equal(spec.sell.input.includes('confirmedNav'),false);assert.equal(spec.sell.input.includes('feeInput'),false);
 for(const field of ['confirmedNav','confirmedNavDate','feeAmount','baselineShares','baselineUnitCost','submittedTarget','postMayHaveBeenSent'])assert.ok(spec.readonly.includes(field));
 assert.equal(spec.fieldOwnership.confirmedNav,'MARKET');assert.equal(spec.fieldOwnership.currentShares,'YJB');assert.equal(spec.fieldOwnership.netAmount,'DERIVED');
 assert.match(spec.uncertainDelivery,/GET before retry/);assert.match(spec.acceptance,/real GET verification required/);
});

test('Production plans use native persistence and formal NAV automation, never browser execution',()=>{
 const {window:w,commands}=runtime(),data={holdings:[{id:'yjb:1:17',fundCode:'000017',name:'基金'}],details:[{id:'yjb:1:17'}],overview:{}};
 w.YjbReadApply({data,plans:[],transactions:[],accountStatus:'CONNECTED',holdingsStatus:'READY'});
 const draft={fundId:'yjb:1:17',amountInput:'123.123456789',buyRateInput:'0.15',frequency:'weekly',weekday:2,monthDay:1,enabled:true,note:'本地计划'};
 w.V2Store.savePlan(draft);const create=JSON.parse(commands.at(-1).slice(12));assert.equal(create.action,'create');assert.equal(create.buyRateInput,'0.15');assert.equal(w.V2Store.getState().plans.length,0);
 w.V2Store.savePlan(draft);assert.equal(commands.length,1);
 const p={...draft,id:create.requestId,revision:1,nextDate:'2026-10-03',nextDateText:'2026-10-03 · 待正式净值',canExecute:true};
 w.YjbReadApply({data,plans:[p],transactions:[],accountStatus:'CONNECTED',holdingsStatus:'READY',buyResult:{requestId:create.requestId,ok:true,message:'计划已保存'}});
 assert.equal(w.V2Store.getState().holdings[0].autoInvest,true);assert.equal(w.V2Store.getState().transactions.length,0);
 w.V2Store.updatePlan(p.fundId,{...draft,amountInput:'456.50'},p.id);const update=JSON.parse(commands.at(-1).slice(12));assert.equal(update.action,'update');assert.equal(update.revision,1);
 w.YjbReadApply({buyResult:{requestId:update.requestId,ok:true,message:'已修改'}});
 const beforeExecute=commands.length;assert.equal(w.V2Store.executePlan(p.fundId,p.id).ok,false);assert.equal(commands.length,beforeExecute);assert.equal(w.V2Store.getState().transactions.length,0);
 w.V2Store.setPlanEnabled(p.fundId,false,p.id);const pause=JSON.parse(commands.at(-1).slice(12));assert.equal(pause.enabled,false);
 w.YjbReadApply({data,plans:[{...p,enabled:false,canExecute:false,revision:2}],transactions:[],accountStatus:'CONNECTED',holdingsStatus:'READY',buyResult:{requestId:pause.requestId,ok:true,message:'已暂停'}});
 const count=commands.length;assert.equal(w.V2Store.executePlan(p.fundId,p.id).ok,false);assert.equal(commands.length,count);
 w.V2Store.deletePlan(p.fundId,p.id);const remove=JSON.parse(commands.at(-1).slice(12));assert.equal(remove.action,'delete');assert.equal(remove.revision,2);
 w.YjbReadApply({data,plans:[],transactions:[],accountStatus:'CONNECTED',holdingsStatus:'READY',buyResult:{requestId:remove.requestId,ok:true,message:'已删除'}});
 assert.equal(w.V2Store.getState().holdings[0].autoInvest,false);assert.equal(w.V2Store.getState().plans.length,0);
});
test('Production initializes empty platform state before frames and cannot complete Mock login or trades',()=>{
 const {window:w,commands}=runtime(),s=w.V2Store.getState();assert.equal(s.holdings.length,0);assert.equal(s.transactions.length,0);assert.equal(s.plans.length,0);assert.equal(s.yjbAccount.status,'NOT_CONNECTED');
 assert.equal(w.V2Store.startLogin(),false);assert.equal(w.V2Store.completeLogin(true),false);assert.equal(w.V2Store.startHoldingSync(),false);assert.equal(w.V2Store.completeHoldingSync(),false);w.V2Store.setAccountStatus('NOT_CONNECTED');assert.deepEqual(commands,['connect','refresh','disconnect']);
 assert.equal(w.V2Store.submitTrade({}).ok,false);assert.equal(w.V2Store.transactionAction('x','complete').ok,false);w.V2Store.reset();assert.equal(w.V2Store.getState().holdings.length,0);
});
test('Production apply maps platform snapshots without changing NDX, strategy, history or theme',()=>{
 const {window:w}=runtime(),before=w.V2Store.getState();const data={holdings:[{id:'yjb:1:17',name:'真实字段测试',amount:'¥999',profit:'-¥1',availableSharesText:'1',meta:'账户',autoInvest:false,sellPresets:{'.25':'.25','.5':'.5','1':'1'}}],details:[{id:'yjb:1:17',amount:'¥999',profit:'-¥1'}],overview:{amount:'¥999',metrics:['¥1000','-¥1','-0.1%','--']},displayName:'测试用户',maskedId:'138****0000',lastSyncAt:'2026-10-02T01:30:00Z'};
 for(const holdingsStatus of ['READY','REFRESHING','OFFLINE_CACHE','AUTH_EXPIRED','ERROR']){w.YjbReadApply({data,accountStatus:holdingsStatus==='AUTH_EXPIRED'?'AUTH_EXPIRED':'CONNECTED',holdingsStatus,errorMessage:'测试'});const s=w.V2Store.getState();assert.equal(s.holdingsState.status,holdingsStatus);assert.equal(s.holdings[0].amount,'¥999');assert.equal(s.holdingsState.isCached,holdingsStatus==='OFFLINE_CACHE');assert.equal(s.nd100.indexText,before.nd100.indexText);assert.equal(s.theme,before.theme);assert.equal(s.transactions.length,0);assert.equal(s.nd100Strategy.tiers.length,before.nd100Strategy.tiers.length);}
});

test('native holding refresh publishes busy state once and keeps the full projection',()=>{
 const {window:w,commands}=runtime(),data={holdings:[{id:'yjb:1:17',name:'基金'}],details:[],overview:{amount:'¥100',metrics:[]},lastSyncAt:'2026-10-04'};
 w.YjbReadApply({data,accountStatus:'CONNECTED',holdingsStatus:'READY'});const before=w.V2Store.getState();
 w.V2Store.startHoldingSync();w.V2Store.startHoldingSync();assert.deepEqual(commands,['refresh']);const busy=w.V2Store.getState();
 assert.equal(busy.holdingsState.status,'REFRESHING');assert.deepEqual(busy.holdings,before.holdings);assert.deepEqual(busy.nd100,before.nd100);assert.deepEqual(busy.transactions,before.transactions);
 w.YjbReadApply({data,accountStatus:'CONNECTED',holdingsStatus:'OFFLINE_CACHE'});assert.equal(w.V2Store.getState().holdingsState.isCached,true);w.V2Store.startHoldingSync();assert.deepEqual(commands,['refresh','refresh']);
});
test('native adapter and YJB runtime have no credential JS storage, old ledger authority or write routes',()=>{
 for(const term of ['fetch(','XMLHttpRequest','localStorage','Token','verify_code','password','fund_hold','LedgerRepository','RoomDatabase'])assert.equal(adapter.includes(term),false,term);
 const kotlin=fs.readFileSync(path.join(root,'../yjb/read/src/main/kotlin/com/ndxinvest/yjb/read/YjbApi.kt'),'utf8');assert.ok(kotlin.includes('https://app-api.yangjibao.com'));assert.ok(kotlin.includes('followRedirects(false)'));assert.equal(/println\(|Log\.|HttpLoggingInterceptor/.test(kotlin),false);
});
test('initial import record remains separate from BUY and preserves importedAt on date command',()=>{
 const {window:w,commands}=runtime();const r={id:'initial:YJB_IMPORT:17',kind:'INITIAL_POSITION',source:'YJB_IMPORT',fundId:'17',fundCode:'000017',fundName:'测试',importedAt:'2026-10-02T01:30:00Z',initialShares:'100',initialHoldingCost:'120',initialUnitCost:'1.2',initialMarketValue:'150',holdingStartDate:null};const payload={data:{holdings:[],details:[],overview:{amount:'--',metrics:[]},lastSyncAt:r.importedAt},initialPositions:[r],accountStatus:'CONNECTED',holdingsStatus:'EMPTY'};
 w.YjbReadApply(payload);assert.equal(w.V2Store.getState().transactions[0].type,'初始持仓导入');assert.equal(w.V2Store.getState().transactions[0].transactionStatus,undefined);assert.equal(w.V2Store.transactionAction(r.id,'sync').ok,false);
 assert.equal(w.V2Store.editHoldingStartDate(r.id,'2026-10-01').ok,true);assert.match(commands.at(-1),/^holding-start:/);assert.equal(w.V2Store.getState().transactions[0].importedAt,r.importedAt);
 const sent=commands.length;
 for(const date of ['2026-10-03','2026-02-30','1899-01-01'])assert.equal(w.V2Store.editHoldingStartDate(r.id,date).ok,false);
 assert.equal(commands.length,sent,'invalid fallback dates cannot reach native storage');
 w.YjbReadApply({...payload,initialPositions:[{...r,holdingStartDate:'2026-10-01'}]});assert.equal(w.V2Store.getState().transactions.length,1);assert.equal(w.V2Store.getState().transactions[0].holdingStartDate,'2026-10-01');
});

test('initial records group by Beijing observation date and retain precise import data',()=>{
 const {window:w}=runtime(),r={id:'initial:YJB_IMPORT:17',kind:'INITIAL_POSITION',source:'YJB_IMPORT',fundId:'17',fundCode:'000017',fundName:'测试',importedAt:'2026-10-02T18:30:00.256Z',initialShares:'100.123456789',initialHoldingCost:'200.123456789',initialUnitCost:'2.123456789',initialMarketValue:'222.123456789',holdingStartDate:null};
 w.YjbReadApply({data:{holdings:[],details:[],overview:{},lastSyncAt:r.importedAt},initialPositions:[r],transactions:[],accountStatus:'CONNECTED',holdingsStatus:'EMPTY'});
 const record=w.V2Store.getState().transactions[0];assert.equal(record.dateKey,'2026-10-03');assert.equal(record.dateLabel,'2026-10-03');
 assert.equal(record.time,r.importedAt);assert.equal(record.importedAt,r.importedAt);assert.equal(record.initialShares,r.initialShares);assert.equal(record.initialMarketValue,r.initialMarketValue);
 assert.equal(w.V2Store.getState().yjbAccount.lastSyncAt,r.importedAt);assert.equal(record.transactionStatus,undefined);
});
test('ordinary BUY uses only synced funds and native journal, never Mock success or DCA',()=>{
 const {window:w,commands}=runtime();const data={holdings:[{id:'yjb:1:17',fundCode:'270042',name:'基金'}],details:[],overview:{},lastSyncAt:'2026-10-03'};
 w.YjbReadApply({data,transactions:[],accountStatus:'CONNECTED',holdingsStatus:'READY'});
 const draft={fundId:'yjb:1:17',type:'买入',amountInput:'1000.123456789',buyRateInput:'0.15',date:'2026-09-28',timePeriod:'before',note:'备注'};
 assert.equal(w.V2Store.submitTrade({...draft,fundId:'unknown'}).ok,false);assert.equal(commands.length,0);
 assert.equal(w.V2Store.submitTrade({...draft,type:'卖出',sellRateInput:undefined}).ok,false);assert.equal(w.V2Store.savePlan(draft).ok,false);assert.equal(commands.length,0);
 assert.equal(w.V2Store.submitTrade(draft).ok,false);assert.match(commands[0],/^buy-create:/);const request=JSON.parse(commands[0].slice(11));assert.equal(request.amountInput,draft.amountInput);assert.equal(request.buyRateInput,'0.15');assert.equal(w.V2Store.getState().transactions.length,0);
 w.V2Store.submitTrade(draft);assert.equal(commands.length,1,'pending submit is blocked');
 const row={id:request.requestId,kind:'BUY',type:'买入',direction:'BUY',fundId:draft.fundId,transactionStatus:'WAITING_NAV',expectedNavDate:'2026-09-28',confirmedNav:null,confirmedNavDate:null,confirmedShares:null};
 w.YjbReadApply({data,transactions:[row],accountStatus:'CONNECTED',holdingsStatus:'READY',buyResult:{requestId:request.requestId,ok:true,message:'已保存'}});assert.equal(w.V2Store.getState().transactions.length,1);assert.equal(w.V2Store.getState().transactions[0].confirmedNav,null);
 w.V2Store.transactionAction(row.id,'check-nav');assert.match(commands.at(-1),/^buy-action:/);assert.equal(w.V2Store.transactionAction(row.id,'complete').ok,false);assert.equal(w.V2Store.transactionAction(row.id,'rebase').ok,false);
 w.YjbReadApply({data,transactions:[],accountStatus:'NOT_CONNECTED',holdingsStatus:'NOT_CONNECTED'});assert.equal(w.V2Store.getState().transactions.length,0,'native payload replaces active-account history');
});
test('ordinary SELL sends shares and explicit sell rate, ignores UI fee and restores native history',()=>{
 const {window:w,commands}=runtime(),data={holdings:[{id:'yjb:1:17',fundCode:'000017',name:'基金',availableSharesText:'100'}],details:[],overview:{},lastSyncAt:'2026-10-03'};
 w.YjbReadApply({data,transactions:[],accountStatus:'CONNECTED',holdingsStatus:'READY'});
 const draft={fundId:'yjb:1:17',type:'卖出',amountInput:'25.1234',sellRateInput:'1.5',buyRateInput:'99',feeInput:'999',date:'2026-09-28',timePeriod:'before',note:'卖出备注'};
 assert.equal(w.V2Store.submitTrade(draft).ok,false);assert.match(commands[0],/^sell-create:/);
 const request=JSON.parse(commands[0].slice(12));assert.equal(request.sharesInput,'25.1234');assert.equal(request.sellRateInput,'1.5');
 assert.equal(request.buyRateInput,undefined);assert.equal(request.feeInput,undefined);assert.equal(request.amountInput,undefined);
 w.V2Store.submitTrade(draft);assert.equal(commands.length,1);
 const row={id:request.requestId,kind:'SELL',type:'卖出',direction:'SELL',fundId:draft.fundId,transactionStatus:'WAITING_NAV',expectedNavDate:'2026-09-28',confirmedNav:null,confirmedNavDate:null};
 w.YjbReadApply({data,transactions:[row],accountStatus:'CONNECTED',holdingsStatus:'READY',buyResult:{requestId:request.requestId,ok:true,message:'已保存卖出'}});
 assert.equal(w.V2Store.getState().transactions[0].kind,'SELL');w.V2Store.transactionAction(row.id,'check-nav');assert.match(commands.at(-1),/^sell-action:/);
 assert.equal(w.V2Store.transactionAction(row.id,'complete').ok,false);assert.equal(w.V2Store.transactionAction(row.id,'rebase').ok,false);
 w.YjbReadApply({data,accountStatus:'CONNECTED',holdingsStatus:'REFRESHING'});assert.equal(w.V2Store.getState().transactions[0].kind,'SELL');
 w.YjbReadApply({data:{...data,holdings:[]},transactions:[{...row,transactionStatus:'SYNCED'}],accountStatus:'CONNECTED',holdingsStatus:'EMPTY'});
 assert.equal(w.V2Store.getState().transactions[0].kind,'SELL');assert.equal(w.V2Store.getState().holdings.length,0,'history does not reconstruct holdings');
});

test('closed fund catalogue permits native BUY but never fake holding, SELL or search',()=>{
 const {window:w,commands}=runtime(),closed={id:'yjb:1:17',fundId:'17',fundCode:'270042',name:'已清仓基金',availableSharesText:'0'};
 w.YjbReadApply({data:{holdings:[],fundCatalog:[closed],details:[],overview:{amount:'¥0',metrics:['¥0','¥0','--','--']}},accountStatus:'CONNECTED',holdingsStatus:'EMPTY'});
 assert.equal(w.V2Store.getState().holdings.length,0);assert.equal(w.V2Store.getState().fundCatalog.length,1);
 const draft={fundId:closed.id,type:'卖出',amountInput:'1',sellRateInput:'0',date:'2026-09-30',timePeriod:'before'};
 w.V2Store.submitTrade(draft);assert.equal(commands.length,0);
 w.V2Store.submitTrade({...draft,type:'买入',buyRateInput:'0'});assert.match(commands[0],/^buy-create:/);
 assert.equal(w.V2Store.getState().holdings.length,0);assert.equal(w.V2Store.getState().transactions.length,0);
});
test('WebView restart restores a durable create, uncertain result blocks duplicates, known result acknowledges',()=>{
 let journal;
 const native={prepareCreate:text=>{const r=JSON.parse(text);journal=journal||{...r,requestId:'11111111-1111-4111-8111-111111111111'};return JSON.stringify(journal);},acknowledgeCreate:id=>{if(journal?.result?.requestId!==id)return false;journal=null;return true;}};
 const data={holdings:[{id:'yjb:1:17'}],details:[],overview:{}},payload={data,accountStatus:'CONNECTED',holdingsStatus:'READY'};
 const a=runtime(native);a.window.YjbReadApply(payload);const draft={fundId:'yjb:1:17',type:'买入',amountInput:'100',buyRateInput:'0',date:'2026-09-30',timePeriod:'before'};
 a.window.V2Store.submitTrade(draft);const id=journal.requestId;assert.equal(a.commands.length,1);
 const b=runtime(native);b.window.YjbReadApply({...payload,creationRecovery:journal});b.window.V2Store.submitTrade(draft);assert.equal(b.commands.length,0,'reload cannot create a second transaction');
 b.window.YjbReadApply({...payload,buyResult:{requestId:id,creationUncertain:true,ok:false,message:'结果未知'}});b.window.V2Store.submitTrade(draft);assert.equal(b.commands.length,0);
 journal.result={requestId:id,ok:true,message:'记录已保存'};
 b.window.YjbReadApply({...payload,creationRecovery:journal,transactions:[{id,kind:'BUY',type:'买入',transactionStatus:'WAITING_NAV'}]});assert.equal(journal,null);
 assert.equal(b.window.V2Store.getState().transactions.length,1);
 b.window.V2Store.submitTrade({...draft,amountInput:'200'});assert.equal(b.commands.length,1,'only a new user submission is sent');
});

test('local placeholder permits BUY catalogue but blocks SELL and DCA without issuing commands',()=>{
 const {window:w,commands}=runtime();const row={id:'yjb:1:10869',fundId:'10869',fundCode:'000834',name:'占位基金',amount:'--',profit:'--',isPlaceholder:true};
 w.YjbReadApply({data:{fundCatalog:[row],holdings:[row],details:[{id:row.id,nav:'6.4259',amount:'--',profit:'--'}],overview:{amount:'--',metrics:['--','--','--','--']}},accountStatus:'CONNECTED',holdingsStatus:'READY',initialPositions:[],transactions:[],plans:[]});
 assert.equal(w.V2Store.getState().holdings[0].isPlaceholder,true);assert.equal(w.V2Store.getState().overview.amount,'--');assert.equal(w.V2Store.getState().transactions.length,0);
 assert.equal(w.V2Store.submitTrade({type:'卖出',fundId:row.id,amountInput:'1',sellRateInput:'0'}).ok,false);assert.equal(w.V2Store.savePlan({fundId:row.id}).ok,false);assert.equal(commands.length,0);
 const buy=w.V2Store.submitTrade({type:'买入',fundId:row.id,amountInput:'100',buyRateInput:'0',date:'2026-09-28',timePeriod:'before'});assert.equal(buy.ok,false);assert.ok(commands.at(-1).startsWith('buy-create:'));assert.equal(w.V2Store.getState().holdings[0].amount,'--');
});
