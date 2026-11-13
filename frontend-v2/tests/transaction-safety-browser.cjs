// Display and bridge commands only. Never connects to YJB or executes a financial write.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
const {chromium}=require(path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
const root=path.resolve(__dirname,'..'),reports=path.resolve(root,'../yjb/read/build/reports/mid-autumn');
const report=n=>JSON.parse(fs.readFileSync(path.join(reports,n+'.json'),'utf8'));
const final=n=>structuredClone(report(n).events.find(e=>e.checkpoint==='final').payload);
const html=fs.readFileSync(path.join(root,'index.html'),'utf8').replace('<script>window.V2Pages=','<script>'+fs.readFileSync(path.join(root,'production-read.js'),'utf8')+'</script>\n<script>window.V2Pages=');
const out=path.join(root,'artifacts/transaction-safety');fs.mkdirSync(out,{recursive:true});const results=[];
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
 const page=await browser.newPage({viewport:{width:390,height:844}}),errors=[],external=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/*',r=>{if(r.request().url().startsWith('http://127.0.0.1:4196/'))return r.fulfill({status:200,contentType:'text/html',body:html});if(/^https?:/.test(r.request().url())&&!/kaspersky-labs/.test(r.request().url()))external.push(r.request().url());return r.abort();});
 await page.addInitScript(()=>{window.commands=[];window.NDXYjbRead={post:x=>commands.push(x)};});
 const check=async(name,fn)=>{await fn();assert.deepEqual(errors,[]);results.push({name,pass:true});console.log('PASS '+name);};
 const apply=async p=>page.evaluate(p=>YjbReadApply(p),p);
 const data=final('03-daily-five'),plan=data.plans[0];
 const review={id:'885e825b-6aa0-4a30-9786-c65a2c41141e',count:3,totalAmount:'15',fromDate:'2026-09-24',toDate:'2026-09-29',slots:['2026-09-24','2026-09-28','2026-09-29'].map(date=>({planId:plan.id,date,amount:'5'}))};
 Object.assign(plan,{revision:8,catchupReview:review,automationProblem:'CATCHUP_REVIEW'});
 async function openPlan(){
  await page.locator('.global-nav-item[data-page="持仓"]').click();const h=page.frameLocator('#framePortfolio');
  await h.locator('#sheetHandleZone').focus();await h.locator('#sheetHandleZone').press('Enter');await page.waitForTimeout(750);
  const card=h.locator('.fund-card').first();await card.focus();await card.press('Enter');await page.waitForTimeout(650);
  await h.locator('#level3Settings').waitFor({state:'visible'});await page.waitForTimeout(1050);
  await h.locator('#level3Settings').click();await h.locator('#gapFundPlan').click();await page.locator('[data-plan-id]').click();await page.locator('#nativeCatchupReview').waitFor();
 }
 try{
  await page.goto('http://127.0.0.1:4196/');await page.frameLocator('#framePortfolio').locator('.total-amount').waitFor();await apply(data);await openPlan();
  await check('catchup-preview-and-theme',async()=>{
   const panel=page.locator('#nativeCatchupReview');assert.match(await panel.innerText(),/共 3 期/);assert.match(await panel.innerText(),/¥15.00/);assert.equal(await panel.locator('[data-catchup-action]').count(),2);
   assert.equal(await panel.evaluate(n=>n.scrollWidth<=n.clientWidth),true);
   const light=await panel.evaluate(n=>getComputedStyle(n).backgroundColor);await page.evaluate(()=>V2Store.setTheme('dark'));await page.waitForFunction(()=>document.documentElement.dataset.theme==='dark');
   const dark=await panel.evaluate(n=>getComputedStyle(n).backgroundColor);assert.notEqual(light,dark);assert.notEqual(dark,'rgba(0, 0, 0, 0)');await page.screenshot({path:path.join(out,'catchup-dark.png')});
  });
  await check('explicit-approval-immutable-batch-and-double-click-guard',async()=>{
   const b=page.locator('[data-catchup-action=catchup-approve]');await b.click();assert.equal(await b.isDisabled(),true);
   const request=await page.evaluate(()=>JSON.parse(commands.at(-1).slice('plan-action:'.length)));assert.equal(request.action,'catchup-approve');assert.equal(request.reviewId,review.id);assert.equal(request.planId,plan.id);assert.equal(request.revision,8);assert.equal(request.fundId,plan.fundId);
   await page.locator('#nativeCatchupReview').evaluate(n=>n.querySelector('button').click());assert.equal(await page.evaluate(()=>commands.length),1);
   delete plan.catchupReview;plan.automationProblem=null;plan.revision++;
   await apply({...data,buyResult:{requestId:request.requestId,ok:true,message:'已确认本批次'}});assert.equal(await page.locator('#nativeCatchupReview').count(),0);
  });
  await check('skip-no-trade-command-and-clean-state-after-removal',async()=>{
   plan.catchupReview=review;plan.automationProblem='CATCHUP_REVIEW';await apply(data);await page.locator('[data-catchup-action=catchup-skip]').click();
   const req=await page.evaluate(()=>JSON.parse(commands.at(-1).slice('plan-action:'.length)));assert.equal(req.action,'catchup-skip');assert.equal(req.reviewId,review.id);
   delete plan.catchupReview;plan.automationProblem=null;plan.enabled=false;plan.revision++;
   await apply({...data,buyResult:{requestId:req.requestId,ok:true,message:'已跳过本批次'}});assert.equal(await page.locator('#nativeCatchupReview').count(),0);
   assert.equal(await page.locator('#planToggle').getAttribute('aria-pressed'),'false');await page.locator('#buyBack').click();await page.waitForTimeout(400);assert.equal(await page.locator('#nativeCatchupReview').count(),0);
  });
  await check('pending-write-detail-offers-get-only-recheck',async()=>{
   await page.reload();await page.frameLocator('#framePortfolio').locator('.total-amount').waitFor();
   const p=final('17-wrong-readback');await apply(p);await page.evaluate(()=>V2App.openRecords());const records=page.frameLocator('#frameRecords');await records.locator('[data-record-id="'+p.transactions[0].id+'"]').click();
   await page.locator('[data-native-verify]').waitFor();assert.match(await page.locator('#settingsScroll').innerText(),/同步中/);assert.equal(await page.locator('[data-native-problem]').getAttribute('class'),'settings-footnote');
   const cancel=page.locator('[data-gap-action=cancel]');assert.ok(await cancel.count()===0||await cancel.isDisabled());await page.locator('[data-native-verify]').click();
   const command=await page.evaluate(()=>commands.at(-1));assert.match(command,/^buy-action:/);assert.equal(JSON.parse(command.slice('buy-action:'.length)).action,'sync');
  });
  assert.deepEqual(external,[]);assert.equal(await page.evaluate(()=>commands.filter(x=>/^(buy-create|sell-create):/.test(x)).length),0);
 }finally{fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({environment:'host Edge; synthetic native projection; no live YJB',results},null,2));await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
