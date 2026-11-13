const fs=require('fs'),path=require('path'),os=require('os'),assert=require('assert/strict');
const {pathToFileURL}=require('url');
const {chromium}=require(path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
const root=path.resolve(__dirname,'..'),source=fs.readFileSync(path.join(root,'index.html'),'utf8');
async function swipe(session,x,start,end){
 await session.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y:start}]});
 for(let i=1;i<=12;i++){await session.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x,y:start+(end-start)*i/12}]});await new Promise(r=>setTimeout(r,16));}
 await session.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
}
(async()=>{const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});let passed=0;
 try{for(const mode of ['mock-file','production-adapter']){
  const context=await browser.newContext({viewport:{width:390,height:700},isMobile:true,hasTouch:true});const page=await context.newPage(),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  if(mode==='mock-file')await page.goto(pathToFileURL(path.join(root,'index.html')).href);
  else{
   const adapter=fs.readFileSync(path.join(root,'production-read.js'),'utf8');
   const production=source.replace('<script>window.V2Pages=','<script>window.NDXYjbRead={post(){}};'+adapter+'</script><script>window.V2Pages=');
   await page.route('http://127.0.0.1:4234/**',r=>r.fulfill({contentType:'text/html',body:production}));await page.goto('http://127.0.0.1:4234/');
  }
  await page.locator('.global-nav-item[data-page="我的"]').tap();await page.waitForTimeout(350);
  const mine=page.frameLocator('#frameMine'),session=await context.newCDPSession(page);
  for(const palette of ['classic','pink'])for(const theme of ['light','dark'])for(const [width,height] of [[320,568],[390,700],[430,844],[480,600]]){
   await page.setViewportSize({width,height});await page.evaluate(({theme,palette})=>{V2Store.setTheme(theme);V2Store.setPalette(palette);},{theme,palette});await page.waitForTimeout(260);
   await mine.locator('html').evaluate(()=>window.scrollTo(0,0));
   const before=await mine.locator('html').evaluate(()=>({max:document.scrollingElement.scrollHeight-innerHeight,y:scrollY,overflow:getComputedStyle(document.documentElement).overflowY}));
   assert.ok(before.max>0);assert.equal(before.overflow,'auto');
   await swipe(session,Math.min(width/2,210),height-160,110);await page.waitForTimeout(240);
   assert.ok(await mine.locator('html').evaluate(()=>scrollY)>0,mode+' touch must scroll '+width+'x'+height);
   await mine.locator('html').evaluate(()=>window.scrollTo(0,document.scrollingElement.scrollHeight));
   const version=await mine.locator('.setting-row').filter({hasText:'版本'}).boundingBox(),nav=await page.locator('#globalNav').boundingBox();
   assert.ok(version.y+version.height<=nav.y,'last row must clear navigation');
   assert.equal(await mine.locator('html').evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
   passed++;console.log('PASS '+mode+' '+palette+'/'+theme+' native touch '+width+'x'+height);
  }
  await page.setViewportSize({width:390,height:700});
  for(const title of ['个人信息','深色模式','主题配色','提醒','数据说明','版本']){
   await page.evaluate(title=>V2App.openProfileEntry(title,document.getElementById('frameMine').contentDocument.querySelector('.identity-card')),title);
   await page.locator('#settingsPage.is-open').waitFor();await page.locator('#settingsBack').tap();await page.waitForTimeout(260);
   assert.equal(await page.locator('#frameMine').evaluate(f=>f.inert),false);
   await mine.locator('html').evaluate(()=>window.scrollTo(0,0));await swipe(session,190,530,140);await page.waitForTimeout(180);
   assert.ok(await mine.locator('html').evaluate(()=>scrollY)>0,'return from '+title+' must retain native scrolling');passed++;
  }
  for(const title of ['个人信息','提醒','数据状态','深色模式','主题配色','数据说明','版本'])for(const backMode of ['button','escape']){
   const trigger=title==='个人信息'?mine.locator('.identity-card'):mine.locator('.setting-row').filter({has:mine.locator('.row-title',{hasText:title})});
   await trigger.tap();await page.locator('#settingsPage.is-open').waitFor();await page.waitForTimeout(230);
   assert.equal(await mine.locator('body').evaluate(e=>e.inert),true,'modal blocks background controls');
   if(backMode==='button')await page.locator('#settingsBack').tap();else await page.evaluate(()=>document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true})));
   await page.waitForTimeout(260);assert.equal(await page.locator('#settingsPage').evaluate(e=>e.classList.contains('is-open')),false);
   assert.equal(await mine.locator('body').evaluate(e=>e.inert),false,'return unlocks controls');
   assert.equal(await page.locator('#frameMine').evaluate(e=>e.style.pointerEvents),'','return restores touch target');
   await mine.locator('html').evaluate(()=>window.scrollTo(0,0));await swipe(session,190,530,140);await page.waitForTimeout(180);assert.ok(await mine.locator('html').evaluate(()=>scrollY)>0,'real menu '+title+' via '+backMode+' must scroll');passed++;console.log('PASS '+mode+' actual menu '+title+' '+backMode);
  }
  for(let i=0;i<3;i++){await page.locator('.global-nav-item[data-page="NDX100"]').tap();await page.waitForTimeout(330);await page.locator('.global-nav-item[data-page="我的"]').tap();await page.waitForTimeout(330);}
  assert.equal(await page.locator('#frameMine').evaluate(f=>f.classList.contains('is-transitioning')||f.inert),false);
  assert.deepEqual(errors,[]);await context.close();
 }
 console.log('Mine scroll browser: '+passed+' passed; native CDP touch, not physical device acceptance.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});

