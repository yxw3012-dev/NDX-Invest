const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
const root=path.resolve(__dirname,'..'),canonical=fs.readFileSync(path.join(root,'index.html'),'utf8'),adapter=fs.readFileSync(path.join(root,'production-read.js'),'utf8');
const marketAdapter=fs.readFileSync(path.join(root,'production-market.js'),'utf8');
const html=canonical.replace('<script>window.V2Pages=','<script>window.NDX_LOCAL_PORTFOLIO=true;'+adapter+'</script>\n<script>'+marketAdapter+'</script>\n<script>window.V2Pages=');
const back=fs.readFileSync(path.join(root,'android-back.js'),'utf8'),results=[];
const data={displayName:'只读测试账户',maskedId:'138****0000',lastSyncAt:'2026-10-02T01:30:00Z',
 holdings:[{id:'yjb:1:17',fundId:'17',fundCode:'000017',name:'平台基金甲',meta:'账户一',amount:'¥9999',profit:'-¥77',autoInvest:false,availableSharesText:'100',sellPresets:{'.25':'25','.5':'50','1':'100'}},{id:'yjb:2:18',fundId:'18',fundCode:'000018',name:'平台基金乙',meta:'账户二',amount:'¥200',profit:'+¥2',autoInvest:false,availableSharesText:'200',sellPresets:{'.25':'50','.5':'100','1':'200'}}],
 details:[{id:'yjb:1:17',amount:'¥9999',cost:'¥200',shares:'100',avgNav:'2',autoInvest:'未设置',profit:'-¥77',profitRate:'-38.5%',annualized:'--',realizedProfit:'--',cumulativeProfit:'--',nav:'1.5',navDelta:'--',navDate:'2026-10-01',navStatus:'养基宝 · 正式净值',officialNav:'1.5',officialNavTime:'2026-10-01',estimatedNav:null,estimatedNavTime:null},{id:'yjb:2:18',amount:'¥200',cost:'¥198',shares:'200',avgNav:'0.99',autoInvest:'未设置',profit:'+¥2',profitRate:'+1.01%',annualized:'--',realizedProfit:'--',cumulativeProfit:'--',nav:'--',navDelta:'--',navDate:'--',navStatus:'暂无正式净值'}],
 overview:{amount:'¥10199',metrics:['¥398','-¥75','-18.84%','--']}};
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.BROWSER_EXECUTABLE||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
 const page=await browser.newPage({viewport:{width:390,height:844}}),errors=[],requests=[];
 page.on('pageerror',e=>errors.push(e.stack||e.message));page.on('request',r=>{if(!r.url().startsWith('http://127.0.0.1:4199')&&!/kaspersky-labs/.test(r.url())&&/^https?:/.test(r.url()))requests.push(r.url());});
 await page.route('**/*',r=>r.request().url().startsWith('http://127.0.0.1:4199')?r.fulfill({status:200,contentType:'text/html',body:html}):r.abort());
 await page.addInitScript(()=>{window.readCommands=[];window.marketCommands=[];window.NDXYjbRead={post:a=>readCommands.push(a),prepareCreate:text=>JSON.stringify({...JSON.parse(text),requestId:crypto.randomUUID()}),acknowledgeCreate:()=>true};window.NDXMarketRead={post:a=>marketCommands.push(a)};});
 const apply=async(status,account='CONNECTED',value=data)=>page.evaluate(p=>YjbReadApply(p),{data:value,accountStatus:account,holdingsStatus:status,errorMessage:['ERROR','LOGIN_FAILED'].includes(status)?'测试读取失败':''});
 const state=()=>page.evaluate(()=>JSON.parse(JSON.stringify(V2Store.getState())));
 const holdings=page.frameLocator('#framePortfolio');
 const detailValue=title=>page.locator('.settings-row').filter({has:page.locator('.settings-row-title',{hasText:new RegExp('^'+title+'$')})}).locator('.settings-row-end').innerText();
 const nav=async name=>{await page.locator('.global-nav-item[data-page="'+name+'"]').click();await page.waitForTimeout(350);};
 const check=async(name,body)=>{await body();assert.deepEqual(errors,[]);results.push({name,pass:true});console.log('PASS '+name);};
 try {
  await page.goto('http://127.0.0.1:4199');await holdings.locator('.total-amount').waitFor();
  await check('local-portfolio-opens-without-login',async()=>{
   assert.equal((await state()).holdings.length,0);await nav('持仓');
   await apply('EMPTY','CONNECTED',{...data,holdings:[],details:[]});
   assert.match(await holdings.locator('#gapHoldingStatus').innerText(),/暂无基金/);
   await holdings.locator('#fundAddEmpty').waitFor();
   assert.equal(await page.locator('[data-gap-action=login]').count(),0);
   assert.deepEqual(await page.evaluate(()=>{V2Store.startLogin();V2Store.setAccountStatus('NOT_CONNECTED');return readCommands}),[]);
  });
  await check('combined-market-and-account-recovery-never-overwrites-other-source',async()=>{
   const market={indexText:'30,807.93',changeText:'+306.37',changePercentText:'+1.00%',marketStatus:'已休市 · 腾讯',updatedText:'行情 2026-10-03 04:00',fxText:'6.7050',fxChangeText:'-0.02%',fxUpdatedText:'腾讯 · 最近报价',drawdownText:'-13.60%',drawdownRate:'-0.136',historyText:'历史范围 1996-06-06—2026-10-02',drawdownBasisText:'基于可用历史最高收盘价',performance:Array.from({length:4},()=>['+10.00%','+3.00%','-20.00%','15.00%']),refreshing:false};
   await page.evaluate(m=>MarketReadApply({nd100:m,dataSourceState:{indexStatus:'已读取 · 腾讯',fxStatus:'已读取 · 腾讯'}}),market);
   for(const [account,status] of [['NOT_CONNECTED','NOT_CONNECTED'],['CONNECTING','INITIAL_LOADING'],['LOGIN_FAILED','ERROR'],['CONNECTED','READY'],['CONNECTED','REFRESHING'],['CONNECTED','EMPTY'],['CONNECTED','ERROR'],['CONNECTED','OFFLINE_CACHE'],['AUTH_EXPIRED','AUTH_EXPIRED']]){
    const value=['NOT_CONNECTED','INITIAL_LOADING','EMPTY'].includes(status)?{...data,holdings:[],details:[],overview:{amount:'--',metrics:['--','--','--','--']}}:data;
    await apply(status,account,value);const s=await state();assert.equal(s.nd100.indexText,market.indexText);assert.equal(s.nd100.fxText,market.fxText);assert.equal(s.nd100.planText,'¥500');assert.equal(s.dataSourceState.indexStatus,'已读取 · 腾讯');
   }
   await apply('READY');const before=await state();
   await page.evaluate(()=>MarketReadApply({nd100:{indexText:'--',fxText:'--',drawdownText:'--',drawdownRate:null,refreshing:false},dataSourceState:{indexStatus:'读取失败 · 暂无缓存',fxStatus:'读取失败 · 暂无缓存'}}));
   const failed=await state();assert.deepEqual(failed.holdings,before.holdings);assert.deepEqual(failed.details,before.details);assert.equal(failed.yjbAccount.status,'CONNECTED');assert.equal(failed.nd100.planText,'--');
   await page.evaluate(m=>MarketReadApply({nd100:m,dataSourceState:{indexStatus:'缓存 · 腾讯',fxStatus:'缓存 · 腾讯'}}),{...market,marketStatus:'缓存 · 已休市 · 腾讯',updatedText:market.updatedText+' · 缓存'});
   assert.deepEqual((await state()).holdings,before.holdings);assert.equal((await state()).nd100.planText,'¥500');assert.deepEqual(await page.evaluate(()=>marketCommands),[]);
  });
  await check('local-data-status-exposes-refresh-without-account-actions',async()=>{
   await page.evaluate(()=>V2App.openProfileEntry('数据状态'));await apply('READY');
   assert.match(await page.locator('#settingsScroll').innerText(),/本地持仓/);
   assert.equal(await page.locator('[data-gap-action=login], [data-gap-action=disconnect], [data-gap-action=account]').count(),0);
   await page.locator('[data-gap-action=holding-sync]').click();
   assert.equal((await page.evaluate(()=>readCommands)).at(-1),'refresh');
   assert.equal(await page.evaluate(back),true);await page.waitForTimeout(300);await apply('READY');
  });
  await check('platform-values-holding-levels-and-zero-transaction-history',async()=>{
   assert.equal(await holdings.locator('.fund-card').count(),2);assert.equal(await holdings.locator('.total-amount').innerText(),'¥10,199.00');
   await holdings.locator('#sheetHandleZone').focus();await holdings.locator('#sheetHandleZone').press('Enter');await page.waitForTimeout(750);
   const card=holdings.locator('.fund-card[data-fund-id="yjb:1:17"]');await card.focus();await card.press('Enter');await page.waitForTimeout(650);await card.click();await page.waitForTimeout(1050);
   assert.equal(await page.locator('#framePortfolio').evaluate(f=>f.contentWindow.V2Portfolio.getMode()),'level3');
   assert.equal(await holdings.locator('#l3Amount').innerText(),'¥9999.00');assert.match(await holdings.locator('.level3-page').innerText(),/-¥77/);
   await holdings.locator('#level3Settings').click();assert.match(await holdings.locator('#hsScroll').innerText(),/最后同步/);assert.match(await holdings.locator('#hsScroll').innerText(),/未设置/);
   await holdings.locator('#gapFundRecords').click();assert.equal(await page.frameLocator('#frameRecords').locator('.record').count(),0);await page.frameLocator('#frameRecords').locator('.back').click();
   assert.equal(await page.evaluate(back),true);await holdings.locator('#level3Back').click();await page.waitForTimeout(1000);await holdings.locator('#sheetHandleZone').press('Enter');await page.waitForTimeout(650);
  });
  await check('refresh-keeps-visible-data-cache-error-empty-and-auth-state',async()=>{
   for(const status of ['REFRESHING','OFFLINE_CACHE']){await apply(status);assert.equal(await holdings.locator('.fund-card').count(),2);assert.equal(await holdings.locator('#gapHoldingStatus').isVisible(),false);}
   assert.match((await state()).holdingsState.lastSyncAt,/2026-10-02/);
   for(const status of ['ERROR','AUTH_EXPIRED','INITIAL_LOADING']){await apply(status,status==='AUTH_EXPIRED'?'AUTH_EXPIRED':'CONNECTED');assert.equal((await state()).holdings.length,2);assert.equal(await holdings.locator('#gapHoldingStatus').isVisible(),true);}
   await apply('EMPTY','CONNECTED',{...data,holdings:[],details:[],overview:{amount:'¥0',metrics:['¥0','¥0','--','--']}});assert.match(await holdings.locator('#gapHoldingStatus').innerText(),/添加基金/);assert.equal(await holdings.locator('.fund-card').count(),0);
   await apply('READY');
  });
  await check('closed-fund-empty-holdings-can-buy-from-native-catalogue',async()=>{
   const closed={...data.holdings[0],availableSharesText:'0',sellPresets:{'.25':'0','.5':'0','1':'0'}};
   await apply('EMPTY','CONNECTED',{...data,fundCatalog:[closed],holdings:[],details:[],overview:{amount:'--',metrics:['--','--','--','--']}});
   assert.equal((await state()).holdings.length,0);assert.equal(await holdings.locator('.fund-card').count(),0);
   assert.match(await holdings.locator('#gapHoldingStatus').innerText(),/已有基金再次买入/);await holdings.locator('#gapClosedFundBuy').click();
   assert.equal(await page.locator('#buyFundName').innerText(),closed.name);await page.locator('#buyAmount').fill('5');await page.locator('#buyFeeRate').fill('0');await page.locator('#buySubmit').click();
   const command=JSON.parse((await page.evaluate(()=>readCommands)).at(-1).slice(11));assert.equal(command.fundId,closed.id);
   await page.evaluate(p=>YjbReadApply(p),{buyResult:{requestId:command.requestId,ok:false,message:'Fake: no transaction created'}});
   await page.locator('#buyBack').click();await page.waitForTimeout(350);await apply('READY');
   assert.equal((await state()).fundCatalog.length,2);assert.equal((await state()).transactions.length,0);
  });
  await check('native-buy-sell-submit-picker-and-dark-mode-retain-layout',async()=>{
   await holdings.locator('.action-button.buy').click();await page.locator('#buyAmount').fill('100');await page.locator('#buyFeeRate').fill('0.15');await page.locator('#buySubmit').click();assert.equal(await page.locator('#buySubmit').isDisabled(),true);assert.equal((await state()).transactions.length,0);
   const command=(await page.evaluate(()=>readCommands)).at(-1);assert.match(command,/^buy-create:/);const request=JSON.parse(command.slice(11));await page.evaluate(p=>YjbReadApply(p),{data,transactions:[],accountStatus:'CONNECTED',holdingsStatus:'READY',buyResult:{requestId:request.requestId,ok:false,message:'测试：日历尚未验证'}});assert.equal(await page.locator('#buySubmit').isDisabled(),false);assert.equal(await page.locator('#buyAmount').inputValue(),'100');assert.match(await page.locator('#buyFeeAmount').innerText(),/0.15元/);
   await page.locator('#fundSelect').click();await page.waitForTimeout(2350);assert.equal(await page.locator('.fund-name-option').count(),2);await page.keyboard.press('Escape');await page.waitForTimeout(500);await page.locator('#buyBack').click();await page.waitForTimeout(350);
   await holdings.locator('.action-button.sell').click();await page.locator('#buyAmount').fill('101');await page.locator('#sellFeeRate').fill('1.5');await page.locator('#buySubmit').click();assert.equal(await page.locator('#buySubmit').isDisabled(),true);
   const sell=JSON.parse((await page.evaluate(()=>readCommands)).at(-1).slice(12));assert.equal(sell.sharesInput,'101');assert.equal(sell.sellRateInput,'1.5');assert.equal(sell.feeInput,undefined);
   await page.evaluate(p=>YjbReadApply(p),{data,transactions:[],accountStatus:'CONNECTED',holdingsStatus:'READY',buyResult:{requestId:sell.requestId,ok:false,message:'卖出份额不能超过当前持仓'}});
   assert.equal(await page.locator('#buyAmount').inputValue(),'101');assert.equal(await page.locator('#buyAmount').getAttribute('aria-invalid'),'true');assert.match(await page.locator('#amountTip').innerText(),/不能超过/);
   await page.locator('#buyAmount').fill('25');await page.locator('#buySubmit').click();const validSell=JSON.parse((await page.evaluate(()=>readCommands)).at(-1).slice(12));
   await page.evaluate(p=>YjbReadApply(p),{data,transactions:[],accountStatus:'CONNECTED',holdingsStatus:'READY',buyResult:{requestId:validSell.requestId,ok:true,message:'已保存本地卖出'}});
   assert.equal(await page.locator('#buyAmount').inputValue(),'');assert.equal(await page.locator('#buyAmount').getAttribute('aria-invalid'),'false');await page.locator('#buyBack').click();await page.waitForTimeout(350);
   await page.evaluate(()=>V2Store.setTheme('dark'));await page.waitForTimeout(500);assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');
  });
  await check('native-sell-details-all-states-filter-clear-and-return',async()=>{
   await nav('持仓');await holdings.locator('.action-button.record').click();await page.waitForTimeout(350);
   const tx={id:'native-sell-only',kind:'SELL',type:'卖出',direction:'SELL',fundId:'yjb:1:17',fundCode:'000017',fundName:'平台基金甲',dateKey:'2026-09-28',dateLabel:'2026-09-28',weekday:'',time:'2026-10-03T00:00:00Z',primaryText:'25 份',secondaryText:'本地 SELL Transaction',amountInput:'25',feeRate:'1.5',feeInput:'0.75',timePeriod:'before',note:'卖出备注',expectedNavDate:'2026-09-28',createdShares:'100',createdCost:'120',currentShares:'99',currentCost:'118.8',costPolicy:'KEEP_UNIT_COST',cashReceiptConfirmed:false};
   const applySell=async(status,extra={})=>page.evaluate(p=>YjbReadApply(p),{data,transactions:[{...tx,transactionStatus:status,...extra}],accountStatus:'CONNECTED',holdingsStatus:'READY'});
   await applySell('WAITING_NAV');await page.frameLocator('#frameRecords').locator('.filter[data-filter="卖出"]').click();assert.equal(await page.frameLocator('#frameRecords').locator('.record').count(),1);
   await page.frameLocator('#frameRecords').locator('[data-record-id="native-sell-only"]').click();await page.locator('[data-native-nav]').waitFor();assert.match(await page.locator('[data-native-sell-note]').innerText(),/不代表实际到账/);
   await page.locator('[data-gap-action=edit]').click();await page.locator('#gapAmount').fill('30');await page.locator('#gapRate').fill('0');await page.locator('#v2TransactionEdit button').click();
   const edit=JSON.parse((await page.evaluate(()=>readCommands)).at(-1).slice(12));assert.equal(edit.action,'edit');assert.equal(edit.draft.amountInput,'30');assert.equal(edit.draft.feeRate,'0');assert.equal(edit.draft.note,'卖出备注');assert.equal(await page.locator('#gapNote').count(),0);
   await page.evaluate(p=>YjbReadApply(p),{buyResult:{requestId:edit.requestId,ok:true,message:'已修改卖出'}});
   for(const status of ['READY_TO_SYNC','SYNCING','SYNCED','SYNC_FAILED','EXTERNAL_CHANGED','CANCELLED']){
    await applySell(status,{confirmedNavDate:'2026-09-28',confirmedNav:'2.0000',confirmedShares:'25.005',shareDelta:'-25.005',feeInput:'0.005',confirmedAmount:'¥49.25',syncedAt:status==='SYNCED'?'2026-10-03T11:05:06.398Z':null,syncError:status==='SYNC_FAILED'?'测试回读未匹配':''});
    assert.equal(await detailValue('手续费'),'¥0.01');assert.equal(await detailValue('确认份额'),'25.01');assert.equal(await detailValue('份额变化'),'-25.01');assert.equal(await detailValue('确认净值'),'2.0000');
    assert.doesNotMatch(await page.locator('#settingsScroll').innerText(),/确认 NAV/);
    if(status==='SYNCED')assert.equal(await detailValue('同步完成时间'),'2026-10-03 19:05:06');
    assert.equal((await state()).transactions[0].feeInput,'0.005');assert.equal((await state()).transactions[0].shareDelta,'-25.005');
    assert.equal(await page.locator('[data-native-nav]').count(),0);assert.equal(await page.locator('[data-gap-action=rebase]').count(),0);assert.match(await page.locator('#settingsScroll').innerText(),/49.25/);
    if(status==='SYNCING')assert.equal(await page.locator('[data-gap-action=tx-sync]').isDisabled(),true);
   }
   await applySell('READY_TO_SYNC');await page.locator('[data-gap-action=tx-sync]').click();const sync=JSON.parse((await page.evaluate(()=>readCommands)).at(-1).slice(12));assert.equal(sync.action,'sync');
   await page.evaluate(p=>YjbReadApply(p),{data:{...data,holdings:[],details:[],overview:{amount:'--',metrics:['--','--','--','--']}},transactions:[{...tx,transactionStatus:'SYNCED',confirmedNavDate:'2026-09-28',confirmedNav:'2',confirmedShares:'100',shareDelta:'-100',confirmedAmount:'¥197.00',syncedAt:'2026-10-03T00:00:00Z'}],accountStatus:'CONNECTED',holdingsStatus:'EMPTY',buyResult:{requestId:sync.requestId,ok:true,message:'剩余持仓核验成功'}});
   assert.equal((await state()).holdings.length,0);assert.equal((await state()).transactions.length,1);assert.equal((await state()).transactions[0].kind,'SELL');
   assert.equal(await page.evaluate(back),true);await page.waitForTimeout(300);await page.frameLocator('#frameRecords').locator('.filter[data-filter="全部"]').click();await page.frameLocator('#frameRecords').locator('.back').click();await page.waitForTimeout(350);
   assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');await apply('READY');
  });
  await check('data-source-status-refresh-uses-native-only',async()=>{
   await nav('我的');await page.frameLocator('#frameMine').locator('.setting-row').filter({hasText:'数据状态'}).click();await apply('OFFLINE_CACHE');
   assert.match(await page.locator('#settingsScroll').innerText(),/本地持仓/);assert.equal(await page.locator('#settingsPage').evaluate(n=>n.classList.contains('is-dark')),true);
   await page.locator('[data-gap-action=holding-sync]').click();assert.equal((await page.evaluate(()=>readCommands)).at(-1),'refresh');assert.equal(await page.locator('[data-gap-action=account], [data-gap-action=disconnect]').count(),0);
   await apply('NOT_CONNECTED','NOT_CONNECTED',{...data,displayName:'',maskedId:'',lastSyncAt:'',holdings:[],details:[],overview:{amount:'--',metrics:['--','--','--','--']}});assert.match(await page.locator('#settingsScroll').innerText(),/正在打开持仓/);assert.equal((await state()).holdings.length,0);
  });
  await check('initial-position-details-edit-only-holding-start-date',async()=>{
   await page.locator('#settingsBack').click();await page.waitForTimeout(300);
   const importRow={id:'initial:YJB_IMPORT:17',kind:'INITIAL_POSITION',source:'YJB_IMPORT',fundId:'17',fundCode:'000017',fundName:'平台基金甲',importedAt:'2026-10-02T18:30:00.256Z',initialShares:'100',initialHoldingCost:'200',initialUnitCost:'2',initialMarketValue:'9999',holdingStartDate:null};
   await page.evaluate(p=>YjbReadApply(p),{data,initialPositions:[importRow],accountStatus:'CONNECTED',holdingsStatus:'READY'});
   await nav('持仓');await holdings.locator('.action-button.record').click();await page.frameLocator('#frameRecords').locator('[data-record-id="initial:YJB_IMPORT:17"]').click();
   assert.match(await page.locator('#settingsScroll').innerText(),/初始份额/);assert.equal(await page.locator('#settingsScroll input').count(),1);assert.equal(await page.locator('[data-gap-action=tx-sync]').count(),0);
   assert.doesNotMatch(await page.locator('#settingsScroll').innerText(),/初始市值/);assert.equal(await detailValue('导入时间'),'2026-10-03 02:30:00');
   assert.equal(await page.frameLocator('#frameRecords').locator('[data-record-id="initial:YJB_IMPORT:17"] .time').innerText(),'2026-10-03 02:30:00');
   assert.equal(await page.frameLocator('#frameRecords').locator('.date-main').first().innerText(),'2026-10-03');
   await page.locator('#holdingStartDate').fill('2026-10-01');await page.locator('#initialPositionDateForm button').click();assert.match((await page.evaluate(()=>readCommands)).at(-1),/^holding-start:/);
   assert.equal((await state()).transactions[0].importedAt,importRow.importedAt);
  });
  await check('native-buy-detail-all-states-manual-nav-no-mock-completion-and-conflict-lock',async()=>{
   await page.locator('#settingsBack').click();await page.waitForTimeout(300);
   const tx={id:'native-buy-only',kind:'BUY',type:'买入',direction:'BUY',fundId:'yjb:1:17',fundCode:'270042',fundName:'平台基金甲',dateKey:'2026-09-28',dateLabel:'2026-09-28',weekday:'',time:'2026-09-28T00:00:00Z',primaryText:'¥1000',secondaryText:'本地 BUY Transaction',amountInput:'1000',feeRate:'0.15',feeInput:'1.5',timePeriod:'before',note:'本地交易',expectedNavDate:'2026-09-28',confirmedNavDate:null,confirmedNav:null,confirmedShares:null,shareDelta:null,syncedAt:null,syncError:'',createdShares:'100',createdCost:'120',currentShares:'101',currentCost:'121.2'};
   const applyTx=async(status,extra={})=>page.evaluate(p=>YjbReadApply(p),{data,transactions:[{...tx,transactionStatus:status,...extra}],initialPositions:[],accountStatus:'CONNECTED',holdingsStatus:'READY'});
   await applyTx('WAITING_NAV');await page.frameLocator('#frameRecords').locator('[data-record-id="native-buy-only"]').click();await page.locator('[data-native-nav]').waitFor();
   assert.equal(await page.locator('#settingsScroll input').count(),0,'confirmed NAV has no user input');await page.locator('[data-native-nav]').click();let command=JSON.parse((await page.evaluate(()=>readCommands)).at(-1).slice(11));assert.equal(command.action,'check-nav');
   await page.evaluate(p=>YjbReadApply(p),{buyResult:{requestId:command.requestId,ok:true,message:'仍等待正式净值'}});
   await applyTx('WAITING_NAV',{expectedNavDate:'2026-09-29',syncError:'候选日期暂无正式净值，尚不能确认是否休市；继续等待，不使用下一天净值替代。'});
   await page.locator('[data-native-problem]').waitFor();assert.match(await page.locator('[data-native-problem]').innerText(),/继续等待/);
   assert.equal(await page.locator('[data-gap-action=tx-sync]').count(),0,'unresolved NAV cannot sync');
   await applyTx('WAITING_NAV');await page.locator('[data-native-nav]').waitFor();assert.equal(await page.locator('.settings-row').filter({hasText:'确认日期'}).locator('.settings-row-end').innerText(),'待公布净值');assert.equal(await page.locator('[data-native-problem]').count(),0,'pending diagnostic clears on recovery');
   await applyTx('READY_TO_SYNC',{expectedNavDate:'2026-09-26',confirmedNavDate:'2026-09-28',confirmedNav:'4.5700',confirmedShares:'21.881838',navConfirmedAt:'2026-10-07T17:00:00Z'});
   assert.doesNotMatch(await page.locator('#settingsScroll').innerText(),/预计净值日期/);assert.equal(await page.locator('.settings-row').filter({hasText:'确认日期'}).locator('.settings-row-end').innerText(),'2026-10-08');
   assert.equal(await page.locator('#settingsScroll input[type=date]').count(),0,'resolved NAV date remains immutable');
   assert.equal(await detailValue('确认净值'),'4.5700');assert.equal(await detailValue('确认份额'),'21.88');
   await applyTx('WAITING_NAV');await page.locator('[data-native-nav]').waitFor();
   await page.locator('[data-gap-action=edit]').click();assert.equal(await page.locator('#gapFee').getAttribute('readonly'),'');assert.match(await page.locator('#v2TransactionEdit button').innerText(),/保存修改/);
   for(const status of ['READY_TO_SYNC','SYNCING','SYNCED','SYNC_FAILED','EXTERNAL_CHANGED','CANCELLED']){
    await applyTx(status,{confirmedNavDate:'2026-09-28',confirmedNav:'2.123456789',confirmedShares:'470.225',shareDelta:'+470.225',feeInput:'123.605',syncedAt:status==='SYNCED'?'2026-10-03T01:00:00Z':null,syncError:status==='SYNC_FAILED'?'测试验证失败':''});
    assert.equal(await detailValue('确认净值'),'2.123456789');assert.equal(await detailValue('确认份额'),'470.23');assert.equal(await detailValue('份额变化'),'+470.23');assert.equal(await detailValue('手续费'),'¥123.61');
    assert.equal((await state()).transactions[0].confirmedShares,'470.225');assert.equal((await state()).transactions[0].confirmedNav,'2.123456789');
    assert.equal(await page.locator('[data-native-nav]').count(),0);assert.equal(await page.locator('#settingsScroll input[type=date]').count(),0);assert.equal(await page.locator('[data-gap-action=rebase]').count(),0,'no fake rebase');
    if(status==='SYNCING')assert.equal(await page.locator('[data-gap-action=tx-sync]').isDisabled(),true);
    if(status==='EXTERNAL_CHANGED')assert.match(await page.locator('#settingsScroll').innerText(),/创建交易时份额/);
    if(status==='CANCELLED')assert.equal(await page.locator('[data-gap-action=tx-sync]').count(),0);
   }
   await applyTx('SYNCING',{postMayHaveBeenSent:true,writeReviewRequired:true,writeDiagnosticText:'旧记录缺少发送阶段证据',syncError:'写入结果尚在核对'});
   await page.locator('[data-native-recovery]').waitFor();assert.equal(await page.locator('.settings-status').getAttribute('aria-busy'),'false');assert.match(await page.locator('.settings-status').innerText(),/写入结果待核对/);
   await page.locator('[data-native-recovery]').click();const recoveryCommand=JSON.parse((await page.evaluate(()=>readCommands)).at(-1).slice(11));assert.equal(recoveryCommand.action,'recover-write');assert.equal(recoveryCommand.id,tx.id);assert.equal(Object.hasOwn(recoveryCommand,'target'),false);
   await page.evaluate(p=>YjbReadApply(p),{buyResult:{requestId:recoveryCommand.requestId,ok:false,message:'用户取消恢复'}});
   await applyTx('READY_TO_SYNC');await page.locator('[data-gap-action=tx-sync]').click();command=JSON.parse((await page.evaluate(()=>readCommands)).at(-1).slice(11));assert.equal(command.action,'sync');const count=(await page.evaluate(()=>readCommands)).length;
   await page.waitForTimeout(1150);assert.equal((await page.evaluate(()=>readCommands)).length,count,'no Mock auto-complete timer');assert.equal((await state()).transactions[0].transactionStatus,'READY_TO_SYNC');
   await page.evaluate(p=>YjbReadApply(p),{buyResult:{requestId:command.requestId,ok:false,message:'协议未验证'}});
   await applyTx('EXTERNAL_CHANGED',{postMayHaveBeenSent:true});assert.equal(await page.locator('[data-gap-action=cancel]').isDisabled(),true,'ambiguous writes cannot be cancelled');
   await page.locator('[data-native-delete]').click();const deletionRaw=(await page.evaluate(()=>readCommands)).at(-1);assert.ok(deletionRaw.startsWith('record-delete:'));const deletion=JSON.parse(deletionRaw.slice(14));assert.equal(deletion.id,tx.id);assert.equal(Object.hasOwn(deletion,'target'),false);
   await page.evaluate(p=>YjbReadApply(p),{buyResult:{requestId:deletion.requestId,ok:false,message:'该基金还有后续或未完成交易，请先处理后续记录'}});await page.locator('[data-native-delete-feedback]').waitFor();assert.match(await page.locator('[data-native-delete-feedback]').innerText(),/后续或未完成/);assert.equal(await page.locator('[data-native-delete]').isDisabled(),false);assert.equal((await state()).transactions[0].id,tx.id);

   assert.equal(await page.evaluate(back),true);await page.waitForTimeout(300);assert.equal(await page.locator('#settingsPage').evaluate(n=>n.classList.contains('is-open')),false);
  });
  await check('native-dca-create-manage-auto-nav-status-delete-and-dark-mode',async()=>{
   await page.frameLocator('#frameRecords').locator('.back').click();await page.waitForTimeout(350);
   await apply('READY');await nav('持仓');await holdings.locator('.action-button.buy').click();await page.locator('[data-mode=recurring]').click();await page.locator('#buyAmount').fill('500.123456789');await page.locator('#buyFeeRate').fill('0.15');assert.equal(await page.locator('#buyRemark').count(),0);
   await page.locator('#buySubmit').click();const create=JSON.parse((await page.evaluate(()=>readCommands)).at(-1).slice(12));assert.equal(create.action,'create');assert.equal(create.buyRateInput,'0.15');assert.equal(await page.locator('#buyAmount').getAttribute('aria-invalid'),'false');
   const p={id:create.requestId,fundId:create.fundId,amountInput:create.amountInput,frequency:create.frequency,weekday:create.weekday,monthDay:create.monthDay,firstDate:'2026-10-04',nextDate:'2026-10-04',nextDateText:'2026-10-04',enabled:true,note:create.note,buyRateInput:'0.15',revision:1,canExecute:false,pendingCreation:false};
   let nativeTransactions=[];
   const applyPlan=async(plan,result)=>page.evaluate(payload=>YjbReadApply(payload),{data,plans:plan?[plan]:[],initialPositions:[],transactions:nativeTransactions,accountStatus:'CONNECTED',holdingsStatus:'READY',...(result?{buyResult:result}:{})});
   await applyPlan(p,{requestId:create.requestId,ok:true,message:'计划已保存到本机'});assert.equal(await page.locator('#buyAmount').inputValue(),'');assert.equal((await state()).plans.length,1);assert.equal((await state()).transactions.length,0);await page.locator('#buyBack').click();await page.waitForTimeout(500);
   await holdings.locator('#sheetHandleZone').focus();await holdings.locator('#sheetHandleZone').press('Enter');await page.waitForTimeout(750);const card=holdings.locator('.fund-card[data-fund-id="yjb:1:17"]');await card.focus();await card.press('Enter');await page.waitForTimeout(650);await card.click();await page.waitForTimeout(1000);
   await holdings.locator('#level3Settings').click();await holdings.locator('#gapFundPlan').click();assert.match(await page.locator('#settingsScroll').innerText(),/计划1/);await page.locator('[data-plan-id]').click();await page.locator('#buyTitle').filter({hasText:'定投计划管理'}).waitFor();assert.equal(await page.locator('#buyFeeRate').inputValue(),'0.15');assert.equal(await page.locator('#nativePlanExecute').count(),0);assert.equal(await page.locator('#firstDateValue').innerText(),p.nextDateText);
   await page.locator('#buyAmount').fill('0');await page.locator('#buySubmit').click();assert.match(await page.locator('#inlinePlanFeedback').innerText(),/金额/);
   await page.locator('#buyAmount').fill('650.50');await page.locator('#cycleRow').click();await page.locator('#buyFrequencyWheel').focus();await page.keyboard.press('Home');await page.keyboard.press('ArrowDown');await page.locator('#buyExecutionWheel').focus();await page.keyboard.press('Home');await page.locator('#buyRecurringConfirm').click();await page.locator('#buySubmit').click();const update=JSON.parse((await page.evaluate(()=>readCommands)).at(-1).slice(12));assert.equal(update.action,'update');assert.equal(update.frequency,'weekly');assert.equal(update.weekday,1);assert.equal(update.buyRateInput,'0.15');assert.equal(await page.locator('#buyAmount').getAttribute('aria-invalid'),'false');
   Object.assign(p,{amountInput:'650.50',frequency:'weekly',weekday:1,revision:2,nextDate:'2026-10-05',nextDateText:'2026-10-05'});await applyPlan(p,{requestId:update.requestId,ok:true,message:'已保存修改'});
   await page.locator('#planToggle').click();let command=JSON.parse((await page.evaluate(()=>readCommands)).at(-1).slice(12));assert.equal(command.action,'enabled');assert.equal(command.enabled,false);Object.assign(p,{enabled:false,revision:3,nextDateText:'已暂停'});await applyPlan(p,{requestId:command.requestId,ok:true,message:'已暂停'});assert.equal(await page.locator('#planToggle').getAttribute('aria-pressed'),'false');assert.equal(await page.locator('#nativePlanExecute').count(),0);
   await page.locator('#planToggle').click();command=JSON.parse((await page.evaluate(()=>readCommands)).at(-1).slice(12));assert.equal(command.enabled,true);Object.assign(p,{enabled:true,revision:4,nextDateText:'2026-10-05'});await applyPlan(p,{requestId:command.requestId,ok:true,message:'已恢复'});assert.equal(await page.locator('#planToggle').getAttribute('aria-pressed'),'true');
   Object.assign(p,{canExecute:false,nextDate:'2026-10-03',nextDateText:'2026-10-03 · 待正式净值',automationProblem:'NETWORK'});await applyPlan(p);assert.match(await page.locator('#inlinePlanFeedback').innerText(),/稍后自动重试/);
   assert.equal(await page.locator('#nativePlanExecute').count(),0);const count=(await page.evaluate(()=>readCommands)).length;
   assert.equal(await page.evaluate(()=>V2Store.executePlan('yjb:1:17',V2Store.getState().plans[0].id).ok),false);await page.waitForTimeout(1100);assert.equal((await page.evaluate(()=>readCommands)).length,count,'browser must not dispatch automatic financial commands');
   await page.evaluate(()=>V2Store.setTheme('dark'));assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');
   nativeTransactions=[{id:'native-plan-buy',fundId:p.fundId,name:'平台基金甲',kind:'BUY',type:'定投',transactionStatus:'SYNCED',amountInput:p.amountInput,time:'2026-10-09T01:00:00Z',dateKey:'2026-10-08',dateLabel:'2026-10-08',note:p.note,originPlanId:p.id,scheduledDate:'2026-10-08',confirmedNavDate:'2026-10-08',confirmedNav:'2.123456789',confirmedShares:'305.91'}];
   Object.assign(p,{nextDate:'2026-10-12',nextDateText:'2026-10-12',revision:5,automationProblem:null});await applyPlan(p);assert.equal((await state()).transactions[0].transactionStatus,'SYNCED');assert.equal((await state()).transactions[0].confirmedNavDate,'2026-10-08');
   await page.locator('#inlinePlanDelete').click();command=JSON.parse((await page.evaluate(()=>readCommands)).at(-1).slice(12));assert.equal(command.action,'delete');await applyPlan(p,{requestId:command.requestId,ok:false,message:'保存失败，计划保留'});assert.equal(await page.locator('#buyPage').getAttribute('aria-hidden'),'false');
   await page.locator('#inlinePlanDelete').click();command=JSON.parse((await page.evaluate(()=>readCommands)).at(-1).slice(12));await applyPlan(null,{requestId:command.requestId,ok:true,message:'计划已删除'});await page.waitForTimeout(500);assert.equal(await page.locator('#buyPage').getAttribute('aria-hidden'),'true');assert.equal(await page.locator('[data-plan-id]').count(),0);assert.equal(await page.locator('.dca-record-heading').count(),0);assert.equal((await state()).holdings[0].autoInvest,false);assert.equal((await state()).transactions[0].id,'native-plan-buy','deletion preserves linked BUY');assert.equal(await page.locator('#plansPage').count(),0);await page.locator('#settingsBack').click();
  });
  await check('native-dca-buy-is-filtered-as-dca-without-changing-domain-kind',async()=>{
   await page.evaluate(()=>V2App.openRecords());await page.waitForTimeout(350);const records=page.frameLocator('#frameRecords');
   await records.locator('[data-filter="定投"]').click();assert.equal(await records.locator('.record').count(),1);
   assert.equal(await records.locator('.record .tag').first().innerText(),'定投');assert.equal((await state()).transactions[0].kind,'BUY');
   await records.locator('[data-filter="买入"]').click();assert.equal(await records.locator('.record').count(),0);
   await records.locator('.back').click();await page.waitForTimeout(350);
  });
  await check('reload-restores-native-cache-and-uncertain-transaction-without-mock-or-repeat',async()=>{
   await page.reload();await holdings.locator('.total-amount').waitFor();
   assert.equal((await state()).holdings.length,0);assert.equal((await state()).nd100.indexText,'--');assert.equal((await state()).nd100.planText,'--');
   const tx={id:'restore-uncertain',kind:'BUY',type:'买入',fundId:'yjb:1:17',fundCode:'000017',fundName:'平台基金甲',dateKey:'2026-09-28',dateLabel:'2026-09-28',time:'2026-09-28T00:00:00Z',primaryText:'¥100.00',secondaryText:'本地 BUY Transaction',amountInput:'100',feeRate:'0',feeInput:'0',timePeriod:'before',note:'',transactionStatus:'SYNC_FAILED',expectedNavDate:'2026-09-28',confirmedNavDate:'2026-09-28',confirmedNav:'2.123456789',confirmedShares:'47.09',postMayHaveBeenSent:true,syncError:'验证期间登录失效，需要重新登录后核对'};
   const restore=async(account='CONNECTED',holding='OFFLINE_CACHE',result)=>page.evaluate(p=>YjbReadApply(p),{data,transactions:[tx],initialPositions:[],accountStatus:account,holdingsStatus:holding,...(result?{buyResult:result}:{})});
   await restore();assert.equal((await state()).holdings.length,2);assert.equal((await state()).transactions.length,1);assert.equal((await state()).transactions[0].confirmedNav,'2.123456789');
   await page.evaluate(()=>{V2Store.setTheme('dark');V2App.openRecords();});await page.frameLocator('#frameRecords').locator('[data-record-id="restore-uncertain"]').click();
   assert.equal(await page.locator('[data-gap-action=cancel]').count(),0,'uncertain failed write cannot be cancelled');assert.equal(await page.locator('#settingsScroll input').count(),0);assert.equal(await page.locator('#settingsPage').evaluate(n=>n.classList.contains('is-dark')),true);
   await page.locator('[data-gap-action=tx-sync]').click();const command=JSON.parse((await page.evaluate(()=>readCommands)).at(-1).slice(11));assert.equal(command.action,'sync');const count=(await page.evaluate(()=>readCommands)).length;
   await page.evaluate(()=>V2Store.transactionAction('restore-uncertain','sync'));assert.equal((await page.evaluate(()=>readCommands)).length,count);
   await restore('AUTH_EXPIRED','AUTH_EXPIRED',{requestId:command.requestId,ok:false,message:'登录已过期，请重新登录后核对持仓'});assert.equal((await state()).transactions[0].transactionStatus,'SYNC_FAILED');
   await restore();assert.equal((await state()).transactions[0].id,tx.id);assert.equal((await state()).transactions[0].postMayHaveBeenSent,true);assert.equal(await page.locator('[data-gap-action=cancel]').count(),0);
   await page.evaluate(()=>MarketReadApply({nd100:{indexText:'30,807.93',fxText:'6.7050',drawdownRate:'-0.136',drawdownText:'-13.60%',refreshing:false}}));
   assert.equal((await state()).holdings.length,2);assert.equal((await state()).transactions.length,1);assert.equal((await state()).nd100.planText,'¥500');assert.deepEqual(await page.evaluate(()=>marketCommands),[]);
  });
  await check('record-types-status-colors-no-note-and-home-action-colors-both-themes',async()=>{
   await page.reload();await holdings.locator('.total-amount').waitFor();
   const statuses=['WAITING_NAV','READY_TO_SYNC','SYNCING','SYNCED','SYNC_FAILED','EXTERNAL_CHANGED','CANCELLED'];
   const transactions=statuses.map(status=>({id:'color-'+status,kind:'BUY',type:'买入',direction:'BUY',fundId:'yjb:1:17',fundName:'测试基金',dateKey:'2026-10-03',dateLabel:'2026-10-03',weekday:'',time:'2026-10-03T01:00:00Z',primaryText:'¥100.00',secondaryText:'不应显示的辅助文字',note:'不应显示的历史备注',amountInput:'100',feeRate:'0',feeInput:'0',transactionStatus:status,confirmedNav:null,confirmedShares:null}));
   transactions.push({...transactions[0],id:'color-adjust',type:'调整',transactionStatus:'SYNCED'});
   const initial={id:'color-initial',kind:'INITIAL_POSITION',source:'YJB_IMPORT',fundId:'17',fundCode:'000017',fundName:'初始基金',importedAt:'2026-10-03T01:00:00Z',initialShares:'100',initialHoldingCost:'200',initialUnitCost:'2'};
   await page.evaluate(p=>YjbReadApply(p),{data,transactions,initialPositions:[initial],accountStatus:'CONNECTED',holdingsStatus:'READY'});
   const records=page.frameLocator('#frameRecords'),expected={WAITING_NAV:'status-waiting',READY_TO_SYNC:'status-waiting',SYNCING:'status-waiting',SYNCED:'status-done',SYNC_FAILED:'status-error',EXTERNAL_CHANGED:'status-warning',CANCELLED:'status-error'};
   for(const theme of ['light','dark']){
    await page.evaluate(t=>V2Store.setTheme(t),theme);await nav('持仓');
    await page.waitForTimeout(500);
    const colors=await holdings.locator('.action-button').evaluateAll(nodes=>nodes.map(n=>({class:n.className,color:getComputedStyle(n).color})));
    for(const entry of colors){const [r,g,b]=entry.color.match(/\d+/g).map(Number);if(entry.class.includes('buy'))assert.ok(g>r&&g>b);if(entry.class.includes('sell'))assert.ok(r>g&&r>b);if(entry.class.includes('record'))assert.ok(b>r);}
    await holdings.locator('.action-button.record').click();await page.waitForTimeout(350);
    assert.equal(await records.locator('.filter').count(),7);assert.equal(await records.locator('.filter').last().innerText(),'初始持仓');
    assert.equal(await records.locator('.record .secondary,.record .note').count(),0);
    for(const status of statuses)assert.ok((await records.locator('[data-record-id="color-'+status+'"] .tag').last().getAttribute('class')).includes(expected[status]));
    assert.ok((await records.locator('[data-record-id="color-initial"] .tag').last().getAttribute('class')).includes('status-done'));
    const initialColor=await records.locator('.type-initial').evaluate(n=>getComputedStyle(n).backgroundColor),adjustColor=await records.locator('.type-adjust').evaluate(n=>getComputedStyle(n).backgroundColor);assert.notEqual(initialColor,adjustColor);
    await records.locator('[data-filter="初始持仓"]').click();assert.equal(await records.locator('.record').count(),1);assert.equal(await records.locator('.record').getAttribute('data-record-id'),'color-initial');
    await records.locator('[data-filter="调整"]').click();assert.equal(await records.locator('.record').count(),1);await records.locator('[data-filter="全部"]').click();assert.equal(await records.locator('.record').count(),9);
    await records.locator('[data-record-id="color-SYNCED"]').click();assert.equal(await page.locator('.settings-row-title').filter({hasText:/^备注$/}).count(),0);assert.doesNotMatch(await page.locator('#settingsScroll').innerText(),/不应显示的历史备注/);assert.equal((await state()).transactions.find(t=>t.id==='color-SYNCED').note,'不应显示的历史备注');await page.locator('#settingsBack').click();
    await records.locator('.back').click();await page.waitForTimeout(350);
    for(const type of ['buy','sell']){await holdings.locator('.action-button.'+type).click();assert.equal(await page.locator('#buyRemark').count(),0);assert.equal(await page.locator('.buy-section-label').filter({hasText:/^备注$/}).count(),0);await page.locator('#buyBack').click();await page.waitForTimeout(350);}
   }
  });
  assert.deepEqual(requests,[],'read bridge must not issue browser HTTP requests');
  fs.mkdirSync(path.join(root,'artifacts'),{recursive:true});fs.writeFileSync(path.join(root,'artifacts/production-read-browser.json'),JSON.stringify({passed:true,environment:'host browser with fake native bridge; no live credentials',results,errors,externalRequests:requests},null,2));
 } catch(e){console.error(await page.evaluate(()=>['buyRecurringSheet','buyRecurringConfirm','cycleRow','buyPage'].map(id=>{const n=document.getElementById(id);return {id,class:n?.className,rect:n?.getBoundingClientRect().toJSON(),scroll:n?.scrollTop}})));throw e;} finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
