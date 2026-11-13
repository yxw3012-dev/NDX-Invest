const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
const {chromium}=require(path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
const root=path.resolve(__dirname,'..'),source=fs.readFileSync(path.join(root,'index.html'),'utf8');
const addon=fs.readFileSync(path.join(root,'appearance.js'),'utf8');
assert.ok(source.endsWith(addon+'\n</script></body>\r\n</html>\r\n'),'offline addon exactly matches its source');
const production=source.replace('<script>window.V2Pages=',`<script>${fs.readFileSync(path.join(root,'production-read.js'),'utf8')}</script>\n<script>${fs.readFileSync(path.join(root,'production-market.js'),'utf8')}</script>\n<script>window.V2Pages=`);
const key='ndx-invest-frontend-v2-palette';
function contrast(a,b){const luminance=color=>{const rgb=color.match(/\d+/g).slice(0,3).map(Number).map(v=>{v/=255;return v<=.04045?v/12.92:((v+.055)/1.055)**2.4;});return rgb[0]*.2126+rgb[1]*.7152+rgb[2]*.0722;};const x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
 const context=await browser.newContext({viewport:{width:390,height:844},colorScheme:'light'}),page=await context.newPage(),errors=[];let passed=0;
 page.on('pageerror',e=>errors.push(e.stack));
 await page.route('**/*',r=>r.request().url().startsWith('http://127.0.0.1:4207')?r.fulfill({status:200,contentType:'text/html',body:r.request().url().endsWith('/production')?production:source}):r.abort());
 await page.addInitScript(()=>{window.appearanceCalls=[];window.readCommands=[];window.marketCommands=[];window.NDXUiLocal={setAppearance:(dark,palette)=>appearanceCalls.push({dark,palette}),setTheme:()=>{},refreshInfo:()=>{},configureReminderStrategy:()=>{},boot:()=>{}};window.NDXYjbRead={post:a=>readCommands.push(a)};window.NDXMarketRead={post:a=>marketCommands.push(a)};});
 const home=page.frameLocator('#frameNDX100'),holdings=page.frameLocator('#framePortfolio'),mine=page.frameLocator('#frameMine');
 const ready=async()=>{await mine.locator('#paletteSettingsRow').waitFor({state:'attached'});await page.waitForFunction(()=>!!V2Store.getState().palette);};
 const check=async(name,fn)=>{await fn();assert.deepEqual(errors,[]);passed++;console.log('PASS '+name);};
 const theme=async value=>{await page.evaluate(t=>V2Store.setTheme(t),value);await page.waitForTimeout(380);};
 const palette=async value=>{assert.equal(await page.evaluate(p=>V2Store.setPalette(p),value),true);await page.waitForTimeout(80);};
 const allPalette=async value=>assert.deepEqual(await page.evaluate(()=>[document,...[...document.querySelectorAll('iframe')].map(f=>f.contentDocument)].map(d=>d.documentElement.dataset.palette)),Array(5).fill(value));
 const financial=()=>page.evaluate(()=>[...document.querySelectorAll('iframe')].flatMap(f=>[...f.contentDocument.querySelectorAll('.profit,.negative,.positive,.action-button,.type-tag,.status-tag,[class*="type-"],.tag[class*="status-"]')].map(n=>({class:n.className,text:n.textContent,color:f.contentWindow.getComputedStyle(n).color,background:f.contentWindow.getComputedStyle(n).backgroundColor}))));
 const geometry=()=>page.evaluate(()=>['frameNDX100','framePortfolio'].map(id=>{const doc=document.getElementById(id).contentDocument;return [...doc.querySelectorAll('.card,.fund-card,.total-amount,.index-value,.static-drawdown,.period-tab')].map(n=>{const s=doc.defaultView.getComputedStyle(n);return {class:n.className,width:s.width,height:s.height,font:s.fontSize,transition:s.transitionDuration,animation:s.animationName};});}));
 try{
  await page.goto('http://127.0.0.1:4207/');await ready();
  await check('classic default and additive entry, invalid palettes rejected',async()=>{
   assert.equal(await page.evaluate(()=>V2Store.getState().palette),'classic');await allPalette('classic');
   assert.equal(await mine.locator('#paletteSettingsRow .row-end > span').innerText(),'经典');
   assert.equal(await page.evaluate(()=>V2Store.setPalette('unknown')),false);
  });
  await check('palette entry supports selection, back and independent brightness',async()=>{
   await page.locator('.global-nav-item[data-page="我的"]').click();await page.waitForTimeout(400);await mine.locator('#paletteSettingsRow').click();
   assert.equal(await page.locator('#settingsTitle').innerText(),'主题配色');await page.locator('[data-palette-choice="pink"]').click();
   assert.equal(await page.locator('[data-palette-choice="pink"]').getAttribute('aria-pressed'),'true');await allPalette('pink');
   assert.equal(await page.evaluate(k=>localStorage.getItem(k),key), 'pink');
   await page.locator('#settingsBack').click();await page.waitForTimeout(400);
   assert.equal(await mine.locator('#paletteSettingsRow .row-end > span').innerText(),'落樱粉');
   const before=await page.evaluate(()=>V2Store.getState().palette);await theme('dark');assert.equal(await page.evaluate(()=>V2Store.getState().palette),before);
   assert.deepEqual(await page.evaluate(()=>appearanceCalls.at(-1)),{dark:true,palette:'pink'});
  });
  await check('financial labels and frozen page dimensions/motion remain unchanged in both modes',async()=>{
   for(const mode of ['light','dark']){
    await theme(mode);await palette('classic');const facts=await financial(),sizes=await geometry();await palette('pink');
    assert.deepEqual((await financial()).map(({class:c,text})=>({class:c,text})),facts.map(({class:c,text})=>({class:c,text})),mode+' financial labels and values');assert.deepEqual(await geometry(),sizes,mode+' geometry/motion');
   }
  });
  await check('pink accents and text meet contrast in light/dark at narrow widths',async()=>{
   for(const mode of ['light','dark']){
    await theme(mode);await palette('pink');
    const colors=await home.locator('html').evaluate(n=>{const s=getComputedStyle(n);return {accent:s.getPropertyValue('--accent-primary').trim(),background:s.backgroundColor,text:s.color};});
    assert.equal(colors.accent,mode==='dark'?'#E9B3BE':'#FBD2D2');assert.ok(contrast(colors.text,colors.background)>=4.5);
    await page.evaluate(()=>V2App.openProfileEntry('主题配色'));
    const labelColors=await page.locator('[data-palette-choice="pink"]').evaluate(n=>({fg:getComputedStyle(n).color,bg:getComputedStyle(n.closest('.settings-card')).backgroundColor}));
    assert.ok(contrast(labelColors.fg,labelColors.bg)>=4.5,mode+' setting text contrast');
    for(const width of [320,390,430]){await page.setViewportSize({width,height:844});assert.equal(await home.locator('html').evaluate(n=>n.scrollWidth<=n.clientWidth),true);}
    await page.locator('#settingsBack').click();await page.waitForTimeout(400);
   }
   await page.setViewportSize({width:390,height:844});
  });
  await check('reload restores palette and brightness, classic restores original tokens',async()=>{
   await palette('pink');await theme('dark');await page.reload();await ready();await allPalette('pink');
   assert.deepEqual(await page.evaluate(()=>appearanceCalls.at(-1)),{dark:true,palette:'pink'});
   await palette('classic');assert.equal(await home.locator('html').evaluate(n=>getComputedStyle(n).getPropertyValue('--accent-primary').trim()),'#5B8CFF');
   await page.reload();await ready();await allPalette('classic');
  });
  await check('system brightness changes retain pink and update native appearance',async()=>{
   await palette('pink');await theme('system');await page.emulateMedia({colorScheme:'dark'});await page.waitForTimeout(400);
   assert.deepEqual(await page.evaluate(()=>appearanceCalls.at(-1)),{dark:true,palette:'pink'});
   await page.emulateMedia({colorScheme:'light'});await page.waitForTimeout(400);assert.deepEqual(await page.evaluate(()=>appearanceCalls.at(-1)),{dark:false,palette:'pink'});
  });
  await check('trade controls, fund sheet and custom wallpaper retain interactions in both pink modes',async()=>{
   await page.locator('.global-nav-item[data-page="持仓"]').click();await page.waitForTimeout(400);await holdings.locator('.action-button.buy').click();
   for(const mode of ['light','dark']){
    await theme(mode);
    const buttonColors=await page.locator('#buySubmit').evaluate(n=>({fg:getComputedStyle(n).color,bg:getComputedStyle(n).backgroundColor}));
    assert.ok(contrast(buttonColors.fg,buttonColors.bg)>=4.5,mode+' submit contrast');
    assert.equal(await page.locator('.buy-mode-button.active').evaluate(n=>getComputedStyle(n).color),mode==='dark'?'rgb(240, 189, 201)':'rgb(162, 77, 97)');
    await page.locator('#fundSelect').click();await page.waitForTimeout(450);
    assert.equal(await page.locator('#fundPickerConfirm').evaluate(n=>getComputedStyle(n).color),mode==='dark'?'rgb(240, 189, 201)':'rgb(162, 77, 97)');
    await page.locator('#buyFundWheel').focus();await page.keyboard.press('End');await page.locator('#fundPickerConfirm').click();await page.waitForTimeout(450);
    assert.equal(await page.locator('#buyFundName').innerText(),'华夏纳指100联接');
   }
   await page.locator('#buyBack').click();await page.waitForTimeout(400);
   const wallpaper='linear-gradient(rgb(10, 20, 30), rgb(30, 20, 10))';
   await holdings.locator('.fund-card').first().evaluate((n,w)=>n.style.backgroundImage=w,wallpaper);
   await palette('classic');await palette('pink');
   assert.equal(await holdings.locator('.fund-card').first().evaluate(n=>getComputedStyle(n).backgroundImage),wallpaper);
  });
  await check('native production updates preserve palette and do not initiate financial operations',async()=>{
   await page.goto('http://127.0.0.1:4207/production');await ready();await allPalette('pink');
   const commands=await page.evaluate(()=>({read:readCommands.slice(),market:marketCommands.slice()}));
   await page.evaluate(()=>YjbReadApply({accountStatus:'CONNECTED',holdingsStatus:'EMPTY',data:{holdings:[],details:[],overview:{amount:'--',metrics:['--','--','--','--']}}}));
   await page.evaluate(()=>MarketReadApply({nd100:{indexText:'30,807.93',drawdownText:'0.00%',drawdownRate:'0',refreshing:false}}));
   await theme('dark');await palette('classic');await palette('pink');await allPalette('pink');
   assert.deepEqual(await page.evaluate(()=>({read:readCommands.slice(),market:marketCommands.slice()})),commands);
   assert.equal(await home.locator('.index-value').innerText(),'30,807.93');
  });
  await check('reduced motion and screenshots for both pink modes',async()=>{
   await page.emulateMedia({reducedMotion:'reduce'});
   await page.locator('.global-nav-item[data-page="NDX100"]').click();await page.waitForTimeout(400);
   const dir=path.join(root,'artifacts/pink-theme');fs.mkdirSync(dir,{recursive:true});
   for(const mode of ['light','dark']){await theme(mode);await page.screenshot({path:path.join(dir,mode+'.png')});}
  });
  console.log(`Appearance browser: ${passed} scenarios passed`);
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
