/* Unified profile information. Reads device diagnostics only; never dispatches transactions. */
(() => {
 const $=id=>document.getElementById(id),original=window.V2App;
 const titles=new Set(['提醒','数据说明','版本']);let info=null,current=null,lastStrategy='';
 const escape=value=>String(value??'--').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c]);
 const row=(label,value)=>'<div class="settings-row"><span class="settings-row-title">'+escape(label)+'</span><span class="settings-row-end">'+escape(value)+'</span></div>';
 const card=content=>'<section class="settings-card">'+content+'</section>';
 const paragraph=(heading,text)=>'<div class="profile-info-block"><h2>'+escape(heading)+'</h2><p>'+escape(text)+'</p></div>';
 const style=document.createElement('style');style.textContent='.profile-information .settings-card{margin-bottom:18px}.profile-info-block{padding:18px 20px}.profile-info-block+.profile-info-block{border-top:1px solid var(--border-subtle,#e7eaed)}.profile-info-block h2{font-size:14px;margin:0 0 8px;color:var(--text-primary,#17212b);font-weight:600}.profile-info-block p{font-size:13px;line-height:1.75;margin:0;color:var(--text-secondary,#879299)}.profile-reminder-row{padding:18px 20px}.profile-reminder-head{display:flex;justify-content:space-between;gap:18px;align-items:center;font-size:14px;font-weight:600;color:var(--text-primary,#17212b)}.profile-reminder-head small{font-size:12px;font-weight:400;color:var(--text-secondary,#879299)}.profile-reminder-row+.profile-reminder-row{border-top:1px solid var(--border-subtle,#e7eaed)}.profile-reminder-row p{margin:8px 0 0;font-size:12px;line-height:1.7;color:var(--text-secondary,#879299)}.profile-information .settings-label{margin:22px 4px 10px}.profile-information .settings-row-end{max-width:56%;white-space:normal;overflow-wrap:anywhere;text-align:right}.profile-system-button{width:100%;min-height:48px;padding:12px 20px;text-align:left;border:0;border-top:1px solid var(--border-subtle,#e7eaed);background:transparent;color:var(--accent-primary,#398f79);font:inherit;font-size:13px;cursor:pointer}.profile-system-button:disabled{color:var(--text-tertiary,#8a949c)}.profile-reminder-switch{flex-shrink:0;border:0;border-radius:20px;padding:6px 12px;font:inherit;font-size:12px;background:var(--bg-secondary,#eef1f2);color:var(--text-secondary,#879299);cursor:pointer}.profile-reminder-switch[aria-pressed=true]{color:var(--accent-primary,#398f79);background:color-mix(in srgb,var(--accent-primary,#398f79) 15%,transparent)}.profile-reminder-switch:disabled{opacity:.5;cursor:default}';document.head.append(style);
 function render(){if(!current||!$('settingsPage').classList.contains('is-open'))return;
  const scroll=$('settingsScroll'),active=document.activeElement?.id;let body='';
  if(current==='提醒'){
   const native=!!window.NDXUiLocal;
   const reminder=(key,title,text)=>{const s=info?.reminders?.[key],enabled=s?.enabled!==false;const status=!native?'仅 App 可用':!info?'读取中':!enabled?'已关闭':!info.notificationsAllowed?'待授权':!s?.channelAllowed?'通道已关闭':'已开启';return '<div class="profile-reminder-row"><div class="profile-reminder-head"><span>'+title+'</span><button class="profile-reminder-switch" data-reminder="'+key+'" aria-label="'+title+'" aria-pressed="'+enabled+'" '+(!native||!info?'disabled':'')+'>'+status+'</button></div><p>'+text+'</p></div>';};
   body=card(reminder('drawdown','回撤提醒','真实指数行情更新时检查 NDX100 策略档位；买入仍由你选择基金并确认。')+reminder('dca','定投提醒','定投确认正式净值并完成养基宝同步验证后提醒，不改变计划执行方式。')+reminder('exception','异常提醒','提示登录失效、同步失败和持仓变化等需要处理的情况。'));
   body+='<div class="settings-label">通知权限</div>'+card(row('系统通知',info?(info.notificationsAllowed?'已允许':'未允许'):(native?'读取中':'仅 App 可用'))+'<button class="profile-system-button" data-notification-action="permission" '+(!native?'disabled':'')+'>'+ (info?.notificationsAllowed?'查看系统通知设置 ›':'开启系统通知 ›')+'</button><button class="profile-system-button" data-system-settings="notifications" '+(!native?'disabled':'')+'>查看系统通知设置 ›</button><button class="profile-system-button" data-notification-action="test" '+(!native?'disabled':'')+'>发送测试通知 ›</button>');
   if(info?.notificationMessage)body+=card(paragraph('测试通知',info.notificationMessage));
   body+='<div class="settings-label">提醒可靠性</div>'+card(row('通知发送',native?'已接入系统通知':'网页预览不发送通知')+row('电池限制',info?(info.batteryUnrestricted?'未受电池优化限制':'受系统电池优化管理'):'读取中')+paragraph('后台运行','回撤在行情更新时检查；定投沿用现有后台检查。系统省电、网络和应用运行状态可能延迟检查或通知。等待净值不会连续发送提醒，历史交易不会批量补发通知。')+'<button class="profile-system-button" data-system-settings="battery" '+(!native?'disabled':'')+'>查看应用电池设置 ›</button>');
   if(info?.settingsError)body+=card(paragraph('设置',info.settingsError));
  }
  if(current==='数据说明'){
   const real=!!window.NDXYjbRead;
   body=card(row('当前数据模式',real?'真实账户 / 行情数据':'Mock 网页预览'));
   body+=card(paragraph('当前持仓','当前份额、成本、市值和持有收益以养基宝返回的持仓为准。NDX 本地交易记录不用于重建或覆盖当前持仓。')+paragraph('净值与估值','正式净值用于交易确认，估值仅供展示。交易会等待符合日期规则的正式历史净值；确认日期展示 NDX 首次读取到该净值的日期，可能晚于净值所属日。')+paragraph('交易与初始持仓','买入、卖出和初始持仓导入记录由 NDX 本地保存。初始导入只记录首次观察到的持仓，不代表历史买入；交易同步更新养基宝最终持仓，不代表实际支付或到账。')+paragraph('指数与汇率','指数行情使用腾讯 / 新浪数据源，汇率使用东方财富 / 腾讯数据源，长期表现使用 Nasdaq 历史数据。行情时间以数据源返回时间为准，各来源更新速度可能不同。')+paragraph('缓存与更新时间','网络不可用时可展示最后成功快照，并标明缓存及最后同步时间。页面时间分别表示行情时间、持仓同步时间或记录时间。')+paragraph('本地展示','金额、份额和收益率按两位小数四舍五入展示，净值保留原精度；格式化后的数字不参与业务计算。自定义基金名称、壁纸、回撤策略和补充的持有起始日期保存在本地。')+paragraph('持有时间','持有起始日期取已同步买入记录的最早正式净值所属日与补充的初始持有日期中较早者。没有有效日期时显示 --，不反推历史交易。'));
  }
  if(current==='版本'){
   body=card(paragraph('NDX Invest','专注长期 · 让投资更简单'))+card(row('当前版本',info?'v'+info.versionName:'网页预览')+row('构建号',info?.versionCode??'--')+row('运行模式',info?(info.mode==='production'?'真实数据':'演示数据'):'Frontend V2 预览')+row('Android 版本',info?.androidVersion??'--')+row('安装类型',info?(info.debug?'开发构建':'正式构建'):'--'))+card(paragraph('版本内容','Frontend V2、养基宝账户与持仓同步、本地交易记录、定投计划管理和本地外观设置。')+paragraph('更新方式','当前通过安装新版本更新，未接入自动更新。'));
  }
  scroll.classList.add('profile-information');scroll.innerHTML=body;if(active)$(active)?.focus({preventScroll:true});
 }
 window.V2SettingsInfo={receive(payload){info={...info,...payload};render();const doc=$('frameMine')?.contentDocument;const version=[...(doc?.querySelectorAll('.setting-row')||[])].find(r=>r.querySelector('.row-title')?.textContent==='版本');if(version&&info.versionName)version.querySelector('.row-end > span').textContent='v'+info.versionName;}};
 window.V2App=Object.freeze({...original,openProfileEntry(title,trigger){current=titles.has(title)?title:null;$('settingsScroll').classList.remove('profile-information');original.openProfileEntry(title,trigger);if(current){render();window.NDXUiLocal?.refreshInfo();}}});
 $('settingsScroll').addEventListener('click',event=>{
  const button=event.target.closest('button');if(!button||button.disabled)return;
  if(button.dataset.systemSettings)window.NDXUiLocal?.openSystemSettings(button.dataset.systemSettings);
  if(button.dataset.notificationAction==='permission'){if(info?.notificationsAllowed)window.NDXUiLocal?.openSystemSettings('notifications');else window.NDXUiLocal?.requestNotifications();}
  if(button.dataset.notificationAction==='test')window.NDXUiLocal?.sendTestNotification();
  if(button.dataset.reminder)window.NDXUiLocal?.setReminderEnabled(button.dataset.reminder,button.getAttribute('aria-pressed')!=='true');
 });
 function shareStrategy(){if(!window.NDXUiLocal)return;const tiers=window.V2Store.getState().nd100Strategy?.tiers;if(!tiers?.length)return;const text=JSON.stringify(tiers.map(t=>({drawdownThreshold:String(t.drawdownThreshold),plannedAmount:String(t.plannedAmount)})));if(text!==lastStrategy){lastStrategy=text;window.NDXUiLocal.configureReminderStrategy(text);}}
 window.V2Store.subscribe(shareStrategy);shareStrategy();
 $('settingsBack').addEventListener('click',()=>{current=null;$('settingsScroll').classList.remove('profile-information');});
 document.addEventListener('visibilitychange',()=>{if(!document.hidden)window.NDXUiLocal?.refreshInfo();});
 $('frameMine').addEventListener('load',()=>{if(info)window.V2SettingsInfo.receive(info);});
 window.NDXUiLocal?.refreshInfo();
})();
