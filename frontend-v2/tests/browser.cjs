const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
const artifacts=path.join(__dirname,'../artifacts');fs.mkdirSync(artifacts,{recursive:true});
const base=process.env.FRONTEND_V2_URL || 'http://127.0.0.1:4173';
const backScript=fs.readFileSync(path.join(__dirname,'../android-back.js'),'utf8');
const results=[];
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.BROWSER_EXECUTABLE || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
 async function check(name,body,options={}) {
  if(process.env.FRONTEND_V2_TEST_FILTER&&!new RegExp(process.env.FRONTEND_V2_TEST_FILTER).test(name))return;
  const context=await browser.newContext({viewport:{width:390,height:844},...options});
  const page=await context.newPage(), errors=[], appRequests=[], environmentRequests=[];
  page.setDefaultTimeout(5000);
  await page.route('**/*',r=>/^https?:/.test(r.request().url())&&!r.request().url().startsWith(base)?r.abort():r.continue());
  page.on('pageerror',e=>errors.push(e.message));
  page.on('request',r=>{const url=r.url();if (/kaspersky-labs\.com/.test(url)) environmentRequests.push(url);else if (/^https?:/.test(url)&&!url.startsWith(base))appRequests.push(url);});
  try {
   await page.goto(base);await page.frameLocator('#framePortfolio').locator('.total-amount').waitFor();
   await body(page);
   assert.deepEqual(errors,[],'uncaught script errors');assert.deepEqual(appRequests,[],'application external requests');
   results.push({name,pass:true,errors,applicationExternalRequests:appRequests,blockedHostAntivirusRequests:environmentRequests.length});console.log('PASS '+name);
  }catch(error){results.push({name,pass:false,message:error.message,errors});console.log('FAIL '+name+': '+error.message.slice(0,300));await page.screenshot({path:path.join(artifacts,name+'-failure.png')}).catch(()=>{});}
  finally{await context.close();}
 }
 const holdings=p=>p.frameLocator('#framePortfolio');
 // Display-name provenance is added lazily; compare all financial fields unchanged.
 const state=p=>p.evaluate(()=>JSON.parse(JSON.stringify(V2Store.getState(),(key,value)=>['originalName','originalFundName'].includes(key)?undefined:value)));
 async function navigate(p,name){await p.locator(`.global-nav-item[data-page="${name}"]`).click();await p.waitForTimeout(350);}
 async function holdingHome(p){await navigate(p,'持仓');}
 async function trade(p,type){await holdingHome(p);await holdings(p).locator('.action-button.'+type).click();await p.waitForTimeout(300);}
 async function enter2(p){await holdings(p).locator('#sheetHandleZone').focus();await holdings(p).locator('#sheetHandleZone').press('Enter');await p.waitForTimeout(700);assert.equal(await p.locator('#framePortfolio').evaluate(f=>f.contentWindow.V2Portfolio.getMode()),'level2');}
 async function enter3(p,id='gf-ndx100a'){await holdings(p).locator(`.fund-card[data-fund-id="${id}"]`).click();await p.waitForTimeout(1000);assert.equal(await p.locator('#framePortfolio').evaluate(f=>f.contentWindow.V2Portfolio.getMode()),'level3');}
 async function saveShot(p,name){await p.waitForTimeout(500);await p.screenshot({path:path.join(artifacts,name+'.png')});}
 await check('01-pages-navigation',async p=>{
  const home=p.frameLocator('#frameNDX100');await home.locator('[data-period="10Y"]').click();
  const expected=await home.locator('#metricsPages').getAttribute('style');
  await saveShot(p,'home-light');
  for(const name of ['持仓','我的','NDX100','我的','持仓','NDX100']){await navigate(p,name);assert.equal(await p.locator('.page-frame.is-active').count(),1);}
  assert.equal(await home.locator('#metricsPages').getAttribute('style'),expected,'home period selection should persist');
  await home.locator('#refreshButton').click();assert.match(await home.locator('#updatedText').innerText(),/Mock/);
  await holdingHome(p);await saveShot(p,'holdings-light');
  const frames=await p.locator('iframe').count();assert.equal(frames,4);
 });
 await check('02-buy-date-fee',async p=>{
  await trade(p,'buy');await p.locator('#buyAmount').fill('1000');await p.locator('#buyFeeRate').fill('1');
  assert.equal(await p.locator('#buyFeeAmount').innerText(),'9.90元');
  await p.locator('#dateRow').click();await p.locator('#buyMonthWheel').focus();await p.keyboard.press('Home');await p.keyboard.press('ArrowDown');
  await p.locator('#buyDayWheel').focus();await p.keyboard.press('End');await p.locator('#buyPeriodWheel').focus();await p.keyboard.press('End');
  await saveShot(p,'date-wheel');await p.locator('#buyDateConfirm').click();assert.equal(await p.locator('#buyDateISO').inputValue(),'2026-02-28');assert.equal(await p.locator('#buyTimePeriod').inputValue(),'after');
  await p.waitForTimeout(400);assert.equal(await p.locator('#buyPage').evaluate(n=>n.scrollTop),0,'date wheel focus must not scroll overlay');assert.equal(await p.locator('.buy-page-shell').evaluate(n=>n.getBoundingClientRect().y),0,'transaction header must remain on screen');
  await p.locator('#dateRow').click();await p.keyboard.press('Escape');assert.equal(await p.locator('#buyDateSheet').getAttribute('aria-hidden'),'true');
  const before=await state(p);await p.locator('#buySubmit').click();const after=await state(p);
  assert.equal(after.transactions.length,before.transactions.length+1);assert.deepEqual(after.holdings,before.holdings);assert.deepEqual(after.overview,before.overview);
  assert.equal(after.transactions[0].timePeriod,'after');assert.equal(after.transactions[0].feeInput,'9.90');
  await p.waitForTimeout(400);await saveShot(p,'buy');await p.locator('#buyBack').click();assert.equal(await p.locator('#buyPage').getAttribute('aria-hidden'),'true');
 });
 await check('03-invalid-fee-input',async p=>{
  await trade(p,'buy');await p.locator('#buyAmount').fill('100');await p.locator('#buyFeeRate').fill('-1');const before=await state(p);await p.locator('#buySubmit').click();assert.equal((await state(p)).transactions.length,before.transactions.length,'invalid buy rate must not submit');
  await p.locator('#buyFeeRate').fill('0');await p.locator('#buyAmount').fill('0');await p.locator('#buySubmit').click();assert.equal((await state(p)).transactions.length,before.transactions.length);
 });
 await check('04-sell-rates',async p=>{
  await trade(p,'sell');await p.locator('.sell-quick-actions [data-ratio=".5"]').click();assert.equal(await p.locator('#buyAmount').inputValue(),'5592.735');
  await p.locator('#sellRateToggle').click();await p.locator('.sell-rate-option').first().click();assert.equal(await p.locator('.sell-rate-option[aria-checked="true"]').count(),1);
  await p.locator('#sellFeeRate').fill('101');const before=await state(p);await p.locator('#buySubmit').click();assert.equal((await state(p)).transactions.length,before.transactions.length,'invalid sell rate must not submit');
  await p.locator('#sellFeeRate').fill('0.5');await p.locator('#buySubmit').click();const after=await state(p);assert.equal(after.transactions[0].sellRateInput,'0.5');assert.deepEqual(after.details,before.details);await saveShot(p,'sell');
 });
 await check('05-fund-name-sheet',async p=>{
  await trade(p,'buy');await p.locator('#buyAmount').fill('123');await p.locator('#fundSelect').click();await p.waitForTimeout(2300);await saveShot(p,'picker-light');
  await p.locator('#buyFundWheel').focus();await p.keyboard.press('End');await p.waitForTimeout(400);await p.keyboard.press('Enter');await p.waitForTimeout(1600);
  assert.equal(await p.locator('#buyFundPicker').getAttribute('aria-hidden'),'true');assert.equal(await p.locator('#buyFundName').innerText(),'华夏纳指100联接');assert.equal(await p.locator('#buyAmount').inputValue(),'123');
  await p.locator('#fundSelect').click();await p.waitForTimeout(2300);await p.locator('#buyFundWheel').focus();await p.keyboard.press('Home');await p.keyboard.press('Escape');await p.waitForTimeout(450);assert.equal(await p.locator('#buyFundName').innerText(),'华夏纳指100联接','cancel restores committed selection');
  assert.equal(await p.locator('.fund-morph-overlay').count(),0);assert.equal(await p.locator('.buy-page-shell').evaluate(n=>n.inert),false);
 });
 await check('06-inline-recurring',async p=>{
  await trade(p,'buy');await p.locator('[data-mode="recurring"]').click();assert.equal(await p.locator('#recurringPanel').getAttribute('aria-hidden'),'false');assert.equal(await p.locator('#dateRow').isVisible(),false);
  await p.locator('#cycleRow').click();await p.locator('#buyFrequencyWheel').focus();await p.keyboard.press('Home');await p.keyboard.press('ArrowDown');await p.keyboard.press('ArrowDown');
  await p.locator('#buyExecutionWheel').focus();await p.keyboard.press('End');await saveShot(p,'recurring-wheel');await p.locator('#buyRecurringConfirm').click();
  assert.equal(await p.locator('#cycleValue').innerText(),'每两周');assert.equal(await p.locator('#dayValue').innerText(),'星期日');
  await p.waitForTimeout(400);assert.equal(await p.locator('#buyPage').evaluate(n=>n.scrollTop),0,'cycle wheel focus must not scroll overlay');
  await p.locator('#buyAmount').fill('600');const before=await state(p);await p.locator('#buySubmit').click();const after=await state(p);
  assert.equal(after.plans.at(-1).frequency,'biweekly');assert.deepEqual(after.transactions,before.transactions);await saveShot(p,'recurring');
  await p.locator('[data-mode="single"]').click();assert.equal(await p.locator('#dateRow').isVisible(),true);
 });
 await check('07-holding-motion-sort-details',async p=>{
  await holdingHome(p);await enter2(p);await saveShot(p,'level2-light');
  // Pager uses wheel intent and preserved snap animation.
  await holdings(p).locator('#cardsStage').hover();await p.mouse.wheel(0,120);await p.waitForTimeout(750);
  const id=await p.locator('#framePortfolio').evaluate(f=>f.contentWindow.V2Portfolio.selectedId());assert.notEqual(id,'gf-ndx100a');
  await enter3(p,id);assert.equal(await holdings(p).locator('#l3Profit').innerText(),id==='hx-ndx100-link'?'-¥280.00':'+¥1,840.00');await saveShot(p,'level3-light');
  await holdings(p).locator('#level3Settings').click();await holdings(p).locator('#hsBack').click();
  await holdings(p).locator('#level3Back').click();await p.waitForTimeout(1000);await holdings(p).locator('#sortButton').click();
  const handles=holdings(p).locator('.sort-handle'), first=await handles.first().boundingBox(), last=await handles.last().boundingBox();
  await p.mouse.move(first.x+first.width/2,first.y+first.height/2);await p.mouse.down();await p.mouse.move(last.x+last.width/2,last.y+last.height/2,{steps:15});await p.mouse.up();await p.waitForTimeout(250);await holdings(p).locator('#doneButton').click();
  await holdings(p).locator('#sheetHandleZone').focus();await p.keyboard.press('Enter');await p.waitForTimeout(550);await navigate(p,'我的');await navigate(p,'持仓');assert.equal(await p.locator('#globalNav').evaluate(n=>n.inert),false);
 });
 await check('08-color-rule',async p=>{
  await holdingHome(p);await enter2(p);
  const colors=await holdings(p).locator('.fund-card .profit').evaluateAll(nodes=>nodes.map(node=>({text:node.textContent,color:getComputedStyle(node).color})));
  assert.notEqual(colors.find(row=>row.text.startsWith('-')).color,colors.find(row=>row.text.startsWith('+')).color,'holding loss must differ from profit');
  await holdings(p).locator('#sheetHandleZone').focus();await p.keyboard.press('Enter');await p.waitForTimeout(550);await holdings(p).locator('.action-button.buy').click();await p.locator('#fundSelect').click();await p.waitForTimeout(2300);
  assert.equal(await p.locator('.fund-name-option').count(),3);assert.equal(await p.locator('.buy-fund-card-summary').count(),0,'name-only selector does not duplicate financial cards');
 });
 await check('09-record-filter',async p=>{
  const s=await state(p);s.transactions.push({...s.transactions[0],id:'adjust-demo',type:'调仓',fundName:'调整演示'});await p.evaluate(s=>V2Store.replaceUiState(s),s);
  await holdingHome(p);await holdings(p).locator('.action-button.record').click();await p.waitForTimeout(350);const records=p.frameLocator('#frameRecords');
  await records.locator('[data-filter="调整"]').click();assert.equal(await records.locator('.record').count(),1,'调整 filter must display 调仓 UI models');await saveShot(p,'records');await records.locator('.back').click();
 });
 await check('10-theme-and-profile',async p=>{
  await navigate(p,'我的');const profile=p.frameLocator('#frameMine');assert.equal(await profile.locator('.setting-row').filter({hasText:'定投计划'}).count(),0,'user removed standalone plan entry');
  await profile.locator('.setting-row').filter({hasText:'深色模式'}).click();await p.locator('[data-theme-choice="dark"]').click();await p.waitForTimeout(500);await p.locator('#settingsBack').click();await saveShot(p,'profile-dark');
  for(const id of ['frameNDX100','framePortfolio','frameMine','frameRecords'])assert.equal(await p.locator('#'+id).evaluate(f=>f.contentDocument.documentElement.dataset.theme),'dark');
  await holdingHome(p);await enter2(p);await saveShot(p,'level2-dark');await enter3(p);await saveShot(p,'level3-dark');await holdings(p).locator('#level3Back').click();await p.waitForTimeout(950);await holdings(p).locator('#sheetHandleZone').focus();await p.keyboard.press('Enter');await p.waitForTimeout(550);
  await p.reload();await p.frameLocator('#frameMine').locator('.setting-row').first().waitFor();assert.equal(await p.locator('html').getAttribute('data-theme'),'dark','theme persistence');
  await navigate(p,'我的');await profile.locator('.setting-row').filter({hasText:'数据说明'}).click();assert.match(await p.locator('#settingsScroll').innerText(),/Mock/);await p.keyboard.press('Escape');
 });
 await check('11-model-update-detail-retention',async p=>{
  await holdingHome(p);await enter2(p);await enter3(p);const s=await state(p);s.holdings[1].amount='¥MODEL';s.details[1].amount='¥DETAIL';s.overview.amount='¥TOTAL';await p.evaluate(s=>V2Store.replaceUiState(s),s);await p.waitForTimeout(100);
  assert.equal(await p.locator('#framePortfolio').evaluate(f=>f.contentWindow.V2Portfolio.getMode()),'level3','data update should preserve selected detail view');assert.equal(await holdings(p).locator('#l3Amount').innerText(),'¥DETAIL');
 });
 await check('12-empty-from-detail-recovery',async p=>{
  await holdingHome(p);await enter2(p);await enter3(p);const s=await state(p);s.status='empty';s.holdings=[];s.details=[];s.transactions=[];s.overview={amount:'—',metrics:['—','—','—','—']};await p.evaluate(s=>V2Store.replaceUiState(s),s);await p.waitForTimeout(100);
  assert.equal(await p.locator('#globalNav').evaluate(n=>n.inert),false,'empty detail must not trap hidden navigation');assert.equal(await p.locator('#framePortfolio').evaluate(f=>f.contentWindow.V2Portfolio.getMode()),'level1');await navigate(p,'我的');await navigate(p,'持仓');
  await p.evaluate(()=>V2Store.reset());await p.waitForTimeout(100);await holdings(p).locator('.action-button.buy').click();await p.locator('#fundSelect').click();await p.waitForTimeout(2300);await p.keyboard.press('Escape');await p.waitForTimeout(500);await p.locator('#buyBack').click();
 });
 await check('13-single-fund-selection',async p=>{
  const s=await state(p);s.holdings=s.holdings.slice(0,1);s.details=s.details.slice(0,1);await p.evaluate(s=>V2Store.replaceUiState(s),s);await holdingHome(p);await holdings(p).locator('.action-button.buy').click();assert.equal(await p.locator('#buyFundName').innerText(),'易方达标普500A');assert.equal(await p.locator('#buySubmit').isDisabled(),false);
  await p.locator('#fundSelect').click();await p.waitForTimeout(2300);await p.locator('.fund-name-option').click();await p.locator('#fundPickerConfirm').click();await p.waitForTimeout(600);assert.equal(await p.locator('#buyFundPicker').getAttribute('aria-hidden'),'true');
 });
 await check('14-loading-error-empty',async p=>{
  await holdingHome(p);const original=await state(p);
  for(const status of ['loading','error','empty']){const s={...original,status,statusText:'测试状态 '+status,holdings:[],details:[],transactions:[],overview:{amount:'—',metrics:['—','—','—','—']}};await p.evaluate(s=>V2Store.replaceUiState(s),s);await p.waitForTimeout(100);assert.equal(await holdings(p).locator('.fund-card').count(),0);await navigate(p,'NDX100');await navigate(p,'持仓');await holdings(p).locator('.action-button.buy').click();assert.equal(await p.locator('#buySubmit').isDisabled(),true);await p.locator('#fundSelect').click();assert.equal(await p.locator('#buyFundPicker').getAttribute('aria-hidden'),'true');await p.locator('#buyBack').click();}
  await p.evaluate(()=>V2Store.reset());assert.equal(await holdings(p).locator('.fund-card').count(),3);
 });
 await check('15-responsive-reduced-motion',async p=>{
  for(const viewport of [{width:320,height:568},{width:375,height:812},{width:768,height:1024},{width:844,height:390}]){await p.setViewportSize(viewport);await p.waitForTimeout(100);assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);await saveShot(p,'viewport-'+viewport.width);}
  await p.setViewportSize({width:375,height:812});await trade(p,'buy');await p.locator('#fundSelect').click();await p.waitForTimeout(250);await p.keyboard.press('Escape');await p.waitForTimeout(200);assert.equal(await p.locator('#buyFundPicker').getAttribute('aria-hidden'),'true');await p.locator('#buyBack').click();
 },{reducedMotion:'reduce'});
 await check('16-system-theme',async p=>{assert.equal(await p.locator('html').getAttribute('data-theme'),'dark');await p.emulateMedia({colorScheme:'light'});await p.waitForTimeout(500);assert.equal(await p.locator('html').getAttribute('data-theme'),'light');},{colorScheme:'dark'});
 await check('17-cancel-and-live-state-during-sheet',async p=>{
  await trade(p,'buy');
  await p.locator('#fundSelect').click();await p.waitForTimeout(150);await p.keyboard.press('Escape');await p.waitForTimeout(550);
  assert.equal(await p.locator('#buyFundPicker').getAttribute('aria-hidden'),'true');assert.equal(await p.locator('.fund-morph-overlay').count(),0);
  await p.locator('#fundSelect').click();await p.waitForTimeout(150);await p.keyboard.press('Escape');await p.waitForTimeout(550);
  assert.equal(await p.locator('#buyFundPicker').getAttribute('aria-hidden'),'true');
  await p.locator('#fundSelect').click();await p.waitForTimeout(2300);const s=await state(p);s.status='empty';s.holdings=[];s.details=[];await p.evaluate(s=>V2Store.replaceUiState(s),s);await p.waitForTimeout(200);
  assert.equal(await p.locator('#buyFundPicker').getAttribute('aria-hidden'),'true');assert.equal(await p.locator('.buy-page-shell').evaluate(n=>n.inert),false);await p.locator('#buyBack').click();
  await p.evaluate(()=>V2Store.reset());await p.waitForTimeout(100);await holdings(p).locator('.action-button.buy').click();await p.locator('#fundSelect').click();await p.waitForTimeout(2300);await p.locator('.fund-name-option[data-index="1"]').click();await p.waitForTimeout(100);await p.keyboard.press('Escape');await p.waitForTimeout(500);
  assert.equal(await p.locator('#buyFundPicker').getAttribute('aria-hidden'),'true');assert.equal(await p.locator('.fund-morph-overlay').count(),0);
 });
 await check('18-dark-and-negative-home-state',async p=>{
  await p.evaluate(()=>V2Store.setTheme('dark'));await p.waitForTimeout(500);await holdingHome(p);await enter2(p);
  const colors=await holdings(p).locator('.fund-card .profit').evaluateAll(nodes=>nodes.map(n=>({text:n.textContent,color:getComputedStyle(n).color})));
  assert.notEqual(colors.find(n=>n.text.startsWith('-')).color,colors.find(n=>n.text.startsWith('+')).color,'dark holding loss must use loss token');
  await holdings(p).locator('#sheetHandleZone').focus();await p.keyboard.press('Enter');await p.waitForTimeout(550);await navigate(p,'NDX100');
  const home=p.frameLocator('#frameNDX100'), positiveColor=await home.locator('.hero-change-number').evaluate(n=>getComputedStyle(n).color);
  const s=await state(p);s.nd100.changeText='-213.47';s.nd100.changePercentText='-0.84%';s.nd100.performance[0]=['-10%','-2%','-15%','25%'];await p.evaluate(s=>V2Store.replaceUiState(s),s);await p.waitForTimeout(100);
  assert.notEqual(await home.locator('.hero-change-number').evaluate(n=>getComputedStyle(n).color),positiveColor,'negative index change must update tone');
  assert.equal(await home.locator('.metrics-page').first().locator('.metric-value').first().getAttribute('class'),'metric-value negative');await saveShot(p,'home-negative-dark');
 });
 await check('19-missing-detail-and-identity',async p=>{
  await holdingHome(p);await enter2(p);await enter3(p);const s=await state(p);s.details=[];await p.evaluate(s=>V2Store.replaceUiState(s),s);
  assert.equal(await holdings(p).locator('#l3Amount').innerText(),'—','missing detail must not retain stale fund data');
  await holdings(p).locator('#level3Back').click();await p.waitForTimeout(950);await holdings(p).locator('#sheetHandleZone').focus();await p.keyboard.press('Enter');await p.waitForTimeout(550);await navigate(p,'我的');
  await p.frameLocator('#frameMine').locator('.identity-card').click();assert.equal(await p.locator('#settingsTitle').innerText(),'个人信息');await p.locator('#settingsBack').click();
 });
 await check('20-native-back-adapter',async p=>{
  assert.equal(await p.evaluate(backScript),false);
  await trade(p,'buy');await p.locator('#dateRow').click();assert.equal(await p.evaluate(backScript),true);await p.waitForTimeout(350);
  assert.equal(await p.locator('#buyDateSheet').getAttribute('aria-hidden'),'true');
  assert.equal(await p.locator('#buyPage').getAttribute('aria-hidden'),'false');
  assert.equal(await p.evaluate(backScript),true);await p.waitForTimeout(350);
  await enter2(p);await enter3(p);assert.equal(await p.evaluate(backScript),true);await p.waitForTimeout(1000);
  assert.equal(await p.locator('#framePortfolio').evaluate(f=>f.contentWindow.V2Portfolio.getMode()),'level2');
  assert.equal(await p.evaluate(backScript),true);await p.waitForTimeout(650);
  assert.equal(await p.locator('#framePortfolio').evaluate(f=>f.contentWindow.V2Portfolio.getMode()),'level1');
  assert.equal(await p.evaluate(backScript),false);
 });
 async function chooseDemo(p,kind,status){const selector=p.locator('[data-gap-demo="'+kind+'"]');if(!await selector.isVisible())await p.locator('.v2-gap-demo summary').click();await selector.selectOption(status);}
 await check('21-yjb-account-lifecycle',async p=>{
  await navigate(p,'我的');await p.frameLocator('#frameMine').locator('.identity-card').click();await p.locator('[data-gap-action="account"]').click();
  assert.equal(await p.locator('#settingsTitle').innerText(),'养基宝账户');
  await chooseDemo(p,'account','NOT_CONNECTED');assert.match(await p.locator('#settingsScroll').innerText(),/未连接/);
  await p.locator('[data-gap-action="login"]').click();assert.equal(await p.locator('[data-gap-action="login"]').isDisabled(),true);assert.match(await p.locator('#settingsScroll').innerText(),/正在连接/);
  await p.waitForTimeout(1100);assert.match(await p.locator('#settingsScroll').innerText(),/138\*\*\*\*2048/);await saveShot(p,'yjb-account-connected');
  await chooseDemo(p,'account','LOGIN_FAILED');assert.match(await p.locator('#settingsScroll').innerText(),/连接失败/);
  await p.locator('[data-gap-action="login"]').click();await p.waitForTimeout(1100);assert.equal((await state(p)).yjbAccount.status,'CONNECTED');
  await chooseDemo(p,'account','AUTH_EXPIRED');assert.match(await p.locator('#settingsScroll').innerText(),/登录已过期/);
  assert.equal(await p.evaluate(backScript),true);assert.equal(await p.locator('#settingsTitle').innerText(),'个人信息');await p.locator('#settingsBack').click();
  assert.equal(await p.locator('#settingsPage').getAttribute('aria-hidden'),'true');assert.equal(await p.locator('#frameMine').evaluate(f=>f.contentDocument.activeElement.className),'identity-card');
 });
 await check('22-holding-sync-states',async p=>{
  await holdingHome(p);const before=await state(p);
  for(const status of ['NOT_CONNECTED','INITIAL_LOADING','EMPTY','ERROR','AUTH_EXPIRED']){
   await p.evaluate(status=>V2Store.setHoldingsStatus(status),status);await holdings(p).locator('#gapHoldingStatus').waitFor();assert.equal(await holdings(p).locator('#gapHoldingStatus').isVisible(),true);
   assert.equal(await holdings(p).locator('.action-button.buy').evaluate(n=>n.closest('[inert]')!==null),true);
   if(status==='EMPTY'){assert.match(await holdings(p).locator('#gapHoldingStatus').innerText(),/请先在养基宝添加基金/);await saveShot(p,'holding-empty');}
   if(status==='ERROR'){await holdings(p).locator('#gapHoldingAction').click();await p.waitForTimeout(1100);assert.equal((await state(p)).holdingsState.status,'READY');}
  }
  for(const status of ['REFRESHING','OFFLINE_CACHE']){await p.evaluate(status=>V2Store.setHoldingsStatus(status),status);assert.equal(await holdings(p).locator('#gapHoldingStatus').isVisible(),false);assert.equal(await holdings(p).locator('.action-button.buy').evaluate(n=>n.closest('[inert]')!==null),false);assert.deepEqual((await state(p)).holdings,before.holdings);}
  assert.equal(await holdings(p).locator('#gapHoldingStatus').innerText(),'');assert.equal((await state(p)).holdingsState.isCached,true);assert.ok((await state(p)).holdingsState.lastSyncAt);await saveShot(p,'holding-cache');
  await p.evaluate(()=>V2Store.setHoldingsStatus('NOT_CONNECTED'));await holdings(p).locator('#gapHoldingAction').click();assert.equal(await p.locator('#settingsTitle').innerText(),'养基宝账户');await p.locator('#settingsBack').click();
 });
 async function openRecord(p,id){await holdingHome(p);await holdings(p).locator('.action-button.record').click();await p.frameLocator('#frameRecords').locator(`[data-record-id="${id}"]`).click();await p.locator('#settingsTitle').filter({hasText:'交易详情'}).waitFor();}
 await check('23-transaction-waiting-edit-cancel',async p=>{
  const before=await state(p);await openRecord(p,'demo-tx-1');assert.match(await p.locator('#settingsScroll').innerText(),/等待正式净值/);
  await p.locator('[data-gap-action="edit"]').click();await p.locator('#gapAmount').fill('650');await p.locator('#gapPeriod').selectOption('after');assert.equal(await p.locator('#gapNote').count(),0);await p.locator('#v2TransactionEdit button').click();
  let t=(await state(p)).transactions[0];assert.equal(t.amountInput,'650');assert.equal(t.timePeriod,'after');assert.equal(t.confirmedNav,null);
  await p.locator('[data-gap-action="cancel"]').click();assert.match(await p.locator('#settingsScroll').innerText(),/已撤销/);assert.equal(await p.locator('[data-gap-action="edit"]').count(),0);assert.deepEqual((await state(p)).holdings,before.holdings);
  await p.keyboard.press('Escape');assert.equal(await p.locator('#settingsPage').getAttribute('aria-hidden'),'true');assert.equal(await p.locator('#frameRecords').evaluate(n=>n.inert),false);assert.equal(await p.locator('#frameRecords').getAttribute('aria-hidden'),'false');
  await p.frameLocator('#frameRecords').locator('.back').click();assert.equal(await p.locator('#globalNav').evaluate(n=>n.inert),false);
 });
 await check('24-transaction-failure-and-external-change',async p=>{
  const before=await state(p);await openRecord(p,'demo-tx-2');assert.match(await p.locator('#settingsScroll').innerText(),/同步暂时不可用/);
  await p.locator('[data-gap-action="tx-sync"]').click();assert.equal(await p.locator('[data-gap-action="tx-sync"]').isDisabled(),true);await p.waitForTimeout(1100);assert.match(await p.locator('#settingsScroll').innerText(),/同步完成时间/);await saveShot(p,'transaction-synced');await p.locator('#settingsBack').click();
  await p.frameLocator('#frameRecords').locator('[data-record-id="demo-tx-3"]').click();assert.match(await p.locator('#settingsScroll').innerText(),/创建交易时份额/);await saveShot(p,'transaction-external-changed');await p.locator('[data-gap-action="rebase"]').click();assert.match(await p.locator('#settingsScroll').innerText(),/待同步养基宝/);assert.deepEqual((await state(p)).details,before.details);assert.deepEqual((await state(p)).holdings,before.holdings);
 });
 await check('25-data-status-and-dark-contract-panels',async p=>{
  await p.evaluate(()=>V2Store.setTheme('dark'));await navigate(p,'我的');await p.frameLocator('#frameMine').locator('.setting-row').filter({hasText:'数据状态'}).click();assert.match(await p.locator('#settingsScroll').innerText(),/NDX 数据/);assert.match(await p.locator('#settingsScroll').innerText(),/汇率数据/);
  await chooseDemo(p,'holding','OFFLINE_CACHE');assert.match(await p.locator('#settingsScroll').innerText(),/缓存 · Mock/);await saveShot(p,'data-status-dark');
  await p.locator('[data-gap-action="holding-sync"]').click();assert.equal(await p.locator('[data-gap-action="holding-sync"]').isDisabled(),true);await p.waitForTimeout(1100);assert.equal((await state(p)).holdingsState.status,'READY');
  await p.locator('[data-gap-action="account"]').click();assert.equal(await p.locator('#settingsPage').evaluate(n=>n.classList.contains('is-dark')),true);await saveShot(p,'account-dark');await p.keyboard.press('Escape');assert.equal(await p.locator('#settingsTitle').innerText(),'数据状态');await p.keyboard.press('Escape');
  await openRecord(p,'demo-tx-3');await saveShot(p,'transaction-dark');assert.equal(await p.locator('#settingsPage').evaluate(n=>n.classList.contains('is-dark')),true);
 });
 await check('26-fund-settings-records-and-inline-dca',async p=>{
  await holdingHome(p);await enter2(p);await enter3(p);await holdings(p).locator('#level3Settings').click();
  assert.match(await holdings(p).locator('#hsScroll').innerText(),/最后同步/);assert.match(await holdings(p).locator('#hsScroll').innerText(),/定投状态/);await saveShot(p,'fund-settings');await p.evaluate(()=>V2Store.setPlanEnabled('gf-ndx100a',false,V2Store.getState().plans[0].id));assert.match(await holdings(p).locator('#hsScroll').innerText(),/已暂停/);
  await holdings(p).locator('#gapFundRecords').click();await p.waitForTimeout(300);assert.equal(await p.frameLocator('#frameRecords').locator('.record:visible').count(),1);
  await p.frameLocator('#frameRecords').locator('[data-filter="卖出"]').click();assert.equal(await p.frameLocator('#frameRecords').locator('.record:visible').count(),0);await p.frameLocator('#frameRecords').locator('[data-filter="全部"]').click();assert.equal(await p.frameLocator('#frameRecords').locator('.record:visible').count(),1);
  await p.frameLocator('#frameRecords').locator('.back').click();await holdings(p).locator('#gapFundDca').click();assert.equal(await p.locator('#buyFundName').innerText(),'广发纳指100A');assert.equal(await p.locator('#buyPage').getAttribute('aria-hidden'),'false');await p.locator('#buyBack').click();assert.equal(await holdings(p).locator('#holdingSettings').getAttribute('aria-hidden'),'false');assert.equal(await p.evaluate(backScript),true);
  await p.evaluate(()=>V2Store.setTheme('dark'));await holdings(p).locator('#level3Settings').click();await saveShot(p,'fund-settings-dark');
 });
 await check('27-transaction-contract-status-coverage',async p=>{
  await openRecord(p,'demo-tx-1');
  for(const status of ['READY_TO_SYNC','SYNCING','SYNCED','SYNC_FAILED','EXTERNAL_CHANGED','CANCELLED','WAITING_NAV']){await chooseDemo(p,'transaction',status);assert.equal((await state(p)).transactions[0].transactionStatus,status);assert.equal(await p.locator('#settingsScroll').evaluate(n=>n.scrollWidth<=n.clientWidth),true);}
  await p.locator('[data-gap-action="edit"]').click();await p.locator('#gapRate').fill('101');await p.evaluate(()=>V2Store.setTheme('dark'));assert.equal(await p.locator('#gapRate').inputValue(),'101','unsaved edit survives unrelated theme state updates');await p.locator('#v2TransactionEdit button').click();assert.match(await p.locator('#gapEditError').innerText(),/费率/);
 },{viewport:{width:320,height:740},reducedMotion:'reduce'});

 await check('28-native-back-record-details',async p=>{
  await openRecord(p,'demo-tx-1');assert.equal(await p.evaluate(backScript),true);assert.equal(await p.locator('#settingsPage').getAttribute('aria-hidden'),'true');assert.equal(await p.locator('#frameRecords').getAttribute('aria-hidden'),'false');assert.equal(await p.evaluate(backScript),true);assert.equal(await p.locator('#frameRecords').getAttribute('aria-hidden'),'true');assert.equal(await p.evaluate(backScript),false);await enter2(p);await enter3(p);await holdings(p).locator('#level3Settings').click();await holdings(p).locator('#gapFundRecords').click();assert.equal(await p.frameLocator('#frameRecords').locator('.record:visible').count(),1);assert.equal(await p.evaluate(backScript),true);await p.waitForTimeout(100);await p.evaluate(()=>V2App.openRecords());assert.equal(await p.frameLocator('#frameRecords').locator('.record:visible').count(),3,'native return clears fund-specific filter');
 });
 await check('29-index-wide-strategy',async p=>{
  await navigate(p,'我的');await p.frameLocator('#frameMine').locator('.setting-row').filter({hasText:'回撤加仓策略'}).click();
  assert.equal(await p.locator('#strategyFundSelect').count(),0);assert.ok((await state(p)).nd100Strategy.tiers.every(t=>!('fundId' in t)));assert.equal(await p.locator('#strategySave').isDisabled(),false);assert.ok(await p.locator('.strategy-tier').count()>=1);await saveShot(p,'strategy-index-light');
  await p.locator('#strategyPage .feature-back').click();await p.evaluate(()=>{const s=V2Store.getState();V2Store.replaceUiState({...s,status:'empty',holdings:[],details:[]});V2Store.setTheme('dark');});
  await p.frameLocator('#frameMine').locator('.setting-row').filter({hasText:'回撤加仓策略'}).click();assert.equal(await p.locator('#strategyPage').getAttribute('aria-hidden'),'false');await saveShot(p,'strategy-index-dark-no-funds');assert.equal(await p.evaluate(backScript),true);
  await p.evaluate(()=>V2Store.reset());await trade(p,'buy');const awaitedFundName=await p.locator('#buyFundName').innerText();assert.ok((await state(p)).holdings.some(f=>f.name===awaitedFundName),'buy displays a valid selectable fund');await p.locator('#fundSelect').click();await p.waitForTimeout(2300);assert.equal(await p.locator('#buyFundPicker').getAttribute('aria-hidden'),'false');await p.keyboard.press('Escape');
 });
 await check('30-strategy-edit-save-validation-home',async p=>{
  const before=await state(p);await navigate(p,'我的');await p.frameLocator('#frameMine').locator('.setting-row').filter({hasText:'回撤加仓策略'}).click();
  assert.equal(await p.locator('.strategy-tier').count(),4);await p.locator('#strategyAddTier').click();assert.equal(await p.locator('.strategy-tier').count(),5);
  await p.locator('.strategy-tier').last().locator('[data-strategy-field="drawdownThreshold"]').fill('5');await p.keyboard.press('Tab');assert.equal(await p.locator('.strategy-tier').first().locator('[data-strategy-field="drawdownThreshold"]').inputValue(),'5');assert.equal(await p.locator('.strategy-tier-badge').first().innerText(),'第一档');
  await p.locator('.strategy-tier').nth(1).locator('[data-strategy-field="plannedAmount"]').fill('650');await p.locator('#strategySave').click();let s=await state(p);assert.equal(s.nd100.planText,'¥650');assert.equal(s.nd100.stageName,'第二档');assert.deepEqual(s.holdings,before.holdings);assert.deepEqual(s.transactions,before.transactions);await saveShot(p,'strategy-edit-light');
  await p.locator('.strategy-tier').first().locator('.strategy-delete').click();while(await p.locator('.strategy-tier').count()>1)await p.locator('.strategy-tier').last().locator('.strategy-delete').click();assert.equal(await p.locator('.strategy-delete').isDisabled(),true);
  const old=await state(p);await p.locator('[data-strategy-field="drawdownThreshold"]').fill('0');await p.locator('#strategySave').click();assert.match(await p.locator('.strategy-tier .strategy-tier-preview[role="alert"]').innerText(),/100%/);assert.deepEqual((await state(p)).nd100Strategy,old.nd100Strategy);
  await p.locator('[data-strategy-field="drawdownThreshold"]').fill('10');await p.locator('[data-strategy-field="plannedAmount"]').fill('0');await p.locator('#strategySave').click();assert.match(await p.locator('.strategy-tier .strategy-tier-preview[role="alert"]').innerText(),/金额/);
  await p.locator('[data-strategy-field="plannedAmount"]').fill('500');await p.locator('#strategyAddTier').click();await p.locator('.strategy-tier').last().locator('[data-strategy-field="drawdownThreshold"]').fill('10.0');await p.keyboard.press('Tab');await p.locator('#strategySave').click();assert.match(await p.locator('.strategy-tier .strategy-tier-preview[role="alert"]').first().innerText(),/重复/);
  await p.locator('.strategy-tier').last().locator('[data-strategy-field="drawdownThreshold"]').fill('25');await p.keyboard.press('Tab');await p.evaluate(()=>V2Store.setTheme('dark'));await p.locator('#strategySave').click();assert.equal(await p.locator('#strategySaved').isVisible(),false);await saveShot(p,'strategy-edit-dark');await p.locator('#strategyPage .feature-back').click();await navigate(p,'NDX100');
  const home=p.frameLocator('#frameNDX100');assert.deepEqual(await home.locator('#drawdownRangeLabels span').allTextContents(),['0%','-10%','-25%']);assert.equal(await home.locator('.plan-inline-value').innerText(),'¥500');assert.equal(await home.locator('#currentStageName').innerText(),'第一档');await saveShot(p,'strategy-home-linked');
 });

 async function manage(p){await holdingHome(p);await enter2(p);await enter3(p);await holdings(p).locator('#level3Settings').click();await holdings(p).locator('#gapFundPlan').click();assert.equal(await p.locator('#settingsTitle').innerText(),'定投计划');await p.locator('[data-plan-id]').first().click();}
 async function cycle(p,index,last=true){await p.locator('#cycleRow').click();await p.locator('#buyFrequencyWheel').focus();await p.keyboard.press('Home');for(let i=0;i<index;i++)await p.keyboard.press('ArrowDown');if(index){await p.locator('#buyExecutionWheel').focus();await p.keyboard.press(last?'End':'Home');}await p.locator('#buyRecurringConfirm').click();}
 await check('31-fund-local-dca-management',async p=>{
  const before=await state(p);await manage(p);assert.equal(await p.locator('#buyTitle').innerText(),'定投计划管理');assert.equal(await p.locator('#fundSelect').isDisabled(),true);assert.equal(await p.locator('#buyModeSection').isVisible(),false);assert.equal(await p.locator('#recurringPanel').getAttribute('aria-hidden'),'false');
  await p.locator('#buyAmount').fill('0');await p.locator('#buySubmit').click();assert.match(await p.locator('#inlinePlanFeedback').innerText(),/金额/);assert.equal((await state(p)).plans[0].amountInput,'500');
  await p.locator('#buyAmount').fill('650.50');await cycle(p,1);await p.locator('#buySubmit').click();assert.equal((await state(p)).plans[0].nextDateText,'2026-10-04 · Mock');assert.equal(await p.locator('#buyAmount').inputValue(),'650.50');await saveShot(p,'dca-management-light');
  await p.locator('#planToggle').click();assert.equal((await state(p)).plans[0].enabled,false);assert.match(await holdings(p).locator('#hsScroll').innerText(),/已暂停/);await p.locator('#planToggle').click();assert.equal((await state(p)).plans[0].enabled,true);
  await p.locator('#buyAmount').fill('730');await p.evaluate(()=>V2Store.setTheme('dark'));assert.equal(await p.locator('#buyAmount').inputValue(),'730');await cycle(p,3);await p.locator('#buySubmit').click();assert.equal((await state(p)).plans[0].nextDateText,'2026-10-31 · Mock');await saveShot(p,'dca-management-dark');
  await cycle(p,2);await p.locator('#buySubmit').click();assert.match((await state(p)).plans[0].nextDateText,/隔周/);await cycle(p,0);await p.locator('#buySubmit').click();assert.equal((await state(p)).plans[0].nextDateText,'2026-10-03 · Mock');
  await p.locator('#buyAmount').fill('0');await p.locator('#buySubmit').click();await p.locator('#inlinePlanDelete').click();await p.waitForTimeout(50);assert.equal((await state(p)).plans.length,0);assert.equal(await p.locator('#buyPage').getAttribute('aria-hidden'),'true');assert.match(await holdings(p).locator('#hsScroll').innerText(),/未设置/);assert.equal(await holdings(p).locator('#gapFundPlan').count(),1);assert.match(await p.locator('#settingsScroll').innerText(),/暂无定投计划/);await p.locator('#settingsBack').click();assert.deepEqual((await state(p)).transactions,before.transactions);
  await holdings(p).locator('#gapFundDca').click();assert.equal(await p.locator('#buyAmount').inputValue(),'');assert.equal(await p.locator('#fundSelect').isDisabled(),true);assert.equal(await p.locator('#buyModeSection').isVisible(),true);assert.equal(await p.locator('#inlinePlanDelete').isVisible(),false);await p.locator('[data-mode=recurring]').click();await p.locator('#buyAmount').fill('800');await p.locator('#buySubmit').click();assert.equal((await state(p)).plans[0].amountInput,'800');await p.waitForTimeout(50);await holdings(p).locator('#gapFundPlan').click();await p.locator('[data-plan-id]').click();assert.equal(await p.locator('#buyAmount').inputValue(),'800');assert.equal(await p.locator('#plansPage').count(),0);
 });
 await check('32-dca-manager-native-return-and-narrow-layout',async p=>{
  await manage(p);assert.equal(await p.locator('#buyPage').evaluate(n=>n.scrollWidth<=n.clientWidth),true);await p.locator('#cycleRow').click();assert.equal(await p.evaluate(backScript),true);assert.equal(await p.locator('#buyPage').getAttribute('aria-hidden'),'false');assert.equal(await p.evaluate(backScript),true);await p.waitForTimeout(50);assert.equal(await p.locator('#buyPage').getAttribute('aria-hidden'),'true');assert.equal(await p.locator('#settingsTitle').innerText(),'定投计划');assert.equal(await p.evaluate(backScript),true);assert.equal(await holdings(p).locator('#holdingSettings').getAttribute('aria-hidden'),'false');await holdings(p).locator('#gapFundDca').click();assert.equal(await p.locator('#buyTitle').innerText(),'买入');assert.equal(await p.locator('#inlinePlanDelete').isVisible(),false);
 },{viewport:{width:320,height:740},reducedMotion:'reduce'});
 await check('33-daily-plan-cycle-normalization',async p=>{
  await p.evaluate(()=>V2Store.updatePlan('gf-ndx100a',{amountInput:'500',frequency:'daily',weekday:0,monthDay:0}));await manage(p);await cycle(p,1,false);await p.locator('#buySubmit').click();assert.equal((await state(p)).plans[0].nextDateText,'2026-10-05 · Mock');await cycle(p,3,false);await p.locator('#buySubmit').click();assert.equal((await state(p)).plans[0].nextDateText,'2026-11-01 · Mock');assert.match(await p.locator('#firstDateValue').innerText(),/2026-11-01/);
 });

 await check('34-plan-list-and-separate-creation',async p=>{
  const initial=await state(p);await holdingHome(p);await enter2(p);await enter3(p);await holdings(p).locator('#level3Settings').click();await holdings(p).locator('#gapFundPlan').click();assert.equal(await p.locator('[data-plan-id]').count(),1);assert.match(await p.locator('#settingsScroll').innerText(),/500/);await saveShot(p,'fund-plan-list-light');await p.locator('#settingsBack').click();
  await holdings(p).locator('#gapFundDca').click();assert.equal(await p.locator('[data-mode=single]').getAttribute('aria-pressed'),'true');await p.locator('[data-mode=recurring]').click();assert.equal(await p.locator('#buyAmount').inputValue(),'');await p.locator('#buyAmount').fill('250');await p.locator('#buySubmit').click();await p.waitForTimeout(50);assert.equal((await state(p)).plans.length,2);assert.equal((await state(p)).plans[0].amountInput,'500');assert.deepEqual((await state(p)).transactions,initial.transactions);
  await holdings(p).locator('#gapFundPlan').click();assert.equal(await p.locator('[data-plan-id]').count(),2);const second=(await state(p)).plans[1];await p.locator('[data-plan-id="'+second.id+'"]').click();assert.equal(await p.locator('#buyAmount').inputValue(),'250');await cycle(p,1);await p.locator('#buyAmount').fill('350');await p.locator('#buySubmit').click();await p.locator('#planToggle').click();assert.equal((await state(p)).plans[0].enabled,true);assert.equal((await state(p)).plans[1].enabled,false);await p.locator('#buyBack').click();await p.waitForTimeout(50);assert.equal(await p.locator('[data-plan-id]').count(),2);assert.match(await p.locator('#settingsScroll').innerText(),/已暂停/);await p.evaluate(()=>V2Store.setTheme('dark'));await saveShot(p,'fund-plan-list-dark');
  await p.locator('[data-plan-id="'+second.id+'"]').click();assert.equal(await p.locator('#buyAmount').inputValue(),'350');assert.equal(await p.locator('#cycleValue').innerText(),'每周');await p.locator('#inlinePlanDelete').click();await p.waitForTimeout(50);assert.equal(await p.locator('[data-plan-id]').count(),1);assert.equal((await state(p)).plans[0].id,initial.plans[0].id);assert.deepEqual((await state(p)).transactions,initial.transactions);
 });

 await check('35-formal-nav-read-contract-and-auth-recovery',async p=>{
  await p.evaluate(()=>{const s=V2Store.getState();V2Store.replaceUiState({...s,transactions:s.transactions.map((t,i)=>i===0?v2TransactionModel({...t,transactionStatus:'READY_TO_SYNC',expectedNavDate:'2026-10-02',confirmedNavDate:'2026-10-06',navConfirmedAt:'2026-10-06T01:00:00Z',confirmedNav:'2.1234',confirmedShares:'47.01',shareDelta:'+47.01',confirmedAmount:null}):t)});});
  await openRecord(p,'demo-tx-1');assert.match(await p.locator('#settingsScroll').innerText(),/2026-10-06/);assert.match(await p.locator('#settingsScroll').innerText(),/2.1234/);assert.equal(await p.locator('#settingsScroll input, #settingsScroll textarea').count(),0);assert.equal(await p.locator('[data-gap-action=edit]').count(),0);await saveShot(p,'contract-official-nav-readonly');
  await p.locator('[data-gap-action=tx-sync]').click();assert.equal(await p.locator('[data-gap-action=tx-sync]').isDisabled(),true);await p.waitForTimeout(1100);assert.equal((await state(p)).transactions[0].confirmedNav,'2.1234');await p.locator('#settingsBack').click();await p.frameLocator('#frameRecords').locator('.back').click();
  await p.evaluate(()=>{V2Store.startHoldingSync();V2Store.completeHoldingSync('AUTH_EXPIRED');V2App.openProfileEntry('数据状态');});assert.match(await p.locator('#settingsScroll').innerText(),/登录已过期/);assert.equal(await p.locator('[data-gap-action=holding-sync]').isDisabled(),true);await p.locator('[data-gap-action=account]').click();assert.match(await p.locator('#settingsScroll').innerText(),/登录已过期/);await p.locator('[data-gap-action=login]').click();await p.waitForTimeout(1100);assert.equal((await state(p)).yjbAccount.status,'CONNECTED');assert.equal((await state(p)).holdingsState.status,'READY');
 });
 await check('36-long-names-large-values-and-zero-shares',async p=>{
  const name='广发纳斯达克100指数证券投资基金超长名称测试人民币份额A类';const amount='¥9,999,999,999.99';
  await p.evaluate(({name,amount})=>{const s=V2Store.getState();V2Store.replaceUiState({...s,holdings:s.holdings.map(f=>f.id==='gf-ndx100a'?{...f,name,amount,profit:'¥0',availableSharesText:'0.00',sellPresets:{'.25':'0','.5':'0','1':'0'}}:f),details:s.details.map(f=>f.id==='gf-ndx100a'?{...f,amount,shares:'0.00',cost:'¥0',profit:'¥0',profitRate:'0.00%'}:f)});},{name,amount});
  await holdingHome(p);await enter2(p);assert.equal(await holdings(p).locator('.fund-card[data-fund-id="gf-ndx100a"] .fund-name').innerText(),name);await saveShot(p,'contract-long-name-large-values');await enter3(p);assert.equal(await holdings(p).locator('#l3Shares').innerText(),'0.00');assert.equal(await holdings(p).locator('#l3Amount').innerText(),amount);assert.equal(await p.locator('#framePortfolio').evaluate(f=>f.contentDocument.documentElement.scrollWidth<=f.contentDocument.documentElement.clientWidth),true);await saveShot(p,'contract-zero-shares-detail');
  await p.evaluate(()=>V2App.openTrade('sell'));assert.match(await p.locator('#amountTip').innerText(),/0.00/);const before=await state(p);await p.locator('#sellFeeRate').fill('0');await p.locator('.sell-quick-actions button[data-ratio="1"]').click();await p.locator('#buySubmit').click();assert.equal(await p.locator('#buyAmount').getAttribute('aria-invalid'),'true');assert.deepEqual((await state(p)).transactions,before.transactions);await p.locator('#buyBack').click();await p.evaluate(()=>V2Store.setTheme('dark'));await holdings(p).locator('#level3Settings').click();await holdings(p).locator('#gapFundPlan').click();await saveShot(p,'contract-long-name-dark-settings');await p.locator('#settingsBack').click();assert.equal(await holdings(p).locator('#holdingSettings').getAttribute('aria-hidden'),'false');
 },{viewport:{width:320,height:740}});
 await browser.close();
 const result={target:path.resolve(__dirname,'../index.html'),sha256:require('node:crypto').createHash('sha256').update(fs.readFileSync(path.resolve(__dirname,'../index.html'))).digest('hex'),backAdapterSha256:require('node:crypto').createHash('sha256').update(backScript).digest('hex'),passed:results.every(r=>r.pass),checks:results};
 fs.writeFileSync(path.join(artifacts,'results.json'),JSON.stringify(result,null,2));
 console.log(`${results.filter(r=>r.pass).length}/${results.length} acceptance checks passed`);if(!result.passed)process.exitCode=1;
})().catch(error=>{console.error(error);process.exitCode=1;});
