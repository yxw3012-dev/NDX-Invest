const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
const root=path.resolve(__dirname,'..'),canonical=fs.readFileSync(path.join(root,'index.html'),'utf8');
const html=canonical.replace('<script>window.V2Pages=',`<script>${fs.readFileSync(path.join(root,'production-read.js'),'utf8')}</script>\n<script>${fs.readFileSync(path.join(root,'production-market.js'),'utf8')}</script>\n<script>window.V2Pages=`);
const data={indexText:'30,807.93',changeText:'+306.37',changePercentText:'+1.00%',marketStatus:'已休市',updatedText:'2026-10-03 04:00',fxText:'6.7050',fxChangeText:'-0.02%',fxUpdatedText:'腾讯 · 行情 2026-10-01 02:59',drawdownText:'-13.60%',drawdownRate:'-0.136',historyText:'历史范围 1996-06-06—2026-10-02',drawdownBasisText:'基于 1996-06-06 至 2026-10-02 的历史最高收盘价',performance:Array.from({length:4},()=>['+10.00%','+3.00%','-20.00%','15.00%']),refreshing:false,refreshSucceeded:true};
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.BROWSER_EXECUTABLE||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
 const page=await browser.newPage({viewport:{width:390,height:844}}),errors=[];let passed=0;
 page.on('pageerror',e=>errors.push(e.message));await page.route('**/*',r=>r.request().url().startsWith('http://127.0.0.1:4198')?r.fulfill({status:200,contentType:'text/html',body:html}):r.abort());
 await page.addInitScript(()=>{window.marketCommands=[];window.readCommands=[];window.NDXMarketRead={post:a=>marketCommands.push(a)};window.NDXYjbRead={post:a=>readCommands.push(a)};});
 const home=page.frameLocator('#frameNDX100'),apply=async(m=data,s={indexStatus:'已读取 · 腾讯',fxStatus:'已读取 · 腾讯',lastUpdatedAt:'2026-10-03 18:00'})=>page.evaluate(p=>MarketReadApply(p),{nd100:m,dataSourceState:s});
 const check=async(name,fn)=>{await fn();assert.deepEqual(errors,[]);passed++;console.log('PASS '+name);};
 try {
  await page.goto('http://127.0.0.1:4198');await home.locator('.index-value').waitFor();
  await check('cold loading contains no Mock market values',async()=>{assert.equal(await home.locator('.index-value').innerText(),'--');assert.equal(await home.locator('.fx-value').innerText(),'--');assert.match(await home.locator('.market-status').innerText(),/正在读取/);assert.equal(await home.locator('#refreshButton').isDisabled(),true);});
  await check('live projection removes source and historical header copy',async()=>{await apply();assert.equal(await home.locator('.index-value').innerText(),data.indexText);assert.equal(await home.locator('.fx-value').innerText(),data.fxText);assert.equal(await home.locator('.card-meta-note').count(),0);assert.equal(await home.locator('.market-status span:last-child').innerText(),'已休市');assert.equal(await home.locator('#updatedText').innerText(),data.updatedText);assert.equal(await home.locator('.plan-inline-value').innerText(),'¥500');assert.equal(await home.locator('.metrics-page').first().locator('.metric-value').first().innerText(),'+10.00%');});
  await check('native refresh spins until completion then confirms and returns to arrow',async()=>{
   await home.locator('#refreshButton').click();assert.deepEqual(await page.evaluate(()=>marketCommands),['refresh']);assert.equal(await home.locator('.index-value').innerText(),data.indexText);assert.equal(await home.locator('#updatedText').innerText(),data.updatedText);assert.equal(await home.locator('#refreshButton').isDisabled(),true);
   await page.waitForTimeout(1200);assert.equal(await home.locator('#refreshButton').getAttribute('data-refresh-phase'),'loading');assert.equal(await home.locator('.refresh-arrow svg').evaluate(n=>getComputedStyle(n).animationIterationCount),'infinite');
   await apply();assert.equal(await home.locator('#refreshButton').getAttribute('data-refresh-phase'),'success');
   assert.equal(await home.locator('.refresh-check').evaluate(n=>getComputedStyle(n).transitionDuration),'0.5s, 0.6s');
   await page.waitForTimeout(200);const entering=await home.locator('.refresh-check').evaluate(n=>Number(getComputedStyle(n).opacity));assert.ok(entering>0&&entering<1,'check fades in gradually');
   await page.waitForTimeout(500);assert.equal(await home.locator('.refresh-check').evaluate(n=>getComputedStyle(n).opacity),'1');
   await page.waitForTimeout(500);assert.equal(await home.locator('#refreshButton').getAttribute('data-refresh-phase'),'success');await page.waitForTimeout(700);assert.equal(await home.locator('#refreshButton').getAttribute('data-refresh-phase'),'idle');await page.waitForFunction(()=>{const doc=document.getElementById('frameNDX100').contentDocument;return doc.defaultView.getComputedStyle(doc.querySelector('.refresh-arrow')).opacity==='1';});
  });
  await check('failed refresh retains error until explicit close without a false success check',async()=>{
   await home.locator('#refreshButton').click();await apply({...data,refreshSucceeded:false,fxText:'--',fxChangeText:'--',marketStatus:'缓存 · 已休市 · 腾讯',updatedText:'行情 '+data.updatedText+' · 缓存'},{indexStatus:'缓存 · 腾讯',fxStatus:'读取失败 · 暂无缓存',lastUpdatedAt:'2026-10-03 18:00'});
   assert.equal(await home.locator('#refreshButton').getAttribute('data-refresh-phase'),'failure');assert.equal(await home.locator('#updatedText').innerText(),data.updatedText);assert.equal(await home.locator('.market-status span:last-child').innerText(),'已休市');assert.equal(await home.locator('.fx-value').innerText(),'--');assert.ok(await home.locator('.refresh-error-text').innerText());await home.locator('#refreshButton').press('ArrowUp');await page.waitForTimeout(700);assert.equal(await home.locator('#refreshButton').getAttribute('data-refresh-phase'),'idle');
  });
  await check('unknown drawdown hides marker and preserves editable strategy',async()=>{await apply({...data,drawdownText:'--',drawdownRate:null,drawdownBasisText:'历史数据暂不可用',historyText:'1996—2026 · 截至 2026-10-02'});await page.waitForTimeout(250);assert.equal(await home.locator('#drawdownMarker').evaluate(n=>getComputedStyle(n).opacity),'0');assert.equal(await home.locator('.drawdown-tick.is-reached').count(),0);assert.equal(await home.locator('.plan-inline-value').innerText(),'--');await page.evaluate(()=>V2Store.saveStrategy({tiers:[{drawdownThreshold:'10',plannedAmount:'999'}]}));await apply();assert.equal(await home.locator('.plan-inline-value').innerText(),'¥999');});
  await check('available historical range stays in diagnostic tooltip',async()=>{await apply();assert.equal(await home.locator('.drawdown-head .small-label').getAttribute('title'),data.drawdownBasisText);await page.waitForTimeout(250);assert.equal(await home.locator('#drawdownMarker').evaluate(n=>getComputedStyle(n).opacity),'1');assert.equal(await home.locator('.plan-inline-value').innerText(),'¥999');await apply({...data,drawdownText:'0.00%',drawdownRate:'0'});assert.equal(await home.locator('.plan-inline-value').innerText(),'¥0');await apply();});
  await check('four period transitions align each panel in both themes and widths',async()=>{
   const performance=Array.from({length:4},(_,i)=>[`+${i+1}.00%`,`+${i+2}.00%`,`-${i+3}.00%`,`${i+4}.00%`]);
   await apply({...data,performance});assert.deepEqual(await home.locator('.period-tab').allTextContents(),['1Y','3Y','5Y','10Y']);assert.equal(await home.locator('.metrics-page').count(),4);
   for(const theme of ['light','dark'])for(const width of [390,320]){
    await page.evaluate(t=>V2Store.setTheme(t),theme);await page.setViewportSize({width,height:844});
    for(let i=0;i<4;i++){
     await home.locator('.period-tab').nth(i).click();await page.waitForTimeout(600);
     assert.equal(await home.locator('.period-tab.active').getAttribute('data-index'),String(i));
     assert.equal(await home.locator('.metrics-page').nth(i).locator('.metric-value').first().innerText(),performance[i][0]);
     const aligned=await home.locator('.metrics-page').nth(i).evaluate(n=>{const viewport=n.parentElement.parentElement,view=viewport.getBoundingClientRect(),panel=n.getBoundingClientRect();return Math.abs(view.left+viewport.clientLeft-panel.left)<1&&Math.abs(viewport.clientWidth-panel.width)<1;});
     assert.equal(aligned,true,`${theme} ${width}px period ${i}`);assert.equal(await home.locator('html').evaluate(n=>n.scrollWidth<=n.clientWidth),true);
     const sliderAligned=await home.locator('.period-tab').nth(i).evaluate(n=>{const tab=n.getBoundingClientRect(),slider=n.parentElement.querySelector('.period-slider').getBoundingClientRect();return Math.abs(tab.left-slider.left)<1&&Math.abs(tab.width-slider.width)<1;});
     assert.equal(sliderAligned,true,`${theme} ${width}px slider ${i}`);
    }
   }
   await apply();await home.locator('.period-tab').nth(2).click();
  });
  await check('dark theme and narrow viewport remain usable',async()=>{await page.evaluate(()=>V2Store.setTheme('dark'));assert.equal(await home.locator('html').getAttribute('data-theme'),'dark');await page.setViewportSize({width:320,height:740});assert.equal(await home.locator('html').evaluate(n=>n.scrollWidth<=n.clientWidth),true);assert.equal(await home.locator('.index-value').innerText(),data.indexText);});
  await check('data status page and YJB update keep independent market facts',async()=>{await page.locator('.global-nav-item[data-page="我的"]').click();await page.waitForTimeout(350);await page.frameLocator('#frameMine').locator('.setting-row').filter({hasText:'数据状态'}).click();await page.locator('#settingsScroll').getByText('已读取 · 腾讯',{exact:true}).first().waitFor();assert.match(await page.locator('#settingsScroll').innerText(),/已读取 · 腾讯/);assert.equal(await page.locator('#settingsPage').evaluate(n=>n.classList.contains('is-dark')),true);await page.evaluate(()=>YjbReadApply({data:{holdings:[],details:[],overview:{amount:"--",metrics:["--","--","--","--"]}},accountStatus:'CONNECTED',holdingsStatus:'EMPTY'}));assert.equal(await home.locator('.index-value').innerText(),data.indexText);assert.equal(await home.locator('.card-meta-note').count(),0);});
  console.log(`Market browser: ${passed} scenarios passed`);
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
