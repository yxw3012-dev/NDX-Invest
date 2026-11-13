const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
const root=path.resolve(__dirname,'..'),source=fs.readFileSync(path.join(root,'index.html'),'utf8'),adapter=fs.readFileSync(path.join(root,'production-read.js'),'utf8');
const html=source.replace('<script>window.V2Pages=','<script>'+adapter+'</script>\n<script>window.V2Pages=');
const artifacts=path.join(root,'artifacts/personal-statistics');fs.mkdirSync(artifacts,{recursive:true});
const initial={id:'initial:YJB_IMPORT:17',kind:'INITIAL_POSITION',source:'YJB_IMPORT',fundId:'17',fundCode:'270042',fundName:'测试基金',importedAt:'2026-01-01T01:00:00Z',initialShares:'100',initialUnitCost:'10',initialHoldingCost:'1000',holdingStartDate:'2025-10-05'};
const data={displayName:'测试用户',maskedId:'***',lastSyncAt:'2026-10-05T01:00:00Z',holdings:[{id:'yjb:1:17',fundId:'17',fundCode:'270042',name:'测试基金',amount:'¥1100',profit:'+¥100',availableSharesText:'100',meta:'测试账户',autoInvest:false,sellPresets:{'.25':'25','.5':'50','1':'100'}}],details:[{id:'yjb:1:17',amount:'¥1100',cost:'¥1000',shares:'100',avgNav:'10',profit:'+¥100',profitRate:'+10%',annualized:'--',realizedProfit:'--',cumulativeProfit:'--',nav:'11',navDate:'2026-10-05',navStatus:'正式净值'}],overview:{amount:'¥1100',metrics:['¥1000','+¥100','+10%','--']}};
const stats={funds:{'yjb:1:17':{annualized:'+10.00%',realizedProfit:'¥0.00',cumulativeProfit:'+¥100.00',cumulativeRate:'+10.00%',holdingStartDate:'2025-10-05',holdingDays:365,valuationDate:'2026-10-05',message:''}},overview:{annualized:'+10.00%'}};
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.BROWSER_EXECUTABLE||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
 const page=await browser.newPage({viewport:{width:390,height:844},hasTouch:true}),errors=[],requests=[],results=[];
 page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(/^https?:/.test(r.url())&&!r.url().startsWith('http://127.0.0.1:4197')&&!/kaspersky-labs/.test(r.url()))requests.push(r.url());});
 await page.route('**/*',r=>r.request().url().startsWith('http://127.0.0.1:4197')?r.fulfill({status:200,contentType:'text/html',body:html}):r.abort());
 await page.addInitScript(()=>{window.commands=[];window.NDXYjbRead={post:a=>commands.push(a)};});
 let record={...initial};
 const apply=async(extra={})=>page.evaluate(p=>YjbReadApply(p),{data,initialPositions:[record],transactions:[],localStatistics:stats,accountStatus:'CONNECTED',holdingsStatus:'READY',...extra});
 const check=async(name,fn)=>{await fn();assert.deepEqual(errors,[]);results.push({name,pass:true});console.log('PASS '+name);};
 const lastCommand=()=>page.evaluate(()=>commands.at(-1));
 try {
  await page.goto('http://127.0.0.1:4197');await page.frameLocator('#framePortfolio').locator('.total-amount').waitFor();await apply();
  await page.locator('.global-nav-item[data-page="持仓"]').tap();await page.waitForTimeout(400);
  await page.frameLocator('#framePortfolio').locator('.action-button.record').tap();await page.frameLocator('#frameRecords').locator('[data-record-id="'+record.id+'"]').tap();await page.locator('#initialContributionsCard').waitFor();
  await check('empty-detail-edit-inserts-row-and-add-inherits-next-day-and-amount',async()=>{
   assert.match(await page.locator('#initialContributionsDifference').innerText(),/-- 元/);assert.equal(await page.locator('#holdingStartDate').getAttribute('max'),'2026-01-01');await page.locator('#initialContributionsEdit').tap();assert.equal(await page.locator('[data-contribution-date]').count(),1);
   await page.locator('[data-contribution-amount="0"]').fill('400');await page.locator('[data-contribution-add="0"]').tap();
   assert.equal(await page.locator('[data-contribution-date="1"]').inputValue(),'2025-10-06');assert.equal(await page.locator('[data-contribution-amount="1"]').inputValue(),'400');
   await page.locator('[data-contribution-amount="1"]').fill('600');assert.match(await page.locator('#initialContributionsDifference').innerText(),/0\.00 元/);
  });
  await check('invalid-date-and-zero-amount-use-local-feedback-with-no-command',async()=>{
   const before=await lastCommand();await page.locator('[data-contribution-amount="0"]').fill('0');await page.locator('#initialContributionsDone').tap();assert.match(await page.locator('#initialContributionsError').innerText(),/金额/);assert.equal(await lastCommand(),before);
   await page.locator('[data-contribution-amount="0"]').fill('400');await page.locator('[data-contribution-date="0"]').fill('2026-01-02');await page.locator('#initialContributionsDone').tap();assert.match(await page.locator('#initialContributionsError').innerText(),/导入日期/);assert.equal(await lastCommand(),before);await page.locator('[data-contribution-date="0"]').fill('2025-10-05');
  });
  await check('native-save-is-local-only-and-ack-preserves-import-and-holding-facts',async()=>{
   await page.locator('#initialContributionsDone').tap();assert.equal(await page.locator('#initialContributionsDone').isDisabled(),true);
   const command=await lastCommand();assert.match(command,/^initial-contributions:/);const request=JSON.parse(command.slice('initial-contributions:'.length));record={...record,initialContributions:request.entries};
   await apply({contributionResult:{id:record.id,requestId:request.requestId,ok:true}});assert.equal(await page.locator('[data-contribution-date]').count(),0);
   const state=await page.evaluate(()=>V2Store.getState());assert.equal(state.initialPositions[0].importedAt,initial.importedAt);assert.equal(state.transactions.length,1);assert.equal(state.holdings[0].amount,'¥1100');assert.equal(state.details[0].annualized,'+10.00%');assert.equal(state.overview.metrics[3],'+10.00%');
  });
  await check('delete-edit-and-mismatch-save-remain-draft-with-no-buy',async()=>{
   await page.locator('#initialContributionsEdit').tap();await page.locator('[data-contribution-remove="1"]').tap();assert.equal(await page.locator('[data-contribution-date]').count(),1);assert.match(await page.locator('#initialContributionsError').innerText(),/尚未补齐/);
   await page.locator('#initialContributionsDone').tap();const request=JSON.parse((await lastCommand()).slice('initial-contributions:'.length));record={...record,initialContributions:request.entries};
   await apply({localStatistics:{funds:{'yjb:1:17':{...stats.funds['yjb:1:17'],annualized:'--',message:'初始投入明细与初始成本不一致，请补齐。'}},overview:{annualized:'--'}},contributionResult:{id:record.id,requestId:request.requestId,ok:true}});
   assert.equal((await page.evaluate(()=>V2Store.getState())).details[0].annualized,'--');assert.equal((await page.evaluate(()=>V2Store.getState())).transactions.length,1);
  });
  await check('save-failure-retains-input-for-retry',async()=>{
   await page.locator('#initialContributionsEdit').tap();await page.locator('[data-contribution-amount="0"]').fill('1000');await page.locator('#initialContributionsDone').tap();const request=JSON.parse((await lastCommand()).slice('initial-contributions:'.length));
   await page.evaluate(p=>YjbReadApply(p),{contributionResult:{id:record.id,requestId:request.requestId,ok:false,message:'测试保存失败'}});assert.equal(await page.locator('[data-contribution-amount="0"]').inputValue(),'1000');assert.match(await page.locator('#initialContributionsError').innerText(),/保存失败/);assert.equal(await page.locator('#initialContributionsDone').isDisabled(),false);
  });
  for(const palette of ['classic','pink'])for(const theme of ['light','dark'])for(const width of [320,390,430])await check('inputs-fit-'+palette+'-'+theme+'-'+width,async()=>{
   await page.setViewportSize({width,height:844});await page.evaluate(({palette,theme})=>{V2Store.setPalette(palette);V2Store.setTheme(theme);},{palette,theme});await page.waitForTimeout(350);await page.locator('#initialContributionsCard').scrollIntoViewIfNeeded();
   const box=await page.locator('#initialContributionsCard').boundingBox();assert.ok(box.x>=0&&box.x+box.width<=width+1);
   assert.equal(await page.locator('#initialContributionsCard').evaluate(el=>el.scrollWidth<=el.clientWidth+1),true);
   if(width===390)await page.screenshot({path:path.join(artifacts,palette+'-'+theme+'.png'),fullPage:true});
  });
  await check('back-and-reopen-restore-saved-rows-and-do-not-leave-scroll-inert',async()=>{
   await page.locator('#settingsBack').tap();await page.waitForTimeout(350);await page.frameLocator('#frameRecords').locator('[data-record-id="'+record.id+'"]').tap();await page.locator('#initialContributionsCard').waitFor();assert.equal(await page.locator('[data-contribution-date]').count(),0);assert.match(await page.locator('#initialContributionsCard').innerText(),/400/);
  });
  assert.deepEqual(requests,[]);assert.equal(await page.evaluate(()=>commands.some(a=>/^(buy-|sell-|plan-)/.test(a))),false);
  fs.writeFileSync(path.join(artifacts,'browser.json'),JSON.stringify({results,errors,externalRequests:requests,deviceAcceptance:false},null,2));console.log('Personal statistics browser: '+results.length+' passed.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
