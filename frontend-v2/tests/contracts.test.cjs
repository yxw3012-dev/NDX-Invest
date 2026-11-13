const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'..'), project=path.resolve(root,'..');
const source=fs.readFileSync(path.join(root,'index.html'),'utf8');
const sha=s=>crypto.createHash('sha256').update(s).digest('hex');
const scripts=s=>[...s.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].map(m=>m[1]);
const motionDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/motion-authorized-diff.json'),'utf8'));
const drawdownLayoutDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/drawdown-layout-authorized-diff.json'),'utf8'));
const performancePeriodsDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/performance-periods-authorized-diff.json'),'utf8'));
const homeDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/home-continuous-authorized-diff.json'),'utf8'));
const bottomRefreshDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/bottom-refresh-authorized-diff.json'),'utf8'));
const confirmationDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/confirmation-date-authorized-diff.json'),'utf8'));
const recordDisplayDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/record-display-authorized-diff.json'),'utf8'));
const recordTagsNotesDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/record-tags-notes-authorized-diff.json'),'utf8'));
const recordPreviewDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/record-preview-polish-authorized-diff.json'),'utf8'));
const fundLocalDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/fund-local-ui-authorized-diff.json'),'utf8'));
const cleanupDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/cleanup-authorized-diff.json'),'utf8'));
const closeoutDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/app-closeout-authorized-diff.json'),'utf8'));
const appearance=fs.readFileSync(path.join(root,'appearance.js'),'utf8');
const wallpaperScrollDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/wallpaper-scroll-authorized-diff.json'),'utf8'));
const universalMineScrollDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/universal-mine-scroll-authorized-diff.json'),'utf8'));
const personalStatisticsDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/personal-statistics-authorized-diff.json'),'utf8'));
const contributionsModule=fs.readFileSync(path.join(root,'initial-contributions.js'),'utf8');
const mineReturnScrollDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/mine-return-scroll-authorized-diff.json'),'utf8'));
const fundAdditionDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/fund-addition-authorized-diff.json'),'utf8'));
const dcaQueueDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/dca-queue-authorized-diff.json'),'utf8'));
const localPortfolioDiff=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/local-portfolio-authorized-diff.json'),'utf8'));
function restoreMotion(scope,text){if(scope==='持仓')text=text.replace("width: app.clientWidth - 32,\n          height: 172,","width: app.clientWidth - 24,\n          height: 174,");if(scope==='shell'){const addon='\n'+contributionsModule+'\n'+appearance+'\n';assert.ok(text.includes(addon),'authorized additive modules');text=text.replace(addon,'');}for(const change of [...motionDiff,...drawdownLayoutDiff,...performancePeriodsDiff,...homeDiff,...bottomRefreshDiff,...confirmationDiff,...recordDisplayDiff,...recordTagsNotesDiff,...recordPreviewDiff,...fundLocalDiff,...cleanupDiff,...closeoutDiff,...wallpaperScrollDiff,...universalMineScrollDiff,...personalStatisticsDiff,...mineReturnScrollDiff,...fundAdditionDiff,...dcaQueueDiff,...localPortfolioDiff].filter(c=>c.scope===scope).reverse()){assert.ok(text.includes(change.after),'authorized UI diff '+scope);text=change.all?text.replaceAll(change.after,change.before):text.replace(change.after,()=>change.before);}return text;}
const pages=JSON.parse(source.match(/<script>window\.V2Pages=([\s\S]*?);<\/script>/)[1]);
function store(){const c={window:{}};for(const s of scripts(source).slice(0,2))vm.runInNewContext(s,c);return c.window.V2Store;}
test('canonical and packaged web build match the accepted current V2 hash',()=>{
 const baseline=JSON.parse(fs.readFileSync(path.join(root,'baseline.json'),'utf8'));
 assert.equal(sha(fs.readFileSync(path.join(root,'index.html'))),baseline.sha256);
 assert.equal(sha(fs.readFileSync(path.join(root,'dist/index.html'))),baseline.sha256);
});
test('production DCA contract preserves ordinary BUY authority with formal NAV automatic sync',()=>{
 const spec=JSON.parse(fs.readFileSync(path.join(root,'contracts/production-dca.json')));
 assert.deepEqual(spec.actions,['create','update','enabled','delete']);assert.deepEqual(spec.buyMetadata,['originPlanId','scheduledDate']);
 for(const key of ['amountInput','buyRateInput','frequency','enabled','nextDate','originPlanId','scheduledDate'])assert.equal(spec.fieldOwnership[key],'LOCAL');
 for(const key of ['fundId','fundCode','currentShares','currentUnitCost'])assert.equal(spec.fieldOwnership[key],'YJB');
 assert.equal(spec.fieldOwnership.confirmedNav,'MARKET');assert.equal(spec.fieldOwnership.canExecute,'DERIVED');
 assert.match(spec.idempotency,/reservation before/);assert.match(spec.executeResult,/WAITING_NAV/);assert.match(spec.lateExecution,/createdAt is actual/);assert.match(spec.delete,/retain occurrence identities/);
 assert.equal(spec.executionMode,'CALENDAR_CREATE_EXACT_NAV_QUEUE_SYNC');assert.match(spec.background,/Android may defer/);assert.match(spec.schedule,/persist WAITING_NAV without waiting for NAV/);assert.equal(spec.canonicalUi,'frontend-v2/index.html');
});

test('every outer and embedded script parses',()=>{for(const s of [...scripts(source),...Object.values(pages).flatMap(scripts)])new vm.Script(s);});
test('long-term performance has four fixed periods with matching Mock and production contracts',()=>{
 const ndx=pages.NDX100,periods=[...ndx.matchAll(/data-period="([^"]+)"/g)].map(m=>m[1]);
 assert.deepEqual(periods,['1Y','3Y','5Y','10Y']);assert.equal((ndx.match(/class="metrics-page"/g)||[]).length,4);
 assert.equal(store().getState().nd100.performance.length,4);
 const spec=JSON.parse(fs.readFileSync(path.join(root,'contracts/production-market.json'),'utf8'));
 assert.deepEqual(spec.history.periods,periods);assert.equal(spec.history.requestedFromIsCoverageRequirement,false);
 assert.equal(Object.hasOwn(spec.history,'maxPeriod'),false);
});
test('home continuous scale reads strategy thresholds and contains no duplicate percent bubble or history header',()=>{
 const home=pages.NDX100;
 assert.ok(home.includes('state.nd100Strategy.tiers'));
 assert.equal(home.includes('id="drawdownMarkerBubble"'),false);
 assert.equal(home.includes('<div class="static-marker-line"'),false);
 assert.equal(home.includes('<div class="card-meta-note"'),false);
 assert.ok(home.includes('id="drawdownFill"'));assert.ok(source.includes('refresh-check'));assert.ok(home.includes('parent.V2BottomRefresh.mount'));
 const context={window:{}};for(const s of scripts(source).slice(0,2))vm.runInNewContext(s,context);
 context.tiers=[{drawdownThreshold:'10'},{drawdownThreshold:'15'},{drawdownThreshold:'20'},{drawdownThreshold:'30'}];
 assert.equal(vm.runInNewContext('v2DrawdownMarkerPercent(15,tiers)',context),50);
 assert.equal(vm.runInNewContext('v2DrawdownMarkerPercent(13,tiers)',context),40);
 assert.equal(vm.runInNewContext('v2DrawdownMarkerPercent(50,tiers)',context),100);
});

test('fund name sheet removes old morph runtime and preserves native touch scrolling with explicit commit',()=>{
 for(const term of ['fund-morph-overlay','fund-morph-ball','buy-fund-card-summary','function startWheelDrag'])assert.equal(source.includes(term),false,term);
 for(const id of ['fundPickerPanel','fundPickerConfirm','fundPickerCancel','buyFundWheel'])assert.ok(source.includes('id="'+id+'"'));
 const css=source.match(/\.fund-name-wheel \{([^}]+)\}/)[1];assert.ok(css.includes('touch-action:pan-y'));assert.ok(css.includes('overflow-y:auto'));assert.ok(css.includes('scroll-snap-type:y proximity'));
 const js=scripts(source).at(-1),picker=js.slice(js.indexOf('  let fundWheelKey'),js.indexOf('  function openFeature'));
 assert.equal(picker.includes('pointermove'),false);assert.equal(picker.includes('.scrollTop='),false);assert.ok(picker.includes("pendingFundIndex=Number(option.dataset.index)"));assert.ok(picker.includes("closeFundPicker({commit:true})"));assert.ok(picker.includes('pendingFundIndex=null'));
});
test('V2 has no old business bridge or networking clients',()=>{
 const code=[...scripts(source),...Object.values(pages).flatMap(scripts)].join('\n');
 for(const term of ['NDXAndroid','NDXMock','PortfolioRepository','LedgerRepository','FundRepository','InvestmentPlanRepository','TransactionRepository','RoomDatabase','fetch(','XMLHttpRequest','WebSocket(','EventSource('])assert.equal(code.includes(term),false,term);
});
test('trade and plan demonstrations leave holding authority and financial models untouched',()=>{
 const ui=store(),before=ui.getState();
 assert.equal(ui.submitTrade({fundId:'gf-ndx100a',type:'卖出',amountInput:'9999999999999999.99999',date:'2026-10-02',timePeriod:'after',feeInput:'0'}).ok,true);
 assert.equal(ui.getState().holdings,before.holdings);assert.equal(ui.getState().overview,before.overview);assert.equal(ui.getState().details,before.details);
 assert.equal(ui.getState().transactions[0].primaryText,'9999999999999999.99999 份');
 const transactions=ui.getState().transactions;
 assert.equal(ui.savePlan({fundId:'gf-ndx100a',amountInput:'600',frequency:'biweekly',weekday:7,enabled:true}).ok,true);
 assert.equal(ui.getState().transactions,transactions);assert.ok(Object.isFrozen(ui.getState().holdings[0]));
});
test('manual UI changes remain and the standalone plan page remains removed',()=>{
 for(const id of ['buyDateSheet','buyFeeRate','sellFeeRate','buyRecurringSheet','buyFrequencyWheel','buyExecutionWheel'])assert.ok(source.includes('id="'+id+'"'),id);
 assert.equal(source.includes('id="plansPage"'),false);assert.equal(source.includes('function loadDetailedPlan'),false);
 assert.equal(pages['我的'].includes('<span class="row-title">定投计划</span>'),false);
});
test('business database schemas, migrations and retained history source are byte-for-byte intact',()=>{
 const inventory=JSON.parse(fs.readFileSync(path.join(project,'docs/cleanup-step4/scan-before.json'),'utf8')).inventory;
 const protectedFiles=inventory.filter(f=> /^(core\/database\/|ledger\/|core\/network\/|core\/security\/|market\/)/.test(f.path)&&f.path!=='core/network/src/main/kotlin/com/ndxinvest/network/NetworkContracts.kt');
 for(const file of protectedFiles)assert.equal(sha(fs.readFileSync(path.join(project,file.path))),file.sha256,file.path);
});
test('Android entry points preserve UI and only Production enables the closed read bridge',()=>{
 const kotlin='app/src/main/kotlin/com/ndxinvest/app/';
 const entry=fs.readFileSync(path.join(project,kotlin+'MainActivity.kt'),'utf8');
 const app=fs.readFileSync(path.join(project,kotlin+'NdxInvestApplication.kt'),'utf8');
 const host=fs.readFileSync(path.join(project,kotlin+'frontendv2/FrontendV2Host.kt'),'utf8');
 assert.ok(entry.includes('FrontendV2Host'));assert.equal(entry.includes('PROTOTYPE_UI'),false);
 assert.equal(app.includes('ProductionAppGraph'),false);assert.equal(app.includes('RoomLedgerRepository'),false);
 for(const code of [entry,host])for(const term of ['UiAppContainer','RoomLedgerRepository','ProductionAppGraph'])assert.equal(code.includes(term),false,term);
 assert.ok(host.includes('BuildConfig.FLAVOR == "production"'));assert.ok(host.includes('NDXYjbRead'));
 const read=fs.readFileSync(path.join(project,kotlin+'yjb/YjbReadController.kt'),'utf8');
 assert.ok(read.includes('setOf("boot","refresh")'));
 assert.doesNotMatch(read,/showLogin|HttpsYjbWire|AndroidVerifiedYjbWriter/);
 const retirement=fs.readFileSync(path.join(project,kotlin+'frontendv2/RetiredAutomation.kt'),'utf8');
 assert.ok(retirement.includes('cancelUniqueWork'));assert.equal(/enqueue|deleteDatabase|\.db|ledgerDao/.test(retirement),false);
});

test('frozen page designs, all original shell styles and motion code are unchanged',()=>{
 const fixture=JSON.parse(fs.readFileSync(path.join(__dirname,'fixtures/protected-ui.json'),'utf8'));
 for(const [name,hash] of Object.entries(fixture.pageHashes)){
  const current=restoreMotion(name,pages[name]);
  const html=name==='记录'?current.replace(' data-record-id="${escape(row.id)}" tabindex="0" role="button" aria-label="${escape(row.fundName)} 交易详情"',''):current;
  assert.equal(sha(html),hash,name+' frozen UI');
 }
 const originalApp=scripts(restoreMotion('shell',source)).at(-1).split('\n// Presentation-only contract extensions.')[0].replace(/\n    openTradeForFund\(id,trigger\) \{[^\n]+\},/,'');
 const normalizePicker=s=>s.replace(/touch-action: pan-y/g,'touch-action: none').replace(/scroll-snap-stop: normal/g,'scroll-snap-stop: always').replace('    scroll-snap-type: none;','    scroll-snap-type: y mandatory;');
 assert.equal(sha(originalApp),fixture.authorizedPickerAppHash,'authorized scroll handling with preserved shell baseline');
 const restoredStylesSource=restoreMotion('shell',source).replace(/<script>window\.V2Pages=([\s\S]*?);<\/script>/,()=>'<script>window.V2Pages='+JSON.stringify(Object.fromEntries(Object.entries(pages).map(([name,html])=>[name,restoreMotion(name,html)]))).replace(/<\/script>/g,'<\\/script>')+';</script>');
 const rawStyles=[...restoredStylesSource.matchAll(/<style\b[^>]*>[\s\S]*?<\/style>/gi)].map(m=>m[0]);const styleHashes=rawStyles.flatMap(style=>[sha(style),sha(normalizePicker(style))]);for(const hash of fixture.styleHashes)assert.ok(styleHashes.includes(hash),'frozen shell style');
});
test('account transitions and holding statuses preserve financial models and guard duplicate operations',()=>{
 const ui=store(),before=ui.getState();
 for(const status of ['NOT_CONNECTED','CONNECTING','LOGIN_FAILED','CONNECTED','AUTH_EXPIRED']){assert.equal(ui.setAccountStatus(status),true);assert.equal(ui.getState().yjbAccount.status,status);}
 ui.setAccountStatus('NOT_CONNECTED');assert.equal(ui.startHoldingSync(),false);assert.equal(ui.startLogin(),true);assert.equal(ui.startLogin(),false);ui.completeLogin(false);assert.match(ui.getState().yjbAccount.errorMessage,/Mock/);ui.startLogin();ui.completeLogin();
 for(const status of ['NOT_CONNECTED','INITIAL_LOADING','READY','REFRESHING','EMPTY','ERROR','OFFLINE_CACHE','AUTH_EXPIRED']){ui.setHoldingsStatus(status);assert.equal(ui.getState().holdingsState.status,status);assert.equal(ui.getState().holdingsState.isCached,status==='OFFLINE_CACHE');assert.equal(ui.getState().holdings,before.holdings);}
 ui.setAccountStatus('CONNECTED');ui.setHoldingsStatus('READY');assert.equal(ui.startHoldingSync(),true);assert.equal(ui.startHoldingSync(),false);ui.setAccountStatus('NOT_CONNECTED');assert.equal(ui.completeHoldingSync(),false,'stale completion cannot reconnect');assert.equal(ui.getState().details,before.details);assert.equal(ui.getState().overview,before.overview);
});
test('pending edits, cancellation, sync retry and external change resolution are Mock only',()=>{
 const ui=store(),before=ui.getState(),id=before.transactions[0].id;
 assert.equal(ui.selectTransaction(id),true);assert.equal(ui.getState().transactionDetail,id);
 assert.equal(ui.transactionAction(id,'edit',{amountInput:'600',dateKey:'2026-10-02',timePeriod:'after',feeRate:'0.15',feeInput:'0.90',note:'修改演示'}).ok,true);
 assert.equal(ui.getState().transactions[0].confirmedNav,null);assert.equal(ui.getState().transactions[0].amountInput,'600');
 assert.equal(ui.transactionAction(id,'edit',{amountInput:'-1'}).ok,false);assert.equal(ui.transactionAction(id,'cancel').ok,true);assert.equal(ui.transactionAction(id,'cancel').ok,false);assert.equal(ui.transactionAction(id,'edit',{}).ok,false);
 const failed=before.transactions[1].id;assert.equal(ui.transactionAction(failed,'sync').ok,true);assert.equal(ui.transactionAction(failed,'sync').ok,false);assert.equal(ui.transactionAction(failed,'edit',{}).ok,false);ui.transactionAction(failed,'fail');assert.match(ui.getState().transactions.find(t=>t.id===failed).syncError,/失败/);ui.transactionAction(failed,'sync');ui.transactionAction(failed,'complete');assert.ok(ui.getState().transactions.find(t=>t.id===failed).syncedAt);
 const changed=before.transactions[2].id;assert.equal(ui.transactionAction(changed,'rebase').ok,true);const resolved=ui.getState().transactions.find(t=>t.id===changed);assert.equal(resolved.transactionStatus,'READY_TO_SYNC');assert.equal(resolved.confirmedNav,before.transactions[2].confirmedNav);assert.match(resolved.note,/未执行真实计算/);
 for(const key of ['holdings','overview','details','plans'])assert.equal(ui.getState()[key],before[key]);
});
test('new UI contracts never store credentials or issue backend requests',()=>{
 const code=scripts(source).join('\n');assert.equal(/localStorage\.(?:setItem|getItem)\((?!['"](?:ndx-invest-frontend-v2-(?:theme|strategy)|ndx-fund-wallpapers)|storageKey)/.test(code),false);
 assert.ok(code.includes("const storageKey='ndx-invest-fund-display-names'"));
 for(const term of ['type="password"','Bearer ','accessToken','refreshToken','indexedDB','navigator.sendBeacon','Worker('])assert.equal(source.includes(term),false,term);
});

test('submitted sell record uses the sell rate and explicit Mock dates without share calculations',()=>{
 const ui=store(),before=ui.getState();assert.equal(ui.submitTrade({fundId:'gf-ndx100a',type:'卖出',amountInput:'300',date:'2026-10-02',timePeriod:'after',buyRateInput:'0.15',sellRateInput:'1.5',feeInput:'4.5'}).ok,true);
 const t=ui.getState().transactions[0];assert.equal(t.feeRate,'1.5');assert.equal(t.direction,'SELL');assert.equal(t.transactionStatus,'WAITING_NAV');assert.equal(t.confirmedNav,null);assert.equal(t.confirmedShares,null);assert.equal(ui.getState().holdings,before.holdings);
});

test('NDX100 strategy is index-wide and stores only threshold / planned amount tiers',()=>{
 assert.equal(source.includes('strategyFundSelect'),false);assert.equal(source.includes('加仓基金'),false);
 const ui=store();assert.ok(ui.getState().nd100Strategy.tiers.length>=1);for(const tier of ui.getState().nd100Strategy.tiers)assert.deepEqual(Object.keys(tier).sort(),['drawdownThreshold','plannedAmount']);const types=fs.readFileSync(path.join(root,'src/models/ui-models.d.ts'),'utf8');const model=types.match(/export interface Nd100StrategyUiModel \{([\s\S]*?)\n\}/)[1];assert.equal(model.includes('fundId'),false);assert.ok(model.includes('drawdownThreshold'));assert.ok(model.includes('plannedAmount'));
 const app=scripts(source).at(-1);assert.ok(app.includes("if (flow !== 'strategy') updateSelectedFund"));assert.ok(app.includes("const flowFundIndex = { buy: 1 }"));
 assert.ok(app.includes("$('strategyAddTier').disabled=false;$('strategySave').disabled=false;"));
});

test('strategy validates, sorts and projects NDX display without touching plans / transactions / holdings',()=>{
 const ui=store(),before=ui.getState(),unchanged=['plans','transactions','holdings','details','overview'];
 for(const tiers of [[],[{drawdownThreshold:'0',plannedAmount:'1'}],[{drawdownThreshold:'101',plannedAmount:'1'}],[{drawdownThreshold:'-10',plannedAmount:'1'}],[{drawdownThreshold:'10',plannedAmount:'0'}],[{drawdownThreshold:'10',plannedAmount:'100'},{drawdownThreshold:'10.0',plannedAmount:'200'}]]){assert.equal(ui.saveStrategy({tiers}).ok,false);assert.equal(ui.getState(),before);}
 assert.equal(ui.saveStrategy({fundId:'discard-me',tiers:[{drawdownThreshold:'30',plannedAmount:'3000',fundId:'discard-me'},{drawdownThreshold:'10',plannedAmount:'600'}]}).ok,true);
 const after=ui.getState();assert.deepEqual(JSON.parse(JSON.stringify(after.nd100Strategy)),{tiers:[{drawdownThreshold:'10',plannedAmount:'600'},{drawdownThreshold:'30',plannedAmount:'3000'}]});assert.equal(after.nd100.planText,'¥600');assert.equal(after.nd100.stageName,'第一档');assert.deepEqual(Array.from(after.nd100.stages,s=>s.range),['0%','-10%','-30%']);for(const key of unchanged)assert.equal(after[key],before[key]);
});
test('existing plan edit / pause / resume / delete update only UI metadata and never create BUY',()=>{
 const ui=store(),before=ui.getState(),plan=before.plans[0],fundId=plan.fundId;
 for(const draft of [{amountInput:'0',frequency:'monthly',monthDay:15},{amountInput:'100',frequency:'weekly',weekday:8},{amountInput:'100',frequency:'monthly',monthDay:32},{amountInput:'100',frequency:'invalid'}]){assert.equal(ui.updatePlan(fundId,draft).ok,false);assert.equal(ui.getState(),before);}
 assert.equal(ui.updatePlan(fundId,{amountInput:'650.50',frequency:'weekly',weekday:7,monthDay:15}).ok,true);assert.equal(ui.getState().plans[0].id,plan.id);assert.equal(ui.getState().plans[0].nextDateText,'2026-10-04 · Mock');assert.equal(ui.getState().details.find(f=>f.id===fundId).autoInvest,'每周 周日');
 assert.equal(ui.setPlanEnabled(fundId,false).ok,true);assert.equal(ui.getState().plans[0].enabled,false);assert.equal(ui.getState().holdings.find(f=>f.id===fundId).autoInvest,false);assert.equal(ui.getState().details.find(f=>f.id===fundId).autoInvest,'已暂停');
 assert.equal(ui.setPlanEnabled(fundId,true).ok,true);assert.equal(ui.getState().holdings.find(f=>f.id===fundId).autoInvest,true);
 assert.equal(ui.deletePlan(fundId).ok,true);assert.equal(ui.getState().plans.length,0);assert.equal(ui.getState().details.find(f=>f.id===fundId).autoInvest,'未设置');assert.equal(ui.deletePlan(fundId).ok,false);assert.equal(ui.setPlanEnabled(fundId,true).ok,false);
 assert.equal(ui.getState().transactions,before.transactions);assert.equal(ui.getState().overview,before.overview);for(const f of ui.getState().holdings){const old=before.holdings.find(x=>x.id===f.id);for(const key of ['amount','profit','availableSharesText','sellPresets'])assert.equal(f[key],old[key]);}
 assert.equal(ui.savePlan({fundId,amountInput:'100',frequency:'monthly',monthDay:31,weekday:3,enabled:true,firstDate:'2026-10-31'}).ok,true);assert.equal(ui.getState().plans[0].nextDateText,'2026-10-31 · Mock');assert.equal(ui.getState().transactions,before.transactions);
});

test('fund-local plan actions preserve other funds and cannot mutate trade history',()=>{
 const ui=store();ui.savePlan({fundId:'ef-sp500a',amountInput:'200',frequency:'monthly',monthDay:31,weekday:1,enabled:true});const before=ui.getState(),other=before.plans.find(p=>p.fundId==='ef-sp500a');ui.updatePlan('gf-ndx100a',{amountInput:'300',frequency:'daily',weekday:0,monthDay:0});ui.setPlanEnabled('gf-ndx100a',false);ui.deletePlan('gf-ndx100a');assert.equal(ui.getState().plans[0],other);assert.equal(ui.getState().holdings.find(f=>f.id==='ef-sp500a'),before.holdings.find(f=>f.id==='ef-sp500a'));assert.equal(ui.getState().transactions,before.transactions);assert.equal(ui.saveStrategy({tiers:[null]}).ok,false);
});

test('fund plan list supports additive creation and edits only the selected plan',()=>{
 const ui=store(),initial=ui.getState(),first=initial.plans[0];
 assert.equal(ui.savePlan({...first,amountInput:'250'},true).ok,true);const second=ui.getState().plans[1];assert.notEqual(second.id,first.id);assert.equal(ui.getState().plans[0],first);
 assert.equal(ui.updatePlan(first.fundId,{...second,amountInput:'350'},second.id).ok,true);assert.equal(ui.getState().plans[0],first);assert.equal(ui.getState().plans[1].amountInput,'350');
 assert.equal(ui.setPlanEnabled(first.fundId,false,second.id).ok,true);assert.equal(ui.getState().holdings.find(f=>f.id===first.fundId).autoInvest,true);
 assert.equal(ui.savePlan({...first,amountInput:'600'},false).ok,true);assert.equal(ui.getState().plans.length,2);
 const snapshot=ui.getState();assert.equal(ui.deletePlan(first.fundId,'missing').ok,false);assert.equal(ui.getState(),snapshot);assert.equal(ui.updatePlan('ef-sp500a',second,second.id).ok,false);
 assert.equal(ui.deletePlan(first.fundId,second.id).ok,true);assert.equal(ui.getState().plans.length,1);assert.equal(ui.getState().plans[0].id,first.id);assert.equal(ui.getState().transactions,initial.transactions);assert.equal(ui.getState().overview,initial.overview);
});

test('official NAV read fields survive fixture adaptation and cannot be edited through pending draft',()=>{
 const c={window:{}};vm.runInNewContext(scripts(source)[0],c);const row={id:'contract-nav',type:'买入',primaryText:'¥100',transactionStatus:'READY_TO_SYNC',expectedNavDate:'2026-10-02',confirmedNavDate:'2026-10-06',confirmedNav:'2.1234',confirmedShares:'47.01',shareDelta:'+47.01',confirmedAmount:null,syncError:'',syncedAt:null,createdShares:'0',currentShares:'0',createdCost:'¥0',currentCost:'¥0'};c.row=row;
 const read=vm.runInNewContext('v2TransactionModel(row)',c);for(const k of ['confirmedNavDate','confirmedNav','confirmedShares','shareDelta','confirmedAmount','createdShares','createdCost'])assert.equal(read[k],row[k]);assert.notEqual(read.expectedNavDate,read.confirmedNavDate);
 const ui=store();ui.submitTrade({fundId:'gf-ndx100a',type:'买入',amountInput:'100',date:'2026-10-09',timePeriod:'after',feeInput:'0'});const pending=ui.getState().transactions[0];assert.equal(pending.expectedNavDate,'2026-10-09');assert.equal(pending.confirmedNav,null);
 assert.equal(ui.transactionAction(pending.id,'edit',{amountInput:'110',dateKey:'2026-10-12',timePeriod:'before',feeRate:'0',feeInput:'0',note:'Mock',confirmedNav:'999',confirmedNavDate:'2026-10-12'}).ok,true);assert.equal(ui.getState().transactions[0].confirmedNav,null);assert.equal(ui.getState().transactions[0].confirmedNavDate,null);assert.equal(ui.getState().transactions[0].expectedNavDate,'2026-10-12');assert.equal(ui.transactionAction(pending.id,'confirm',{confirmedNav:'999'}).ok,false);
});
test('account and holding auth expiry recover together and stale completions remain rejected',()=>{
 const ui=store(),before=ui.getState();ui.startHoldingSync();ui.completeHoldingSync('AUTH_EXPIRED');assert.equal(ui.getState().yjbAccount.status,'AUTH_EXPIRED');assert.equal(ui.getState().holdingsState.status,'AUTH_EXPIRED');assert.equal(ui.startHoldingSync(),false);assert.equal(ui.completeHoldingSync('READY'),false);
 ui.startLogin();assert.equal(ui.completeLogin(false),true);assert.equal(ui.getState().yjbAccount.status,'LOGIN_FAILED');ui.startLogin();ui.completeLogin(true);assert.equal(ui.getState().holdingsState.status,'READY');assert.equal(ui.getState().holdings,before.holdings);
});
test('all seven transaction states reject non-applicable actions without changing holding authority',()=>{
 const actions=['edit','cancel','sync','complete','fail','rebase'];const permitted={WAITING_NAV:['edit','cancel'],READY_TO_SYNC:['sync'],SYNCING:['complete','fail'],SYNCED:[],SYNC_FAILED:['sync'],EXTERNAL_CHANGED:['rebase','cancel'],CANCELLED:[]};
 for(const [status,allowed] of Object.entries(permitted))for(const action of actions){const ui=store(),s=ui.getState(),t=s.transactions[0];ui.replaceUiState({...s,transactions:[{...t,transactionStatus:status}]});const before=ui.getState();const result=ui.transactionAction(t.id,action,{amountInput:'100',dateKey:'2026-10-02',timePeriod:'before',feeRate:'0',feeInput:'0'});assert.equal(result.ok,allowed.includes(action),status+' / '+action);for(const k of ['holdings','details','overview','plans'])assert.equal(ui.getState()[k],before[k]);if(!result.ok)assert.equal(ui.getState(),before);}
});
test('DCA creation appends independent plans by default and never generates BUY',()=>{
 const ui=store(),before=ui.getState();assert.equal(ui.savePlan({...before.plans[0],amountInput:'900'}).ok,true);assert.equal(ui.getState().plans.length,2);assert.equal(ui.getState().plans[0],before.plans[0]);assert.notEqual(ui.getState().plans[1].id,before.plans[0].id);assert.equal(ui.getState().transactions,before.transactions);ui.setPlanEnabled('gf-ndx100a',false,ui.getState().plans[1].id);assert.equal(ui.getState().holdings.find(f=>f.id==='gf-ndx100a').autoInvest,true);
});

test('backend ownership catalogue covers every declared core UI field and enforces NAV read contract',()=>{
 const spec=JSON.parse(fs.readFileSync(path.join(root,'contracts/backend-contract.json'))),types=fs.readFileSync(path.join(root,'src/models/ui-models.d.ts'),'utf8');assert.equal(spec.holdingAuthority,'YJB');assert.equal(spec.historyAuthority,'LOCAL');
 for(const m of types.matchAll(/export interface (\w+) \{/g)){let depth=1,end=m.index+m[0].length;const start=end;for(;end<types.length&&depth;end++){if(types[end]==='{')depth++;if(types[end]==='}')depth--;}let body=types.slice(start,end-1);while(/\{[^{}]*\}/.test(body))body=body.replace(/\{[^{}]*\}/g,'TYPE');for(const f of body.matchAll(/(?:^|[;\n])\s*(?:readonly\s+)?(\w+)\??\s*:/g)){const key=m[1]+'.'+f[1];assert.ok(spec.fieldOwnership[key],key);assert.ok(['YJB','LOCAL','MARKET','DERIVED'].includes(spec.fieldOwnership[key].owner));}}
 for(const field of spec.userReadonlyTransactionFields)assert.equal(spec.userEditableTransactionFields.includes(field),false);assert.equal(spec.navConfirmationRules.requiredSource,'OFFICIAL_HISTORY');assert.equal(spec.navConfirmationRules.estimatedPriceAccepted,false);assert.equal(spec.navConfirmationRules.userConfirmationAccepted,false);
 const sample=spec.examples.officialNav;for(const field of spec.navConfirmationRules.requiredEvidence)assert.ok(sample[field]);assert.equal(sample.source,'OFFICIAL_HISTORY');assert.notEqual(spec.examples.pending.expectedNavDate,sample.navDate);assert.equal(spec.examples.pending.confirmedNav,null);assert.ok(Number(sample.nav)>0);
 const strategy=spec.fieldOwnership['Nd100StrategyUiModel.tiers'];assert.equal(strategy.owner,'LOCAL');assert.equal(spec.fieldOwnership['TransactionUiModel.confirmedNav'].owner,'MARKET');
});

test('initial and reset Mock homepage agree with index strategy thresholds and planned amount',()=>{
 const ui=store();for(let i=0;i<2;i++){const s=ui.getState(),drawdown=Math.abs(parseFloat(s.nd100.drawdownText));const active=s.nd100Strategy.tiers.filter(t=>Number(t.drawdownThreshold)<=drawdown).at(-1);assert.equal(s.nd100.planText,'¥'+active.plannedAmount);assert.equal(s.nd100.stages.filter(t=>t.active).length,1);assert.equal(s.nd100.stages.find(t=>t.active).range,'-'+active.drawdownThreshold+'%');assert.equal(s.nd100.stageName,s.nd100.stages.find(t=>t.active).label);ui.saveStrategy({tiers:[{drawdownThreshold:'5',plannedAmount:'200'}]});ui.reset();}
});

test('presentation rounding is exact half-up and does not round NAV or mutate state',()=>{
 const context={window:{}};vm.runInNewContext(scripts(source)[0],context);const format=context.window.V2Display;
 for(const [value,expected] of [['1.005','1.01'],['-1.005','-1.01'],['¥99999999999999999999.995','¥100000000000000000000.00'],['+12.34567%','+12.35%'],['--','--'],['0','0.00']])assert.equal(format(value),expected);
 assert.ok(pages['持仓'].includes('l3Nav.textContent = data.nav;'));assert.ok(pages['持仓'].includes('l3AvgNav.textContent = data.avgNav;'));
 const ui=store(),before=ui.getState();format(before.details[0].cost);assert.equal(ui.getState(),before);
});
test('Android back consumes transitional states and only explicit root result can finish',()=>{
 const back=fs.readFileSync(path.join(root,'android-back.js'),'utf8');assert.ok(back.includes("mode === 'transitioning'"));assert.ok(back.includes('if (!doc) return true;'));
 const host=fs.readFileSync(path.join(project,'app/src/main/kotlin/com/ndxinvest/app/frontendv2/FrontendV2Host.kt'),'utf8');assert.ok(host.includes('handled == "false"'));assert.ok(host.includes('!loaded || backPending'));assert.ok(host.includes('BuildConfig.DEBUG && BuildConfig.FLAVOR == "prototype"'));
});


test('confirmation display uses observation date, waits for NAV and never guesses old timestamps',()=>{
 const c={window:{}};vm.runInNewContext(scripts(source)[0],c);
 const display=row=>{c.row=row;return vm.runInNewContext('v2ConfirmationDateText(row)',c);};
 assert.equal(display({transactionStatus:'WAITING_NAV',confirmedNav:null,expectedNavDate:'2026-09-28'}),'待公布净值');
 assert.equal(display({confirmedNav:'4.5700',confirmedNavDate:'2026-09-28',confirmationDate:'2026-09-29'}),'2026-09-29');
 assert.equal(display({confirmedNav:'4.5700',confirmedNavDate:'2026-09-28',navConfirmedAt:'2026-10-07T17:00:00Z'}),'2026-10-08');
 assert.equal(display({confirmedNav:'4.5700',confirmedNavDate:'2026-09-28',syncedAt:'2026-10-03T11:05:06Z',updatedAt:'2026-10-03T11:05:06Z'}),'未记录');
 assert.equal(display({transactionStatus:'CANCELLED',confirmedNav:null}),'--');
 assert.equal(source.includes("row('预计净值日期',t.expectedNavDate)"),false);
});

test('record timestamp presentation uses Beijing time without mutating stored timestamps',()=>{
 const c={window:{}};vm.runInNewContext(scripts(source)[0],c);const format=c.window.V2DateTime;
 for(const [input,expected] of [
  ['2026-10-02T15:51:31.256Z','2026-10-02 23:51:31'],
  ['2026-10-03T11:05:06.398Z','2026-10-03 19:05:06'],
  ['2026-10-02T18:30:00Z','2026-10-03 02:30:00'],
  ['2026-10-03T00:00:00+08:00','2026-10-03 00:00:00'],
  ['2026-10-03T00:00:00','--'],['2026-99-99T00:00:00Z','--'],
  [null,'--'],['','--'],['尚未同步','尚未同步'],['2026-10-02 09:35 · Mock','2026-10-02 09:35 · Mock']
 ])assert.equal(format(input),expected,input);
 assert.equal(format('2026-10-02T18:30:00Z',true),'2026-10-03');
 assert.ok(pages['记录'].includes('parent.V2DateTime(row.time)'));
 const ui=store(),before=ui.getState();format(before.yjbAccount.lastSyncAt);assert.equal(ui.getState(),before);
 assert.equal(source.includes("row('初始市值'"),false);assert.equal(source.includes("row('确认 NAV'"),false);
 assert.ok(source.includes("row('确认净值',t.confirmedNav)"));
 for(const term of ["row('确认份额',V2Display(t.confirmedShares))","row('份额变化',V2Display(t.shareDelta))"])assert.ok(source.includes(term));
});

test('record status categories are semantic and note inputs are removed without removing storage fields',()=>{
 const code=pages['记录'].match(/const statusClass = row => \{[\s\S]*?\n  \};/)[0];
 const categories={WAITING_NAV:'status-waiting',READY_TO_SYNC:'status-waiting',SYNCING:'status-waiting',SYNCED:'status-done',SYNC_FAILED:'status-error',EXTERNAL_CHANGED:'status-warning',CANCELLED:'status-error'};
 for(const [status,expected] of Object.entries(categories))assert.equal(vm.runInNewContext(code+'statusClass(row)',{row:{transactionStatus:status}}),expected);
 assert.equal(vm.runInNewContext(code+'statusClass(row)',{row:{kind:'INITIAL_POSITION',status:'已导入'}}),'status-done');
 assert.equal((pages['记录'].match(/class="filter[^\"]*" data-filter=/g)||[]).length,7);
 assert.ok(pages['记录'].includes('data-filter="初始持仓"'));assert.ok(pages['记录'].includes("type === '初始持仓导入' ? '初始持仓'"));
 const shell=source.replace(/<script>window\.V2Pages=[\s\S]*?;<\/script>/,'');for(const id of ['buyRemark','gapNote'])assert.equal(shell.includes(id),false);
 assert.equal(pages['记录'].includes('${escape(row.secondaryText)}'),false);assert.equal(pages['记录'].includes('${escape(row.note)}'),false);
 assert.equal(shell.includes("row('备注',t.note"),false);
 const ui=store(),before=ui.getState();assert.ok(Object.hasOwn(before.transactions[0],'note'));assert.equal(ui.getState(),before);
});

test('retired preview and plan surfaces are absent from the packaged UI',()=>{
 for(const term of ['renderColorPreview','recordColorPreview','V2RecordPreview','record-preview-card'])assert.equal(pages['记录'].includes(term),false,term);
 for(const term of ['id="plansPage"','loadDetailedPlan','planDaySheet'])assert.equal(source.includes(term),false,term);
 assert.equal(pages['持仓'].includes('function showToast'),false);
 assert.ok(pages['持仓'].includes('html[data-theme="dark"] .action-icon {background:transparent}'));
});

test('fund addition contract separates local placeholders, YJB holdings and uncertain writes',()=>{
 const spec=JSON.parse(fs.readFileSync(path.join(root,'contracts/production-fund-addition.json'),'utf8').replace(/^\uFEFF/,''));
 assert.equal(spec.holdingAuthority,'YJB');assert.equal(spec.search.path,'/content/v1/search/fund');assert.equal(spec.search.method,'POST');
 assert.deepEqual(spec.add.input,['fundId','accountId','shares','unitCost']);assert.match(spec.add.zero,/no POST/);assert.match(spec.add.duplicate,/never overwrite/);
 assert.match(spec.uncertainDelivery,/no repeat POST/);assert.match(spec.firstBuy,/automatic synchronization after official NAV confirmation/);
 for(const rule of ['YJB totals','XIRR','SELL','DCA creation'])assert.ok(spec.placeholder.exclude.includes(rule));
 assert.match(spec.initialImport,/does not synthesize another import/);
});
