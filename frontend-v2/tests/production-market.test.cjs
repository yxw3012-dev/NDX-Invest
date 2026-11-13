const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const root=path.resolve(__dirname,'..'),source=fs.readFileSync(path.join(root,'index.html'),'utf8');
const read=fs.readFileSync(path.join(root,'production-read.js'),'utf8'),market=fs.readFileSync(path.join(root,'production-market.js'),'utf8');
function runtime(){
 const commands=[],window={crypto:require('node:crypto').webcrypto},context={window,queueMicrotask(){},document:{createElement:()=>({}),getElementById:()=>null,head:{append(){}},addEventListener(){}},v2PlanStatus:()=>''};
 for(const s of [...source.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].slice(0,2))vm.runInNewContext(s[1],context);
 window.NDXYjbRead={post:a=>commands.push(a)};vm.runInNewContext(read,context);window.NDXMarketRead={post:a=>commands.push('market:'+a)};vm.runInNewContext(market,context);
 return {w:window,commands};
}
const data={indexText:'30,807.93',changeText:'+306.37',changePercentText:'+1.00%',marketStatus:'已休市 · 腾讯',updatedText:'行情 2026-10-03 04:00',fxText:'6.7050',fxChangeText:'-0.02%',drawdownText:'-13.60%',drawdownRate:'-0.136',historyText:'历史范围 1996-06-06—2026-10-02',drawdownBasisText:'基于 1996-06-06 至 2026-10-02 的历史最高收盘价',performance:Array.from({length:4},()=>['+10.00%','+3.00%','-20.00%','15.00%']),refreshing:false};
test('production market begins with unknown facts, retains Mock in standalone prototype',()=>{
 const {w}=runtime(),s=w.V2Store.getState();assert.equal(s.nd100.indexText,'--');assert.equal(s.nd100.fxText,'--');assert.equal(s.nd100.drawdownText,'--');assert.equal(s.nd100.planText,'--');assert.equal(s.nd100.stages.some(x=>x.active),false);
 assert.ok(s.nd100.performance.every(p=>p.every(v=>v==='--')));assert.equal(s.holdings.length,0);
 assert.match(source,/25,612\.84/);assert.equal(source.includes('window.MarketReadApply ='),false);
});
test('market apply only changes market projection and derives local strategy tiers',()=>{
 const {w}=runtime();w.V2Store.setTheme('dark');const before=w.V2Store.getState();
 w.MarketReadApply({nd100:data,dataSourceState:{indexStatus:'腾讯',fxStatus:'腾讯'},holdings:[{id:'bad'}],transactions:[{id:'bad'}]});const s=w.V2Store.getState();
 assert.equal(s.nd100.indexText,data.indexText);assert.equal(s.nd100.stageName,'第一档');assert.equal(s.nd100.planText,'¥500');assert.equal(s.nd100.stages[1].active,true);assert.equal(s.theme,'dark');assert.deepEqual(s.holdings,before.holdings);assert.deepEqual(s.transactions,before.transactions);assert.deepEqual(s.plans,before.plans);
});
test('YJB account refresh does not overwrite real market data',()=>{
 const {w}=runtime();w.MarketReadApply({nd100:data});
 w.YjbReadApply({data:{holdings:[],details:[],overview:{}},accountStatus:'CONNECTED',holdingsStatus:'EMPTY'});
 assert.equal(w.V2Store.getState().nd100.indexText,data.indexText);assert.equal(w.V2Store.getState().nd100.fxText,data.fxText);
});
test('strategy save reprojects current raw drawdown and never reintroduces Mock display',()=>{
 const {w}=runtime();w.MarketReadApply({nd100:data});const result=w.V2Store.saveStrategy({tiers:[{drawdownThreshold:'10',plannedAmount:'1234.56'},{drawdownThreshold:'20',plannedAmount:'2000'}]});
 assert.equal(result.ok,true);assert.equal(result.message.includes('Mock'),false);const s=w.V2Store.getState();assert.equal(s.nd100.planText,'¥1234.56');assert.equal(s.nd100.planLabel,'计划投入');assert.equal(s.nd100.indexText,data.indexText);assert.ok(Math.abs(s.nd100.markerPercent-68)<1e-8);
 const invalid=w.V2Store.saveStrategy({tiers:[{drawdownThreshold:'10',plannedAmount:'0'}]});assert.equal(invalid.ok,false);assert.equal(w.V2Store.getState().nd100.planText,'¥1234.56');
});
test('missing drawdown facts cannot activate a guessed strategy tier',()=>{
 const {w}=runtime();w.MarketReadApply({nd100:{...data,drawdownText:'--',drawdownRate:null,drawdownBasisText:'历史数据暂不可用',historyText:'1996—2026'}});const s=w.V2Store.getState();
 assert.equal(s.nd100.planText,'--');assert.equal(s.nd100.stageName,'--');assert.equal(s.nd100.stages.some(x=>x.active),false);
 w.V2Store.saveStrategy({tiers:[{drawdownThreshold:'5',plannedAmount:'100'}]});assert.equal(w.V2Store.getState().nd100.planText,'--');
});
test('cached and independently failed channels remain explicit without changing native commands',()=>{
 const {w,commands}=runtime();w.MarketReadApply({nd100:{...data,fxText:'--',fxChangeText:'--',marketStatus:'缓存 · 已休市 · 腾讯'},dataSourceState:{indexStatus:'缓存 · 腾讯',fxStatus:'读取失败 · 暂无缓存'}});
 assert.equal(w.V2Store.getState().nd100.indexText,data.indexText);assert.equal(w.V2Store.getState().nd100.fxText,'--');assert.deepEqual(commands,[]);
 assert.equal(w.V2Store.getState().dataSourceState.fxStatus,'读取失败 · 暂无缓存');
});
test('available history drives drawdown and plan without an inception coverage flag',()=>{
 const {w}=runtime();w.MarketReadApply({nd100:data});let s=w.V2Store.getState();
 assert.equal(s.nd100.drawdownText,'-13.60%');assert.equal(s.nd100.planText,'¥500');assert.equal(s.nd100.performance.length,4);
 assert.match(s.nd100.drawdownBasisText,/1996-06-06/);assert.equal(Object.hasOwn(s.nd100,'historyComplete'),false);
 w.MarketReadApply({nd100:{...data,drawdownText:'0.00%',drawdownRate:'0'}});s=w.V2Store.getState();
 assert.equal(s.nd100.stageName,'观察');assert.equal(s.nd100.planText,'¥0');assert.equal(s.nd100.stages[0].active,true);
});
test('public adapter cannot read credentials, request arbitrary URLs or execute transactions',()=>{
 for(const forbidden of ['fetch(','XMLHttpRequest','localStorage','NDXYjbRead','fund_hold','buy-create','sell-create','Room','Ledger'])assert.equal(market.includes(forbidden),false,forbidden);
 const kotlin=fs.readFileSync(path.join(root,'../app/src/main/kotlin/com/ndxinvest/app/market/MarketReadController.kt'),'utf8');
 assert.match(kotlin,/CredentialResolver \{null\}/);assert.equal(/token\(|protocolKey|YjbReadApi|Log\.|println\(/.test(kotlin),false);assert.match(kotlin,/command!="boot"&&command!="refresh"/);
 const host=fs.readFileSync(path.join(root,'../app/src/main/kotlin/com/ndxinvest/app/frontendv2/FrontendV2Host.kt'),'utf8');assert.match(host,/production-market\.js/);assert.match(host,/marketController\?\.close/);
});
