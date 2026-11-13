const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
const {chromium}=require(path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
const root=path.resolve(__dirname,'..'),source=fs.readFileSync(path.join(root,'index.html'),'utf8'),adapter=fs.readFileSync(path.join(root,'production-read.js'),'utf8');
const html=source.replace('<script>window.V2Pages=','<script>'+adapter+'</script>\n<script>window.V2Pages=');
const fundId='yjb:1:17';
const data={displayName:'测试用户',maskedId:'***',holdings:[{id:fundId,fundId:'17',fundCode:'040046',name:'华安纳斯达克100ETF联接(QDII)A',amount:'¥1100',profit:'+¥100',availableSharesText:'100',meta:'测试账户',autoInvest:true}],details:[{id:fundId,amount:'¥1100',cost:'¥1000',shares:'100',avgNav:'10',profit:'+¥100',profitRate:'+10%',annualized:'--',nav:'11',navDate:'2026-10-08'}],overview:{amount:'¥1100',metrics:['¥1000','+¥100','+10%','--']}};
const tx=(id,status,date,plan='plan-1',amount='5')=>({id,fundId,fundName:data.holdings[0].name,type:plan?'定投':'买入',direction:'BUY',originPlanId:plan,scheduledDate:plan?date:null,dateKey:date,dateLabel:date,amountInput:amount,primaryText:'¥'+amount,transactionStatus:status,feeRate:'0',feeInput:'0',timePeriod:'before',confirmedNav:status==='SYNCED'?'11':null,confirmedShares:status==='SYNCED'?'0.45':'--',shareDelta:status==='SYNCED'?'0.45':'--'});
const transactions=[tx('waiting','WAITING_NAV','2026-10-08'),tx('done-1','SYNCED','2026-09-30'),tx('done-2','SYNCED','2026-09-29'),tx('failed','SYNC_FAILED','2026-09-28'),tx('ordinary','SYNCED','2026-09-27',null,'100')];
const plans=[{id:'plan-1',fundId,amountInput:'5',feeRate:'0',frequency:'daily',nextDateDisplay:'2026.10.09',nextStatusText:'待执行',enabled:true,revision:1}];
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
 const page=await browser.newPage({viewport:{width:390,height:844},hasTouch:true}),errors=[],results=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/*',r=>r.request().url().startsWith('http://127.0.0.1:4198')?r.fulfill({status:200,contentType:'text/html',body:html}):r.abort());
 await page.addInitScript(()=>{window.commands=[];window.NDXYjbRead={post:a=>commands.push(a)};});
 const apply=extra=>page.evaluate(p=>YjbReadApply(p),{data,transactions,plans,initialPositions:[],accountStatus:'CONNECTED',holdingsStatus:'READY',...extra});
 const check=async(name,fn)=>{await fn();assert.deepEqual(errors,[]);results.push(name);console.log('PASS '+name);};
 try{
  await page.goto('http://127.0.0.1:4198');await page.frameLocator('#framePortfolio').locator('.total-amount').waitFor();await apply();await page.locator('.global-nav-item[data-page="持仓"]').tap();await page.waitForTimeout(400);
  await page.evaluate(id=>V2App.detailSettings(id),fundId);await page.frameLocator('#framePortfolio').locator('#gapFundPlan').tap();await page.locator('.dca-summary-metrics').waitFor();
  await check('synced-only-statistics-and-next-date',async()=>{assert.deepEqual(await page.locator('.dca-summary-metrics strong').allTextContents(),['¥10.00','2']);assert.match(await page.locator('.fund-plan-next').innerText(),/2026.10.09.*待执行/s);assert.equal(await page.locator('.dca-record-row').count(),4);assert.match(await page.locator('[data-dca-transaction="waiting"]').innerText(),/定投进行中/);});
  await check('record-opens-existing-detail-and-back-restores-plan',async()=>{await page.locator('[data-dca-transaction="waiting"]').tap();assert.equal(await page.locator('#settingsTitle').innerText(),'交易详情');assert.match(await page.locator('#settingsScroll').innerText(),/待公布净值/);await page.locator('#settingsBack').tap();assert.equal(await page.locator('#settingsTitle').innerText(),'定投计划');assert.equal(await page.locator('.dca-record-row').count(),4);});
  for(const palette of ['classic','pink'])for(const theme of ['light','dark'])for(const width of [320,390,430])await check('layout-'+palette+'-'+theme+'-'+width,async()=>{await page.setViewportSize({width,height:844});await page.evaluate(p=>{V2Store.setPalette(p.palette);V2Store.setTheme(p.theme);},{palette,theme});await page.waitForTimeout(300);assert.equal(await page.locator('#settingsScroll').evaluate(el=>el.scrollWidth<=el.clientWidth+1),true);});
  await check('large-amount-and-multiple-plan-statistics',async()=>{await apply({transactions:[tx('million','SYNCED','2026-09-30','deleted-plan','1234567.89'),tx('second','SYNCED','2026-09-29','plan-1','0.11'),tx('waiting','WAITING_NAV','2026-10-08')]});assert.deepEqual(await page.locator('.dca-summary-metrics strong').allTextContents(),['¥1,234,568.00','2']);assert.equal(await page.locator('#settingsScroll').evaluate(el=>el.scrollWidth<=el.clientWidth+1),true);});
  await check('empty-records-and-plan-management',async()=>{await apply({transactions:[]});assert.deepEqual(await page.locator('.dca-summary-metrics strong').allTextContents(),['¥0.00','0']);assert.match(await page.locator('#settingsScroll').innerText(),/暂无定投记录/);await page.locator('[data-plan-id="plan-1"]').tap();assert.equal(await page.locator('#buyTitle').innerText(),'定投计划管理');});
  assert.deepEqual(await page.evaluate(()=>commands.filter(a=>/^(buy-|sell-|plan-action:)/.test(a))),[]);
  fs.mkdirSync(path.join(root,'artifacts/dca-queue-verification'),{recursive:true});fs.writeFileSync(path.join(root,'artifacts/dca-queue-verification/browser.json'),JSON.stringify({results,errors,realAccountWrites:false,deviceAcceptance:false},null,2));
  console.log(results.length+' DCA browser checks passed');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
