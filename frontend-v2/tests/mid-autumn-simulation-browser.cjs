// Consume actual native simulation projections. No live account or financial bridge.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
const root=path.resolve(__dirname,'..'),native=path.resolve(root,'../yjb/read/build/reports/mid-autumn'),out=path.join(root,'artifacts/mid-autumn-simulation');
fs.mkdirSync(out,{recursive:true});
const html=fs.readFileSync(path.join(root,'index.html'),'utf8').replace('<script>window.V2Pages=','<script>'+fs.readFileSync(path.join(root,'production-read.js'),'utf8')+'</script>\n<script>'+fs.readFileSync(path.join(root,'production-market.js'),'utf8')+'</script>\n<script>window.V2Pages=');
const report=n=>JSON.parse(fs.readFileSync(path.join(native,n+'.json'),'utf8'));
const payload=(n,label='final')=>structuredClone(report(n).events.find(e=>e.checkpoint===label).payload);
const results=[];
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.BROWSER_EXECUTABLE||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
 async function check(name,data,body){
  const context=await browser.newContext({viewport:{width:390,height:844}}),page=await context.newPage(),errors=[],external=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/*',r=>{const u=r.request().url();if(u.startsWith('http://127.0.0.1:4197/'))return r.fulfill({status:200,contentType:'text/html',body:html});if(/^https?:/.test(u)&&!/kaspersky-labs/.test(u))external.push(u);return r.abort();});
  await page.addInitScript(()=>{window.commands=[];window.NDXYjbRead={post:x=>commands.push(x),prepareCreate:()=>{throw Error('Creation forbidden in display-only simulation');},acknowledgeCreate:()=>false};window.NDXMarketRead={post:()=>{}};});
  try{
   await page.goto('http://127.0.0.1:4197/');await page.frameLocator('#framePortfolio').locator('.total-amount').waitFor();
   await page.evaluate(p=>YjbReadApply(p),data);
   const records=page.frameLocator('#frameRecords');
   const value=title=>page.locator('.settings-row').filter({has:page.locator('.settings-row-title',{hasText:new RegExp('^'+title+'$')})}).locator('.settings-row-end').innerText();
   const open=async tx=>{await page.evaluate(()=>V2App.openRecords());await records.locator('[data-record-id="'+tx.id+'"]').click();await page.locator('#settingsScroll .settings-row').first().waitFor();};
   await body({page,records,value,open});
   assert.deepEqual(errors,[]);assert.deepEqual(external,[]);assert.deepEqual(await page.evaluate(()=>commands),[],'display must not dispatch trade operations');
   await page.screenshot({path:path.join(out,name+'.png'),fullPage:true});results.push({name,pass:true});console.log('PASS '+name);
  }catch(e){results.push({name,pass:false,error:e.message});await page.screenshot({path:path.join(out,name+'-failure.png'),fullPage:true}).catch(()=>{});throw e;}
  finally{await context.close();}
 }
 try{
  await check('waiting-exact-nav',payload('01-buy-sell','BUY created; no NAV'),async({page,value,open})=>{
   const tx=payload('01-buy-sell','BUY created; no NAV').transactions[0];await open(tx);assert.equal(await value('确认日期'),'待公布净值');
   assert.doesNotMatch(await page.locator('#settingsScroll').innerText(),/预计净值日期/);assert.equal(await page.locator('#settingsScroll input').count(),0);assert.equal(await page.locator('[data-gap-action=tx-sync]').count(),0);
  });
  await check('buy-ready-high-precision',payload('01-buy-sell','BUY NAV confirmed'),async({page,value,open})=>{
   const tx=payload('01-buy-sell','BUY NAV confirmed').transactions[0];await open(tx);
   assert.equal(await value('确认日期'),'2026-09-28');assert.equal(await value('确认净值'),'4.5700');assert.equal(await value('确认份额'),'21.88');assert.equal(await value('份额变化'),'+21.88');assert.equal(await value('手续费'),'¥0.00');
   const raw=await page.evaluate(()=>V2Store.getState().transactions[0]);assert.equal(raw.confirmedNavDate,'2026-09-24');assert.equal(raw.confirmedShares,tx.confirmedShares);
  });
  await check('buy-synced-light-and-dark',payload('01-buy-sell','BUY synced and GET verified'),async({page,value,open})=>{
   const p=payload('01-buy-sell','BUY synced and GET verified');await open(p.transactions[0]);assert.match(await page.locator('#settingsScroll').innerText(),/已同步/);assert.equal(await value('确认日期'),'2026-09-28');
   await page.evaluate(()=>V2Store.setTheme('dark'));await page.waitForFunction(()=>document.documentElement.dataset.theme==='dark');assert.equal(await value('确认份额'),'21.88');
   assert.equal((await page.evaluate(()=>V2Store.getState())).details[0].cost,p.data.details[0].cost,'display must retain platform precision');
  });
  await check('sell-synced-confirmation-date',payload('01-buy-sell'),async({page,value,open})=>{
   const p=payload('01-buy-sell'),tx=p.transactions.find(t=>t.kind==='SELL');await open(tx);assert.equal(await value('确认日期'),'2026-09-29');assert.equal(await value('确认净值'),'4.5700');assert.equal(await value('确认份额'),'21.88');assert.equal(await value('份额变化'),'-21.88');assert.equal(await value('手续费'),'¥0.00');assert.equal(await value('确认卖出金额'),'¥99.99');
   assert.equal((await page.evaluate(()=>V2Store.getState())).transactions.find(t=>t.kind==='SELL').netAmount,'99.991600');
  });
  await check('after-cutoff-next-target',payload('02-after-cutoff'),async({value,open})=>{
   const tx=payload('02-after-cutoff').transactions[0];await open(tx);assert.equal(tx.confirmedNavDate,'2026-09-28');assert.equal(await value('确认日期'),'2026-09-29');
  });
  await check('daily-five-record-filter-and-state',payload('03-daily-five'),async({page,records})=>{
   await page.evaluate(()=>V2App.openRecords());await records.locator('[data-filter="定投"]').click();assert.equal(await records.locator('.record').count(),2);assert.match(await records.locator('.record').first().innerText(),/¥5/);
   await records.locator('[data-filter="买入"]').click();assert.equal(await records.locator('.record').count(),0);
   const s=await page.evaluate(()=>V2Store.getState());assert.deepEqual(s.transactions.map(t=>t.dateKey).sort(),['2026-09-24','2026-09-28']);assert.ok(s.transactions.every(t=>t.kind==='BUY'&&t.transactionStatus==='SYNCED'));assert.equal(s.plans[0].nextDateDisplay,'2026.09.29');assert.equal(s.plans[0].nextStatusText,'待公布净值');
  });
  await check('external-holding-conflict',payload('14-external-change'),async({page,value,open})=>{
   await open(payload('14-external-change').transactions[0]);assert.match(await page.locator('#settingsScroll').innerText(),/创建交易时份额/);assert.match(await value('创建交易时份额'),/100/);assert.match(await value('当前养基宝份额'),/101/);assert.equal(await page.locator('[data-gap-action=rebase]').count(),0);assert.equal(await page.locator('#settingsScroll input').count(),0);
  });
  const failed=payload('18-readback-offline'),event=report('18-readback-offline').events.find(e=>e.buy?.some(t=>t.transactionStatus==='SYNCING'&&t.syncError));
  assert.ok(event,'native pending projection required');failed.transactions=event.buy;
  await check('ambiguous-post-pending-cannot-cancel',failed,async({page,open})=>{
   await open(failed.transactions[0]);assert.match(await page.locator('#settingsScroll').innerText(),/同步中/);assert.equal(await page.locator('[data-native-verify]').count(),1);const cancel=page.locator('[data-gap-action=cancel]');assert.ok(await cancel.count()===0||await cancel.isDisabled(),'uncertain POST cannot expose an enabled cancellation');
  });
  await check('cleared-fund-catalogue-and-history',payload('24-full-clear-preserves-fund'),async({page,records})=>{
   const s=await page.evaluate(()=>V2Store.getState());assert.equal(s.holdingsState.status,'EMPTY');assert.equal(s.holdings.length,0);assert.equal(s.fundCatalog.length,1);assert.equal(s.fundCatalog[0].availableSharesText,'0');
   await page.locator('.global-nav-item[data-page="持仓"]').click();const holdings=page.frameLocator('#framePortfolio');await holdings.locator('#gapClosedFundBuy').waitFor();assert.equal(await holdings.locator('.fund-card').count(),0);
   await page.evaluate(()=>V2App.openRecords());assert.equal(await records.locator('.record').count(),1);
  });
 }finally{fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({environment:'host Edge; native loopback simulation projections; no live account',results},null,2));await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
