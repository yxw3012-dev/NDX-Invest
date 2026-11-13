const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
const base=process.env.FRONTEND_V2_URL||'http://127.0.0.1:4173',artifacts=path.join(__dirname,'../artifacts');fs.mkdirSync(artifacts,{recursive:true});
const backScript=fs.readFileSync(path.join(__dirname,'../android-back.js'),'utf8');
const results=[];
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.BROWSER_EXECUTABLE||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
 async function check(name,run,options={}){
  const context=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true,...options});const page=await context.newPage(),errors=[];page.setDefaultTimeout(5000);
  await page.route('**/*',r=>/^https?:/.test(r.request().url())&&!r.request().url().startsWith(base)?r.abort():r.continue());page.on('pageerror',e=>errors.push(e.message));
  try{await page.goto(base);await page.frameLocator('#framePortfolio').locator('.total-amount').waitFor();await page.locator('.global-nav-item[data-page="持仓"]').click();await page.waitForTimeout(350);await page.frameLocator('#framePortfolio').locator('.action-button.buy').click();await page.waitForTimeout(350);await run(page,context);assert.deepEqual(errors,[]);results.push({name,pass:true});console.log('PASS '+name);}catch(e){results.push({name,pass:false,message:e.message});console.log('FAIL '+name+': '+e.message);await page.screenshot({path:path.join(artifacts,name+'-failure.png')});}finally{await context.close();}
 }
 const open=async p=>{await p.locator('#fundSelect').click();await p.waitForTimeout(500);};
 const shut=async p=>{await p.waitForTimeout(500);assert.equal(await p.locator('#buyFundPicker').getAttribute('aria-hidden'),'true');assert.equal(await p.locator('.buy-page-shell').evaluate(n=>n.inert),false);assert.equal(await p.locator('#fundSelect').getAttribute('aria-expanded'),'false');};
 await check('sheet-source-position-and-confirm',async p=>{
  const rect=await p.locator('#fundSelect').boundingBox();await p.locator('#buyAmount').fill('123');await open(p);
  assert.deepEqual(await p.locator('#fundSelect').boundingBox(),rect);assert.equal(await p.locator('#fundSelect').evaluate(n=>getComputedStyle(n).opacity),'0');assert.match(await p.locator('.fund-picker-dim').evaluate(n=>getComputedStyle(n).backdropFilter),/blur\(8px\)/);
  assert.equal(await p.locator('.fund-morph-overlay,.buy-fund-card').count(),0);await p.screenshot({path:path.join(artifacts,'fund-name-sheet-light.png')});
  assert.equal(await p.locator('.fund-picker-title,.fund-picker-tip,.fund-picker-actions').count(),0);const panel=await p.locator('#fundPickerPanel').boundingBox(),cancel=await p.locator('#fundPickerCancel').boundingBox(),confirm=await p.locator('#fundPickerConfirm').boundingBox();assert.ok(cancel.x<panel.x+panel.width/2&&confirm.x>panel.x+panel.width/2);assert.ok(cancel.y<panel.y+60&&confirm.y<panel.y+60);assert.ok(panel.height<310,'compact sheet');const highlight=await p.locator('.fund-wheel-highlight').evaluate(n=>({background:getComputedStyle(n).backgroundColor,top:getComputedStyle(n).borderTopWidth,bottom:getComputedStyle(n).borderBottomWidth}));assert.deepEqual(highlight,{background:'rgba(0, 0, 0, 0)',top:'1px',bottom:'1px'});
  await p.locator('.fund-name-option[data-index="2"]').click();await p.locator('#fundPickerConfirm').click();await shut(p);
  assert.equal(await p.locator('#buyFundName').innerText(),'华夏纳指100联接');assert.deepEqual(await p.locator('#fundSelect').boundingBox(),rect);assert.equal(await p.locator('#buyAmount').inputValue(),'123');assert.equal(await p.locator('#fundSelect').evaluate(n=>getComputedStyle(n).opacity),'1');
 });
 await check('sheet-draft-and-cancel',async p=>{
  const name=await p.locator('#buyFundName').innerText();for(const action of ['button','outside','escape']){await open(p);await p.locator('.fund-name-option[data-index="2"]').click();await p.waitForTimeout(400);assert.equal(await p.locator('#buyFundName').innerText(),name,'preview must not commit');if(action==='button')await p.locator('#fundPickerCancel').click();else if(action==='outside')await p.locator('.fund-picker-dim').click({position:{x:20,y:50}});else await p.keyboard.press('Escape');await shut(p);assert.equal(await p.locator('#buyFundName').innerText(),name);}
 });
 await check('sheet-rapid-confirm-and-repeated-back',async p=>{
  for(let i=0;i<3;i++){await open(p);await p.locator('#buyFundWheel').focus();await p.keyboard.press(i%2?'Home':'End');await p.locator('#fundPickerConfirm').click();await shut(p);assert.equal(await p.locator('#buyFundName').innerText(),i%2?'易方达标普500A':'华夏纳指100联接');}
  await open(p);assert.equal(await p.evaluate(backScript),true);assert.equal(await p.evaluate(backScript),true);await shut(p);assert.equal(await p.locator('#buyPage').getAttribute('aria-hidden'),'false');
 });
 await check('sheet-native-touch-and-inertia',async(p,context)=>{
  await p.evaluate(()=>{const s=JSON.parse(JSON.stringify(V2Store.getState()));const h=s.holdings[1];s.holdings=Array.from({length:20},(_,i)=>({...h,id:'touch-fund-'+i,name:'滚动基金 '+(i+1)}));s.details=[];V2Store.replaceUiState(s);});await open(p);
  const box=await p.locator('#buyFundWheel').boundingBox(),cdp=await context.newCDPSession(p),x=box.x+box.width/2,y=box.y+box.height-40;
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});for(let i=1;i<=7;i++){await p.waitForTimeout(16);await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x,y:y-i*20}]});}
  const held=await p.locator('#buyFundWheel').evaluate(n=>n.scrollTop);await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await p.waitForTimeout(180);const coast=await p.locator('#buyFundWheel').evaluate(n=>n.scrollTop);assert.ok(held>60,'native drag follows finger');assert.ok(coast>held+10,'native scrolling continues after release');await p.waitForTimeout(900);
  const centered=await p.locator('#buyFundWheel').evaluate(n=>{const o=n.querySelector('.is-current');return Math.abs(o.offsetTop+o.offsetHeight/2-n.scrollTop-n.clientHeight/2);});assert.ok(centered<=1,'released wheel centers nearest fund');const chosen=await p.locator('.fund-name-option.is-current').innerText();await p.locator('#fundPickerConfirm').click();await shut(p);assert.equal(await p.locator('#buyFundName').innerText(),chosen);
  results.push({name:'native-inertia-observation',heldScrollTop:held,afterReleaseScrollTop:coast});
 });
 await check('sheet-stopped-between-funds-snaps-nearest',async p=>{
  await open(p);await p.locator('#buyFundWheel').evaluate(n=>{n.scrollTop=100;});await p.waitForTimeout(700);const nearest=await p.locator('#buyFundWheel').evaluate(n=>{const o=n.querySelector('.is-current');return {name:o.textContent,error:Math.abs(o.offsetTop+o.offsetHeight/2-n.scrollTop-n.clientHeight/2)};});assert.ok(nearest.error<=1,'no half-row resting state');assert.equal(nearest.name,'华夏纳指100联接');await p.locator('#fundPickerConfirm').click();await shut(p);assert.equal(await p.locator('#buyFundName').innerText(),nearest.name);
 });
 await check('sheet-inline-dca-and-sell-selection',async p=>{
  await p.locator('[data-mode="recurring"]').click();await p.locator('#buyAmount').fill('600');await open(p);await p.locator('.fund-name-option[data-index="0"]').click();await p.locator('#fundPickerConfirm').click();await shut(p);assert.equal(await p.locator('#buyFundName').innerText(),'易方达标普500A');assert.equal(await p.locator('#recurringPanel').getAttribute('aria-hidden'),'false');assert.equal(await p.locator('#buyAmount').inputValue(),'600');await p.locator('#buyBack').click();await p.waitForTimeout(400);
  await p.frameLocator('#framePortfolio').locator('.action-button.sell').click();await p.waitForTimeout(350);await open(p);await p.locator('.fund-name-option[data-index="2"]').click();await p.locator('#fundPickerConfirm').click();await shut(p);assert.equal(await p.locator('#buyFundName').innerText(),'华夏纳指100联接');assert.equal(await p.locator('#buyTitle').innerText(),'卖出');assert.equal(await p.locator('.sell-quick-actions').isVisible(),true);
 });
 await check('sheet-dark-long-names',async p=>{
  await p.evaluate(()=>{V2Store.setTheme('dark');const s=JSON.parse(JSON.stringify(V2Store.getState()));s.holdings[1].name='这是用于验证两行显示的非常长的基金名称纳斯达克100指数证券投资基金人民币份额';V2Store.replaceUiState(s);});await open(p);assert.equal(await p.locator('html').getAttribute('data-theme'),'dark');assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);assert.notEqual(await p.locator('.fund-picker-panel').evaluate(n=>getComputedStyle(n).backgroundColor),'rgb(255, 255, 255)');await p.screenshot({path:path.join(artifacts,'fund-name-sheet-dark.png')});await p.locator('#fundPickerCancel').click();await shut(p);
 });
 await check('sheet-reduced-motion-and-live-empty',async p=>{
  await open(p);await p.locator('#fundPickerCancel').click();assert.equal(await p.locator('#buyFundPicker').getAttribute('aria-hidden'),'true');await open(p);await p.evaluate(()=>{const s=JSON.parse(JSON.stringify(V2Store.getState()));s.holdings=[];s.details=[];V2Store.replaceUiState(s);});await shut(p);assert.equal(await p.locator('#buySubmit').isDisabled(),true);await p.locator('#fundSelect').click();assert.equal(await p.locator('#buyFundPicker').getAttribute('aria-hidden'),'true');
 },{reducedMotion:'reduce'});
 await browser.close();fs.writeFileSync(path.join(artifacts,'fund-name-sheet-results.json'),JSON.stringify(results,null,2));const checks=results.filter(r=>'pass'in r);console.log(`${checks.filter(r=>r.pass).length}/${checks.length} fund sheet checks passed`);if(checks.some(r=>!r.pass))process.exitCode=1;
})().catch(e=>{console.error(e);process.exitCode=1;});
