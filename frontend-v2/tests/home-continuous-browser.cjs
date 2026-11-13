const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
const root=path.resolve(__dirname,'..'),canonical=fs.readFileSync(path.join(root,'index.html'),'utf8');
const production=canonical.replace('<script>window.V2Pages=',`<script>${fs.readFileSync(path.join(root,'production-read.js'),'utf8')}</script>\n<script>${fs.readFileSync(path.join(root,'production-market.js'),'utf8')}</script>\n<script>window.V2Pages=`);
// Independent expected geometry: each strategy interval has the same visual width.
function intervalFraction(thresholds,value){
 const boundaries=[0,...thresholds];
 for(let i=1;i<boundaries.length;i++)if(value<=boundaries[i])return ((i-1)+(value-boundaries[i-1])/(boundaries[i]-boundaries[i-1]))/thresholds.length;
 return 1;
}
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.BROWSER_EXECUTABLE||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
 let checks=0;
 try {
  for(const mode of ['mock','production']){
   const page=await browser.newPage({reducedMotion:'reduce'}),errors=[];page.on('pageerror',e=>errors.push(e.message));
   await page.route('**/*',r=>r.request().url().startsWith('http://127.0.0.1:4197')?r.fulfill({status:200,contentType:'text/html',body:mode==='production'?production:canonical}):r.abort());
   if(mode==='production')await page.addInitScript(()=>{window.NDXMarketRead={post(){}};window.NDXYjbRead={post(){}};});
   await page.goto('http://127.0.0.1:4197');const frame=page.frameLocator('#frameNDX100');await frame.locator('#drawdownTrack').waitFor();
   async function update(thresholds,magnitude){
    await page.evaluate(({mode,thresholds,magnitude})=>{
     if(mode==='production')MarketReadApply({nd100:{drawdownText:`-${magnitude.toFixed(2)}%`,drawdownRate:String(-magnitude/100),refreshing:false}});
     else{const s=V2Store.getState();V2Store.replaceUiState({...s,nd100:{...s.nd100,drawdownText:`-${magnitude.toFixed(2)}%`}});}
     const result=V2Store.saveStrategy({tiers:thresholds.map((t,i)=>({drawdownThreshold:String(t),plannedAmount:String((i+1)*500)}))});if(!result.ok)throw Error(result.message);
    },{mode,thresholds,magnitude});
    await page.waitForTimeout(45);
   }
   for(const width of [320,390])for(const theme of ['light','dark'])for(const thresholds of [[10],[10,15,20,30],[5,10,15,20,25,30,40,50],[10,10.1,10.2,20,29.9,30]]){
    await page.setViewportSize({width,height:844});await page.evaluate(t=>V2Store.setTheme(t),theme);await page.waitForTimeout(350);
    for(const magnitude of [0,thresholds[0]-.01,...thresholds,thresholds.at(-1)+.01,99.9]){
     await update(thresholds,magnitude);
     const g=await frame.locator('.static-drawdown').evaluate(card=>{
      const rect=n=>{const r=n.getBoundingClientRect();return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,center:(r.left+r.right)/2}};
      const dot=card.querySelector('.static-marker-dot'),fill=card.querySelector('#drawdownFill');
      return {card:rect(card),track:rect(card.querySelector('#drawdownTrack')),dot:rect(dot),fill:rect(fill),ticks:[...card.querySelectorAll('.drawdown-tick')].map(n=>({...rect(n),reached:n.classList.contains('is-reached')})),labels:[...card.querySelectorAll('#drawdownRangeLabels span')].map(n=>({...rect(n),text:n.textContent})),stage:card.querySelector('#currentStageName').textContent,stageColor:getComputedStyle(card.querySelector('#currentStageName')).color,dotColor:getComputedStyle(dot).backgroundColor,fillColor:getComputedStyle(fill).backgroundColor,overflow:document.documentElement.scrollWidth>document.documentElement.clientWidth};
     });
     const maximum=thresholds.at(-1),fraction=intervalFraction(thresholds,magnitude);
     assert.ok(Math.abs(g.dot.center-(g.track.left+g.track.width*fraction))<.8,`proportional ${mode}/${width}/${theme} ${magnitude}/${maximum}`);
     assert.ok(Math.abs(g.fill.width-g.track.width*fraction)<.8);assert.deepEqual(g.labels.map(x=>x.text),['0%',...thresholds.map(t=>`-${t}%`)]);
     assert.ok(Math.abs(g.labels[0].center-g.track.left)<.8);
     assert.ok(Math.abs((g.track.left-g.card.left)-(g.card.right-g.track.right))<.8,'track endpoint margins are balanced');
     assert.ok(g.labels.every(l=>l.left>g.card.left+12&&l.right<g.card.right-12),'endpoint labels stay within the card');
     for(let i=1;i<g.ticks.length;i++){
      assert.ok(Math.abs(g.ticks[i].left-(g.track.left+g.track.width*i/thresholds.length))<.8);
      assert.ok(Math.abs(g.labels[i].center-g.ticks[i].left)<.8);
     }
     for(let i=0;i<g.labels.length;i++)for(let j=i+1;j<g.labels.length;j++)assert.ok(g.labels[i].right+3<=g.labels[j].left||g.labels[i].bottom<=g.labels[j].top||g.labels[j].bottom<=g.labels[i].top,'dense threshold labels must not overlap');
     assert.equal(g.ticks.filter(t=>t.reached).length,1+thresholds.filter(t=>magnitude>=t).length);
     const active=thresholds.filter(t=>magnitude>=t).length;assert.equal(g.stage,active===0?'观察':'第'+['一','二','三','四','五','六','七','八'][active-1]+'档');
     assert.equal(g.dotColor,g.fillColor);assert.equal(g.fillColor,theme==='light'?'rgb(128, 157, 184)':'rgb(148, 175, 200)');assert.equal(g.stageColor,theme==='light'?'rgb(80, 108, 134)':'rgb(195, 210, 223)');assert.equal(g.overflow,false);assert.deepEqual(errors,[]);checks++;
    }
   }
   await page.emulateMedia({reducedMotion:'no-preference'});await page.setViewportSize({width:390,height:844});
   await update([10,15,20,30],13);await page.waitForTimeout(550);
   await frame.locator('.static-marker-dot').evaluate(n=>window.savedDot=n);
   await update([10,15,20,30],20);await page.waitForTimeout(120);
   const intermediate=await frame.locator('#drawdownFill').evaluate(n=>new DOMMatrix(getComputedStyle(n).transform).a);
   assert.ok(intermediate>intervalFraction([10,15,20,30],13)&&intermediate<intervalFraction([10,15,20,30],20),'fill moves continuously, rather than jumping');await page.waitForTimeout(550);
   assert.equal(await frame.locator('.static-marker-dot').evaluate(n=>n===window.savedDot),true,'data changes preserve the animated dot');
   assert.equal(await frame.locator('#drawdownMarkerBubble,.static-marker-line').count(),0);assert.equal(await frame.locator('.card-meta-note').count(),0);
   for(const theme of ['light','dark']){
    await page.evaluate(t=>V2Store.setTheme(t),theme);await update([10,15,20,30],13);await page.waitForTimeout(600);
    fs.mkdirSync(path.join(root,'artifacts'),{recursive:true});await page.screenshot({path:path.join(root,'artifacts',`home-continuous-${mode}-${theme}.png`)});
   }
   if(mode==='production'){
    await page.evaluate(()=>MarketReadApply({nd100:{drawdownRate:null,drawdownText:'--'}}));await page.waitForTimeout(250);
    assert.equal(await frame.locator('.drawdown-value').innerText(),'--');assert.equal(await frame.locator('.plan-inline-value').innerText(),'--');assert.equal(await frame.locator('.drawdown-tick.is-reached').count(),0);assert.equal(await frame.locator('#drawdownMarker').evaluate(n=>getComputedStyle(n).opacity),'0');
   }
   assert.deepEqual(errors,[]);await page.close();console.log('PASS '+mode+' equal intervals, continuous progress, centered/dynamic ticks, themes and motion');
  }
  console.log(`Continuous drawdown: ${checks} geometry cases passed`);
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
