const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
const root=path.resolve(__dirname,'..'),canonical=fs.readFileSync(path.join(root,'index.html'),'utf8');
assert.equal(canonical.match(/<script id="v2-bottom-refresh-runtime">\n([\s\S]*?)\n<\/script>/)[1],fs.readFileSync(path.join(root,'ui/bottom-refresh.js'),'utf8'));
const html=canonical.replace('<script>window.V2Pages=',`<script>${fs.readFileSync(path.join(root,'production-read.js'),'utf8')}</script><script>${fs.readFileSync(path.join(root,'production-market.js'),'utf8')}</script><script>window.V2Pages=`);
const market={indexText:'30,807.93',changeText:'+306.37',changePercentText:'+1.00%',marketStatus:'已休市',updatedText:'2026-10-03 04:00',fxText:'6.7050',fxChangeText:'-0.02%',drawdownText:'-13.60%',drawdownRate:'-0.136',performance:Array.from({length:4},()=>['+10.00%','+3.00%','-20.00%','15.00%']),refreshing:false,refreshSucceeded:true};
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.BROWSER_EXECUTABLE||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
 const page=await browser.newPage({viewport:{width:390,height:844},hasTouch:true}),errors=[];let passed=0;
 page.on('pageerror',e=>errors.push(e.message));await page.route('**/*',r=>r.request().url().startsWith('http://127.0.0.1:4196')?r.fulfill({status:200,contentType:'text/html',body:html}):r.abort());
 await page.addInitScript(()=>{window.marketCommands=[];window.readCommands=[];window.NDXMarketRead={post:a=>marketCommands.push(a)};window.NDXYjbRead={post:a=>readCommands.push(a)};});
 const home=page.frameLocator('#frameNDX100'),holding=page.frameLocator('#framePortfolio');
 let data;
 const applyMarket=async value=>page.evaluate(m=>MarketReadApply({nd100:m}),value);
 const applyHolding=async status=>page.evaluate(p=>YjbReadApply(p),{data,accountStatus:'CONNECTED',holdingsStatus:status});
 const check=async(name,fn)=>{await fn();assert.deepEqual(errors,[]);passed++;console.log('PASS '+name);};
 try{
  await page.goto('http://127.0.0.1:4196');await home.locator('#refreshButton').waitFor();
  // Reuse frozen fixtures for the read projection; no actual requests or writes.
  {const scripts=[...canonical.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].slice(0,2),vm=require('node:vm'),c={window:{}};scripts.forEach(m=>vm.runInNewContext(m[1],c));const s=c.window.V2Store.getState();data={holdings:s.holdings,details:s.details,overview:s.overview,lastSyncAt:'2026-10-04 12:00'};}
  await applyMarket(market);await applyHolding('READY');await page.waitForTimeout(1900);

  await check('arrows align above navigation and blank space belongs to the scroll root',async()=>{
   const nav=await page.locator('.global-nav').boundingBox();
   for(const [frame,id] of [[home,'#refreshButton'],[holding,'#holdingRefreshButton']]){
    const g=await frame.locator(id).evaluate(n=>{const h=n.parentElement.getBoundingClientRect(),r=n.querySelector('.refresh-hint').getBoundingClientRect();return{position:getComputedStyle(n).position,bottom:h.bottom,hint:r.top+r.height/2,extra:n.parentElement.scrollHeight-n.parentElement.clientHeight}});
    assert.equal(g.position,'relative');assert.ok(Math.abs(g.bottom-(nav.y-12))<2);assert.ok(g.extra>=0);
    if(id==='#holdingRefreshButton')assert.ok(Math.abs(g.hint-(nav.y-36))<3);
   }
  });
  await check('first browse to end never refreshes, including its inertia',async()=>{
   await home.locator('.index-value').hover();await page.mouse.wheel(0,900);await page.waitForTimeout(650);assert.deepEqual(await page.evaluate(()=>marketCommands),[]);assert.equal(await home.locator('#refreshButton').getAttribute('data-refresh-phase'),'idle');
  });
  await check('new upward browse at end refreshes once',async()=>{
   await page.mouse.wheel(0,80);await page.waitForTimeout(40);await page.mouse.wheel(0,100);await page.waitForTimeout(650);assert.deepEqual(await page.evaluate(()=>marketCommands),['refresh']);assert.equal(await home.locator('#refreshButton').getAttribute('data-refresh-phase'),'loading');await page.mouse.wheel(0,160);assert.equal((await page.evaluate(()=>marketCommands)).length,1);
  });
  await check('success folds with check, without an intermediate arrow phase',async()=>{
   await applyMarket(market);assert.equal(await home.locator('#refreshButton').getAttribute('data-refresh-phase'),'success');await page.waitForTimeout(1250);assert.equal(await home.locator('#refreshButton').getAttribute('data-refresh-phase'),'success');await page.waitForTimeout(500);assert.equal(await home.locator('#refreshButton').getAttribute('data-refresh-phase'),'idle');assert.equal(await home.locator('#refreshButton.is-expanded').count(),0);
  });
  await check('failed market read holds X and grey reason until downward close',async()=>{
   await home.locator('#refreshButton').click();await applyMarket({...market,refreshSucceeded:false,refreshError:'汇率更新失败'});await page.waitForTimeout(1800);assert.equal(await home.locator('#refreshButton').getAttribute('data-refresh-phase'),'failure');assert.equal(await home.locator('.refresh-error-text').innerText(),'汇率更新失败');await home.locator('.refresh-error-text').hover();await page.mouse.wheel(0,-90);await page.waitForTimeout(650);assert.equal(await home.locator('#refreshButton').getAttribute('data-refresh-phase'),'idle');
  });
  await check('background updates never expand a closed refresh footer',async()=>{
   await applyMarket({...market,refreshing:true});await applyMarket(market);assert.equal(await home.locator('#refreshButton').getAttribute('data-refresh-phase'),'idle');assert.equal(await home.locator('#refreshButton.is-expanded').count(),0);
  });
  // Touch-only document avoids mixing browser wheel scroll latches with touch injection.
  await page.goto('http://127.0.0.1:4196');await home.locator('#refreshButton').waitFor();await applyMarket(market);await applyHolding('READY');await page.waitForTimeout(800);await page.locator('.global-nav-item[data-page="持仓"]').click();await page.waitForTimeout(500);
  const cdp=await page.context().newCDPSession(page);
  async function swipe(frame,selector,delta){const b=await frame.locator(selector).boundingBox(),x=b.x+b.width/2,y=b.y+b.height/2;await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});for(let i=1;i<=12;i++){await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x,y:y-delta*i/12}]});await page.waitForTimeout(18);}await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await page.waitForTimeout(650);}
  await check('holdings blank-space touch first arms and second triggers',async()=>{
   await swipe(holding,'.refresh-hint',110);assert.deepEqual(await page.evaluate(()=>readCommands),[]);await swipe(holding,'.refresh-hint',130);assert.deepEqual(await page.evaluate(()=>readCommands),['refresh']);assert.equal(await holding.locator('#holdingRefreshButton').getAttribute('data-refresh-phase'),'loading');
  });
  await check('intermediate successful holding read does not end aggregate spinner',async()=>{
   await page.evaluate(p=>YjbReadApply(p),{data,accountStatus:'CONNECTED',holdingsStatus:'READY',refreshCycle:{status:'RUNNING'}});assert.equal(await holding.locator('#holdingRefreshButton').getAttribute('data-refresh-phase'),'loading');
  });
  await check('check failure overrides successful final holding read, preserving data',async()=>{
   const before=await page.evaluate(()=>V2Store.getState().holdings);await page.evaluate(p=>YjbReadApply(p),{data,accountStatus:'CONNECTED',holdingsStatus:'READY',refreshCycle:{status:'FAILURE',errorMessage:'定投检查失败'}});await page.waitForTimeout(1700);assert.equal(await holding.locator('#holdingRefreshButton').getAttribute('data-refresh-phase'),'failure');assert.equal(await holding.locator('.refresh-error-text').innerText(),'定投检查失败');assert.deepEqual(await page.evaluate(()=>V2Store.getState().holdings),before);await swipe(holding,'.refresh-symbol',-90);assert.equal(await holding.locator('#holdingRefreshButton').getAttribute('data-refresh-phase'),'idle');
  });
  await check('one success is not replayed by later background reads',async()=>{
   await holding.locator('#holdingRefreshButton').click();await page.evaluate(p=>YjbReadApply(p),{data,accountStatus:'CONNECTED',holdingsStatus:'READY',refreshCycle:{status:'SUCCESS'}});assert.equal(await holding.locator('#holdingRefreshButton').getAttribute('data-refresh-phase'),'success');await applyHolding('REFRESHING');assert.equal(await holding.locator('#holdingRefreshButton').getAttribute('data-refresh-phase'),'success');await applyHolding('READY');await page.waitForTimeout(1800);assert.equal(await holding.locator('#holdingRefreshButton').getAttribute('data-refresh-phase'),'idle');
  });
  await check('level-two and back retain the original handle and gesture boundaries',async()=>{
   await holding.locator('#sheetHandleZone').press('Enter');await page.waitForTimeout(850);assert.equal(await holding.locator('#holdingRefreshButton').isVisible(),false);assert.equal(await holding.locator('html').evaluate(n=>getComputedStyle(n).overscrollBehavior),'none');await holding.locator('#sheetHandleZone').press('Enter');await page.waitForTimeout(850);assert.equal(await holding.locator('#holdingRefreshButton').isVisible(),true);
  });
  await check('light/dark small and tall viewports retain alignment without overflow',async()=>{
   for(const theme of ['light','dark'])for(const size of [{width:320,height:740},{width:430,height:1000}]){await page.evaluate(t=>V2Store.setTheme(t),theme);await page.setViewportSize(size);await page.waitForTimeout(250);const nav=await page.locator('.global-nav').boundingBox();for(const [frame,id]of [[home,'#refreshButton'],[holding,'#holdingRefreshButton']]){const bottom=await frame.locator(id).evaluate(n=>n.parentElement.getBoundingClientRect().bottom);assert.ok(Math.abs(bottom-(nav.y-12))<2);assert.equal(await frame.locator('html').evaluate(n=>n.scrollWidth<=n.clientWidth),true);}}
  });
  await check('long failure reasons wrap completely in narrow light and dark layouts',async()=>{
   const message='账户读取成功；交易检查暂未完成，请重新登录后核对本地待处理记录。ThisIsAVeryLongUnbrokenDiagnosticStringForLayoutOnly。'.repeat(3);
   for(const theme of ['light','dark']){
    await page.evaluate(t=>V2Store.setTheme(t),theme);await page.setViewportSize({width:320,height:740});await applyHolding('READY');await holding.locator('#holdingRefreshButton').click();
    await page.evaluate(p=>YjbReadApply(p),{data,accountStatus:'CONNECTED',holdingsStatus:'READY',refreshCycle:{status:'FAILURE',errorMessage:message}});await page.waitForTimeout(650);
    const box=await holding.locator('.refresh-error-text').evaluate(n=>({text:n.textContent,width:n.clientWidth,scrollWidth:n.scrollWidth,height:n.clientHeight,scrollHeight:n.scrollHeight,parentHeight:n.parentElement.clientHeight,parentScrollHeight:n.parentElement.scrollHeight}));
    assert.equal(box.text,message);assert.ok(box.height>20);assert.ok(box.scrollWidth<=box.width+1);assert.ok(box.scrollHeight<=box.height+1);assert.ok(box.parentScrollHeight<=box.parentHeight+1);
    await holding.locator('.refresh-error-text').hover();await page.mouse.wheel(0,-100);await page.waitForTimeout(650);assert.equal(await holding.locator('#holdingRefreshButton').getAttribute('data-refresh-phase'),'idle');
   }
  });
  await cdp.detach();console.log('Bottom refresh browser: '+passed+' scenarios passed');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
