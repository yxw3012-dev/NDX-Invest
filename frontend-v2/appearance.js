/* Presentation preferences only. Embedded in index.html for offline/file and Android use. */
(() => {
  'use strict';
  const storageKey='ndx-invest-frontend-v2-palette';
  const labels={classic:'经典',pink:'落樱粉'};
  const base=window.V2Store;
  let saved='classic';
  try{const value=localStorage.getItem(storageKey);if(Object.hasOwn(labels,value))saved=value;}catch(_){}
  const css=`
html[data-palette="pink"] {
  --accent-primary:#FBD2D2;--accent-primary-hover:#F3BDC6;--accent-primary-pressed:#EDAFBD;
  --accent-soft:#FFEFF0;--palette-button-text:#642D40;--palette-ink:#A24D61;
  --bg-primary:#F5F5F5;--bg-secondary:#F9F5F6;
  --surface-primary:#FFFFFF;--surface-secondary:#FFF6F6;--surface-elevated:#FFEFF0;
  --border-subtle:#EBE4E7;--border-normal:#DFCFD6;
  --text-primary:#27232A;--text-secondary:#77717A;--text-tertiary:#77717A;
  --profit:#24866D;--profit-soft:#E6F3ED;--loss:#BE435E;--loss-soft:#F9E8ED;
  --warning:#A66C24;--warning-soft:#FAF0DE;
  --green:var(--profit);--green-bg:var(--profit-soft);--red:var(--loss);--red-bg:var(--loss-soft);
  --buy-text:var(--profit);--buy-soft:var(--profit-soft);--sell-text:var(--loss);--sell-soft:var(--loss-soft);
  --bg:var(--bg-primary);--surface:var(--surface-primary);--surface-soft:var(--surface-secondary);
  --text:var(--text-primary);--text-2:var(--text-secondary);--muted:var(--text-tertiary);
  --text-3:var(--text-tertiary);--surface-2:var(--surface-secondary);--brand-soft:var(--accent-soft);
  --line:var(--border-subtle);--divider:var(--border-subtle);
  --brand:var(--palette-ink);--brand-deep:var(--palette-ink);--calendar-blue:var(--palette-ink);
}
html[data-palette="pink"][data-theme="dark"] {
  --accent-primary:#E9B3BE;--accent-primary-hover:#F0BDC9;--accent-primary-pressed:#D9A2B0;
  --accent-soft:#342B31;--palette-button-text:#3C1F2B;--palette-ink:#F0BDC9;
  --bg-primary:#141518;--bg-secondary:#191A1E;
  --surface-primary:#1F2024;--surface-secondary:#27282D;--surface-elevated:#303137;
  --border-subtle:#34353C;--border-normal:#43454F;
  --text-primary:#F1F2F4;--text-secondary:#ACABB3;--text-tertiary:#ACABB3;
  --profit:#69C5A3;--profit-soft:#203B32;--loss:#EF8297;--loss-soft:#3F2330;
  --warning:#DDB073;--warning-soft:#403222;
}
html[data-palette="pink"],html[data-palette="pink"] body,
html[data-palette="pink"] :is(.app-shell,.app,.page-frame,.records-page,.buy-page,.feature-page,.level3-page,.holding-settings) {
  background-color:var(--bg-primary);color:var(--text-primary);
}
html[data-palette="pink"] .app {background-image:none;}
html[data-palette="pink"] :is(.card,.fund-card,.buy-card,.settings-group,.profile-card,.strategy-tier,.main-sheet,.sheet-panel,.panel,.total-card,.holding-overview-card,.stat-card,.holding-summary,.plan-sheet-panel,.fund-picker-panel) {
  background-color:var(--surface-primary);border-color:var(--border-subtle);
}
html[data-palette="pink"] .fund-card::after {border-color:color-mix(in srgb,var(--accent-primary) 7%,transparent);box-shadow:0 0 0 22px color-mix(in srgb,var(--accent-primary) 4.3%,transparent),0 0 0 45px color-mix(in srgb,var(--accent-primary) 2.4%,transparent);}
html[data-palette="pink"] :is(.brand-invest,.status-ok,.row-icon,.avatar,.page-title-accent) {color:var(--palette-ink);}
html[data-palette="pink"] .avatar {background:var(--accent-soft);}
html[data-palette="pink"] .identity-card {background:var(--surface-primary);border-color:var(--border-subtle);}
html[data-palette="pink"] .identity-card::before,html[data-palette="pink"] .identity-card::after {background:none;}
html[data-palette="pink"] .identity-title {color:var(--text-primary);}
html[data-palette="pink"] .market-status {background:var(--accent-soft);color:var(--palette-ink);}
html[data-palette="pink"] :is(.status-dot,.holding-dot) {background:var(--palette-ink);box-shadow:0 0 0 4px var(--accent-soft);}
html[data-palette="pink"] .market-status .status-dot {box-shadow:none;}
html[data-palette="pink"] .detail-status {background:var(--accent-soft);color:var(--palette-ink);}
html[data-palette="pink"] .status-done {background:var(--profit-soft);color:var(--profit);}
html[data-palette="pink"] .status-waiting {background:#ECF0F3;color:#707D89;}
html[data-palette="pink"][data-theme="dark"] .status-waiting {background:#293039;color:#A5B2BD;}
html[data-palette="pink"] .status-warning {background:var(--warning-soft);color:var(--warning);}
html[data-palette="pink"] .status-error {background:var(--loss-soft);color:var(--loss);}
html[data-palette="pink"] :is(.type-buy,.action-button.buy) {background:var(--profit-soft);color:var(--profit);}
html[data-palette="pink"] .action-button.sell {background:var(--loss-soft);color:var(--loss);}
html[data-palette="pink"] .action-button.record {background:var(--accent-soft);color:var(--palette-ink);}
html[data-palette="pink"] .type-sell {background:#FCE7E2;color:#B45550;}
html[data-palette="pink"][data-theme="dark"] .type-sell {background:#3C2825;color:#EA9C8F;}
html[data-palette="pink"] .brand-ndx {color:#17284C;}
html[data-palette="pink"][data-theme="dark"] .brand-ndx {color:var(--text-primary);}
html[data-palette="pink"] .global-nav {background:var(--surface-primary);border-color:var(--border-normal);}
html[data-palette="pink"] .global-nav-indicator {background:var(--accent-soft);}
html[data-palette="pink"] .global-nav-item.is-active {color:var(--palette-ink);}
html[data-palette="pink"] .static-drawdown {
  --dd-track:var(--accent-soft);--dd-progress:var(--accent-primary);--dd-tick:var(--border-normal);
  --dd-passed:var(--palette-ink);--dd-label:var(--text-secondary);--dd-label-passed:var(--palette-ink);
}
html[data-palette="pink"] :is(.period-tabs,.sliding-tabs,.metrics-viewport,.longterm-metrics,.drawdown-summary) {background:var(--surface-secondary);border-color:var(--border-subtle);}
html[data-palette="pink"] .period-slider {background:var(--accent-soft)!important;box-shadow:none;}
html[data-palette="pink"] .period-tab.active {background:transparent!important;color:var(--palette-ink)!important;}
html[data-palette="pink"] .settings-page {
  --sp-bg:var(--bg-primary);--sp-card:var(--surface-primary);--sp-text:var(--text-primary);
  --sp-muted:var(--text-secondary);--sp-line:var(--border-subtle);--sp-soft:var(--accent-soft);--sp-green:var(--palette-ink);
}
html[data-palette="pink"] :is(.settings-choice .is-check,.settings-sheet-option span:last-child,.settings-brand-mark,.strategy-explain-title,.strategy-add,.strategy-tier-badge,.fund-picker-toolbar button,#buyDateConfirm,#buyRecurringConfirm,.buy-fee-rate-input:focus) {color:var(--palette-ink);}
html[data-palette="pink"] :is(.strategy-add,.strategy-tier-badge,.settings-brand-mark,.buy-mode-indicator) {background:var(--accent-soft);}
html[data-palette="pink"] :is(.buy-mode-button.active,.fund-chevron,.auto-invest) {color:var(--palette-ink);}
html[data-palette="pink"] .buy-mode {background:var(--surface-secondary);}
html[data-palette="pink"] .fund-select-card {background:var(--surface-primary);border-color:var(--border-subtle);}
html[data-palette="pink"] :is(.fund-select-name,.amount-input,.amount-currency,.strategy-input-wrap input) {color:var(--text-primary);}
html[data-palette="pink"] .strategy-input-wrap {background:var(--surface-secondary);color:var(--text-secondary);}
html[data-palette="pink"] .strategy-input-wrap:focus-within {background:var(--surface-elevated);}
html[data-palette="pink"] .sell-rate-option[aria-checked="true"] .sell-rate-mark {background:var(--accent-primary);border-color:var(--accent-primary);color:var(--palette-button-text);}
html[data-palette="pink"] :is(.buy-submit,.buy-page.is-sell .buy-submit) {background:var(--accent-primary);color:var(--palette-button-text);box-shadow:none;}
html[data-palette="pink"] :is(.buy-submit:active,.buy-page.is-sell .buy-submit:active) {background:var(--accent-primary-pressed);}
html[data-palette="pink"] .buy-submit-wrap {background:linear-gradient(to top,var(--bg-primary) 70%,color-mix(in srgb,var(--bg-primary) 96%,transparent) 86%,transparent);}
html[data-palette="pink"] .amount-input {caret-color:var(--accent-primary);}
html[data-palette="pink"] .buy-date-highlight {border-color:var(--border-normal);background:var(--accent-soft);}
html[data-palette="pink"] .settings-action:not(.is-danger) {border-color:var(--border-normal);}
html[data-palette="pink"] :is(.settings-toggle[aria-pressed="true"],.toggle:not(.off),.feature-toggle:not(.off)) {background:var(--accent-primary);border-color:var(--accent-primary);}
html[data-palette="pink"] .settings-toggle.is-switching-on {animation-name:ndx-pink-track-on;}
html[data-palette="pink"] .settings-toggle.is-switching-off {animation-name:ndx-pink-track-off;}
@keyframes ndx-pink-track-on {from{background:var(--surface-elevated);border-color:var(--border-normal)}to{background:var(--accent-primary);border-color:var(--accent-primary)}}
@keyframes ndx-pink-track-off {from{background:var(--accent-primary);border-color:var(--accent-primary)}to{background:var(--surface-elevated);border-color:var(--border-normal)}}
html[data-palette="pink"] :is(.refresh,.v2-bottom-refresh.refresh),html[data-palette="pink"] .refresh-arrow {color:var(--palette-ink);}
html[data-palette="pink"] .refresh-check {color:var(--profit,var(--green,#2a8b67));}
html[data-palette="pink"] :is(.top-safe,.topbar,.filters-wrap) {background:var(--bg-primary);}
html[data-palette="pink"] :where(button,a,[role="button"]):focus-visible {outline-color:var(--palette-ink);}
.palette-swatch {display:inline-block;width:16px;height:16px;border-radius:50%;margin-right:12px;vertical-align:middle;background:#2a8b67;}
.palette-swatch[data-swatch="pink"] {background:#FBD2D2;}
html[data-theme="dark"] .palette-swatch[data-swatch="pink"] {background:#E9B3BE;}
`;
  function documents(){return [document,...[...document.querySelectorAll('iframe')].map(f=>f.contentDocument)].filter(d=>d?.documentElement);}
  let nativeSignature='';
  function nativeAppearance(){
    const dark=document.documentElement.dataset.theme==='dark',palette=window.V2Store.getState().palette||saved,signature=palette+':'+dark;
    if(signature!==nativeSignature&&window.NDXUiLocal?.setAppearance){window.NDXUiLocal.setAppearance(dark,palette);nativeSignature=signature;}
  }
  function apply(){
    const palette=window.V2Store.getState().palette||saved;
    for(const doc of documents()){
      if(!doc.getElementById('ndx-palette-style')){const style=doc.createElement('style');style.id='ndx-palette-style';style.textContent=css;doc.head.append(style);}
      if(doc.documentElement.dataset.palette!==palette)doc.documentElement.dataset.palette=palette;
    }
    const mine=document.getElementById('frameMine')?.contentDocument;
    const row=mine?.getElementById('paletteSettingsRow');if(row)row.querySelector('.row-end > span').textContent=labels[palette];
    document.querySelectorAll('[data-palette-choice]').forEach(b=>{const active=b.dataset.paletteChoice===palette;b.setAttribute('aria-pressed',String(active));b.querySelector('.is-check').hidden=!active;});
    nativeAppearance();
  }
  window.V2Store=Object.freeze({...base,setPalette(palette){
    if(!Object.hasOwn(labels,palette))return false;
    try{localStorage.setItem(storageKey,palette);}catch(_){return false;}
    if(base.getState().palette!==palette)base.replaceUiState({...base.getState(),palette});
    return true;
  }});
  base.subscribe(apply);
  // Existing page subscriptions require loaded frame DOM before the first publish.
  Promise.all([...document.querySelectorAll('iframe')].map(frame=>frame.contentDocument?.body?.childElementCount?Promise.resolve():new Promise(resolve=>frame.addEventListener('load',resolve,{once:true})))).then(()=>{
    if(!base.getState().palette)base.replaceUiState({...base.getState(),palette:saved});
  });
  const original=window.V2App;
  window.V2App=Object.freeze({...original,openProfileEntry(title,trigger){
    if(title!=='主题配色')return original.openProfileEntry(title,trigger);
    original.openProfileEntry('深色模式',trigger);
    document.getElementById('settingsTitle').textContent='主题配色';
    document.getElementById('settingsPage').dataset.section='palette';
    document.getElementById('settingsScroll').innerHTML='<section class="settings-section"><div class="settings-label">配色</div><div class="settings-card">'+Object.entries(labels).map(([key,label])=>'<button type="button" class="settings-choice" data-palette-choice="'+key+'" aria-pressed="false"><span><span class="palette-swatch" data-swatch="'+key+'" aria-hidden="true"></span>'+label+'</span><span class="is-check" aria-hidden="true" hidden>✓</span></button>').join('')+'</div></section>';
    document.querySelectorAll('[data-palette-choice]').forEach(b=>b.addEventListener('click',()=>{
      document.getElementById('settingsScroll').querySelector('[role="status"]')?.remove();
      if(!window.V2Store.setPalette(b.dataset.paletteChoice)){const p=document.createElement('p');p.className='settings-footnote';p.setAttribute('role','status');p.textContent='配色未能保存，请重试。';document.getElementById('settingsScroll').append(p);}
    }));apply();
  }});
  function attach(frame){
    const doc=frame.contentDocument;if(!doc?.documentElement)return;
    const themeRow=[...doc.querySelectorAll('.setting-row')].find(r=>r.querySelector('.row-title')?.textContent==='深色模式');
    if(themeRow&&!doc.getElementById('paletteSettingsRow')){
      const row=themeRow.cloneNode(true);row.id='paletteSettingsRow';row.removeAttribute('data-toast');row.querySelector('.row-title').textContent='主题配色';
      row.querySelector('.row-icon').innerHTML='<svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="12" cy="12" r="8" stroke="currentColor" stroke-width="1.7"/><circle cx="9" cy="9" r="1.4" fill="currentColor"/><circle cx="15" cy="9" r="1.4" fill="currentColor"/><circle cx="9" cy="15" r="1.4" fill="currentColor"/></svg>';
      row.addEventListener('click',e=>{e.stopPropagation();window.V2App.openProfileEntry('主题配色',row);});themeRow.after(row);
    }
    apply();
  }
  document.querySelectorAll('iframe').forEach(frame=>{frame.addEventListener('load',()=>attach(frame));attach(frame);});
  new MutationObserver(nativeAppearance).observe(document.documentElement,{attributes:true,attributeFilter:['data-theme']});
  apply();
})();
