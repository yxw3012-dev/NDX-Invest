const path=require('node:path'),{pathToFileURL}=require('node:url'),assert=require('node:assert/strict');
const {chromium}=require(path.join(require('node:os').homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:390,height:844}}),errors=[];page.on('pageerror',e=>errors.push(e.stack));
  await page.goto(pathToFileURL(path.resolve(__dirname,'../index.html')).href);await page.frameLocator('#framePortfolio').locator('.total-amount').waitFor();
  await page.locator('.global-nav-item[data-page="持仓"]').click();await page.waitForTimeout(350);await page.frameLocator('#framePortfolio').locator('.action-button.buy').click();await page.locator('#fundSelect').click();await page.waitForTimeout(500);await page.locator('#buyFundWheel').focus();await page.keyboard.press('End');await page.locator('#fundPickerConfirm').click();await page.waitForTimeout(500);assert.equal(await page.locator('#buyFundName').innerText(),'华夏纳指100联接');await page.locator('#buyAmount').fill('100');await page.locator('#buySubmit').click();
  assert.match(await page.locator('#buyToast').innerText(),/模拟记录已保存/);await page.locator('#buyBack').click();
  await page.frameLocator('#framePortfolio').locator('.action-button.record').click();await page.frameLocator('#frameRecords').locator('[data-record-id="demo-tx-2"]').click();assert.match(await page.locator('#settingsScroll').innerText(),/同步失败/);await page.locator('#settingsBack').click();await page.frameLocator('#frameRecords').locator('.back').click();
  await page.evaluate(()=>{V2Store.setAccountStatus('NOT_CONNECTED');V2App.openProfileEntry('养基宝账户');});assert.match(await page.locator('#settingsScroll').innerText(),/未连接/);await page.locator('[data-gap-action="login"]').click();await page.waitForTimeout(1100);assert.match(await page.locator('#settingsScroll').innerText(),/已连接/);await page.locator('#settingsBack').click();
  await page.evaluate(()=>{V2Store.setHoldingsStatus('OFFLINE_CACHE');V2App.openProfileEntry('数据状态');});assert.match(await page.locator('#settingsScroll').innerText(),/缓存 · Mock/);assert.deepEqual(errors,[]);
  require('node:fs').writeFileSync(path.join(__dirname,'../artifacts/file-smoke.json'),JSON.stringify({passed:true,fileUrl:page.url(),sha256:require('node:crypto').createHash('sha256').update(require('node:fs').readFileSync(path.resolve(__dirname,'../index.html'))).digest('hex'),checks:['navigation','trade Mock','transaction failure details','YJB disconnected and connected','data source offline cache'],errors},null,2));console.log('Standalone file:// display, navigation and mock transaction passed.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
