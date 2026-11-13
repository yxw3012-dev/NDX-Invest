/* Auxiliary initial inputs only. Native storage owns production values and all financial statistics. */
(() => {
  'use strict';
  const $=id=>document.getElementById(id),escape=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c]);
  let draft=null,editing=false,busy=false,requestId=null,recordId=null,error='';
  const validDate=value=>/^\d{4}-\d{2}-\d{2}$/.test(value||'')&&Number.isFinite(Date.parse(value+'T00:00:00Z'))&&new Date(value+'T00:00:00Z').toISOString().slice(0,10)===value;
  const today=()=>new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
  const importDate=record=>window.V2DateTime(record.importedAt,true);
  function decimal(value){if(!/^\d+(?:\.\d+)?$/.test(String(value||''))||String(value).length>128)return null;const [a,b='']=String(value).split('.');if(b.length>32)return null;return {n:BigInt(a+b),scale:b.length};}
  function sum(values){const numbers=values.map(decimal);if(numbers.some(n=>!n))return null;const scale=Math.max(2,...numbers.map(n=>n.scale));return {n:numbers.reduce((a,n)=>a+n.n*10n**BigInt(scale-n.scale),0n),scale};}
  function difference(record,entries){const total=sum(entries.map(e=>e.amount)),cost=decimal(record.initialHoldingCost);if(!total||!cost)return null;const scale=Math.max(total.scale,cost.scale,2);return {n:total.n*10n**BigInt(scale-total.scale)-cost.n*10n**BigInt(scale-cost.scale),scale};}
  function rounded(value){if(!value)return '--';const abs=value.n<0n?-value.n:value.n,div=10n**BigInt(value.scale-2);const cents=(abs+div/2n)/div;const integer=(cents/100n).toString().replace(/\B(?=(\d{3})+(?!\d))/g,',');return (value.n<0n?'-':value.n>0n?'+':'')+integer+'.'+(cents%100n).toString().padStart(2,'0');}
  function analyze(record,entries){
    const latest=[importDate(record),today()].sort()[0];
    for(let i=0;i<entries.length;i++){const e=entries[i],n=decimal(e.amount);if(!validDate(e.date)||e.date<'1900-01-01'||e.date>latest)return {ok:false,message:'第 '+(i+1)+' 条日期须有效且不晚于导入日期。'};if(!n||n.n<=0n)return {ok:false,message:'第 '+(i+1)+' 条金额须大于 0，最多保留 32 位小数。'};}
    const diff=difference(record,entries);const matched=diff&&((diff.n<0n?-diff.n:diff.n)<=10n**BigInt(diff.scale-2));
    return {ok:true,difference:diff,matched:!!matched};
  }
  function current(){const state=window.V2Store.getState(),id=state.transactionDetail;return state.initialPositions?.find(r=>r.id===id)||state.transactions.find(r=>r.id===id&&r.kind==='INITIAL_POSITION');}
  function reset(id){recordId=id;draft=null;editing=false;busy=false;requestId=null;error='';}
  function render(){
    const scroll=$('settingsScroll'),page=$('settingsPage'),record=current();
    if(!scroll||!page?.classList.contains('is-open')||$('settingsTitle')?.textContent!=='初始持仓导入'||!record){if(!busy)reset(null);return;}
    if(recordId!==record.id)reset(record.id);
    const startInput=$('holdingStartDate');if(startInput){startInput.min='1900-01-01';startInput.max=[importDate(record),today()].sort()[0];}
    let card=$('initialContributionsCard');
    if(!card){card=document.createElement('section');card.id='initialContributionsCard';card.className='settings-card initial-contributions';scroll.append(card);}
    const entries=editing?draft:(record.initialContributions||[]),check=analyze(record,entries);
    const title='<div class="initial-contribution-head"><strong>初始投入明细</strong><div>'+ (editing?'<button type="button" id="initialContributionsDone" '+(busy?'disabled':'')+'>'+(busy?'保存中…':'完成')+'</button>':'')+'<button type="button" id="initialContributionsEdit" aria-pressed="'+editing+'" '+(busy?'disabled':'')+'>编辑</button></div></div>';
    const rows=entries.map((entry,i)=>'<div class="initial-contribution-row'+(editing?' is-editing':'')+'" data-index="'+i+'">'+(editing?'<button type="button" data-contribution-add="'+i+'" aria-label="新增投入明细" '+(busy?'disabled':'')+'>+</button>':'')+(editing?'<input type="date" aria-label="第 '+(i+1)+' 条投入日期" max="'+escape([importDate(record),today()].sort()[0])+'" min="1900-01-01" data-contribution-date="'+i+'" value="'+escape(entry.date)+'" '+(busy?'disabled':'')+'><input type="text" inputmode="decimal" aria-label="第 '+(i+1)+' 条买入金额" data-contribution-amount="'+i+'" value="'+escape(entry.amount)+'" '+(busy?'disabled':'')+'>':'<span>'+escape(entry.date)+'</span><strong>'+escape(window.V2Display('¥'+entry.amount))+'</strong>')+(editing?'<button type="button" data-contribution-remove="'+i+'" aria-label="删除投入明细" '+(busy?'disabled':'')+'>−</button>':'')+'</div>').join('');
    card.innerHTML=title+'<div class="initial-contribution-rows">'+(rows||'<p class="initial-contribution-empty">未填写明细</p>')+'</div>'+(editing&&entries.length===0?'<button type="button" id="initialContributionsAddFirst" '+(busy?'disabled':'')+'>+ 新增条目</button>':'')+'<p id="initialContributionsDifference">与初始成本差 '+escape(!editing&&!entries.length?'--':rounded(check.difference))+' 元</p><p id="initialContributionsError" role="alert">'+escape(error||(!check.ok?check.message:entries.length&&!check.matched?'明细尚未补齐，保存后暂不用于年化。':''))+'</p>';
  }
  function initialEntry(record){return {date:record.holdingStartDate||importDate(record),amount:record.initialHoldingCost??''};}
  function updateFeedback(){const record=current();if(!record||!draft)return;const check=analyze(record,draft);$('initialContributionsDifference').textContent='与初始成本差 '+rounded(check.difference)+' 元';$('initialContributionsError').textContent=error||(!check.ok?check.message:draft.length&&!check.matched?'明细尚未补齐，保存后暂不用于年化。':'');}
  document.addEventListener('input',event=>{const el=event.target;if(!editing||busy||!el.closest?.('#initialContributionsCard'))return;const index=el.dataset.contributionDate??el.dataset.contributionAmount;if(index==null)return;draft[Number(index)][el.dataset.contributionDate!=null?'date':'amount']=el.value;error='';updateFeedback();});
  document.addEventListener('click',event=>{
    const button=event.target.closest?.('#initialContributionsCard button'),record=current();if(!button||!record||busy)return;
    if(button.id==='initialContributionsEdit'){if(!editing){draft=(record.initialContributions||[]).map(e=>({...e}));if(!draft.length)draft=[initialEntry(record)];editing=true;}render();return;}
    if(button.id==='initialContributionsAddFirst'){draft=[initialEntry(record)];render();return;}
    if(button.dataset.contributionAdd!=null){const index=Number(button.dataset.contributionAdd);if(draft.length>=10000){error='最多保存 10,000 条明细。';updateFeedback();return;}const previous=draft[index];const nextDate=validDate(previous.date)?new Date(Date.parse(previous.date+'T00:00:00Z')+86400000).toISOString().slice(0,10):previous.date;draft.splice(index+1,0,{date:nextDate,amount:previous.amount});error='';render();return;}
    if(button.dataset.contributionRemove!=null){draft.splice(Number(button.dataset.contributionRemove),1);error='';render();return;}
    if(button.id==='initialContributionsDone'){
      const check=analyze(record,draft);if(!check.ok){error=check.message;updateFeedback();return;}
      if(window.NDXYjbRead){requestId=window.crypto.randomUUID();busy=true;const result=window.V2Store.saveInitialContributions?.(record.id,draft.map(e=>({...e})),requestId);if(!result?.ok){busy=false;error=result?.message||'保存不可用';}render();}
      else {const state=window.V2Store.getState(),updated={...record,initialContributions:draft.map(e=>({...e}))};editing=false;draft=null;window.V2Store.replaceUiState({...state,initialPositions:(state.initialPositions||[]).some(r=>r.id===record.id)?state.initialPositions.map(r=>r.id===record.id?updated:r):[...(state.initialPositions||[]),updated],transactions:state.transactions.map(r=>r.id===record.id?{...r,...updated}:r)});render();}
    }
  });
  window.V2InitialContributions={
    analyze,holdingDate(record){const entries=record.initialContributions||[];if(!entries.length)return record.holdingStartDate||null;const check=analyze(record,entries);return check.ok&&check.matched?entries.map(e=>e.date).sort()[0]:null;},
    result(result){if(result.id!==recordId||result.requestId!==requestId)return;busy=false;requestId=null;if(result.ok){editing=false;draft=null;error='';}else error=result.message||'保存失败，请重试。';render();}
  };
  const style=document.createElement('style');style.textContent='.initial-contributions{padding:16px 12px;margin-top:16px;color:var(--sp-text);background:var(--sp-card)}.initial-contribution-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:0 4px 12px;font-size:14px}.initial-contribution-head div{display:flex;gap:8px}.initial-contribution-head button,#initialContributionsAddFirst{border:0;background:transparent;color:var(--sp-green);font:inherit;font-size:13px;min-height:44px;padding:0 6px;cursor:pointer}.initial-contribution-row{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);align-items:center;gap:8px;padding:6px 0;border-bottom:1px solid var(--sp-line);font-size:13px;min-height:44px}.initial-contribution-row.is-editing{grid-template-columns:44px minmax(0,1.2fr) minmax(0,1fr) 44px;gap:4px}.initial-contribution-row strong{text-align:right;font-variant-numeric:tabular-nums;font-weight:500;overflow-wrap:anywhere}.initial-contribution-row input{box-sizing:border-box;width:100%;min-width:0;min-height:44px;padding:6px 0;border:0;border-bottom:1px solid var(--sp-line);border-radius:0;background:transparent;color:var(--sp-text);font:inherit;font-size:12px;box-shadow:none;outline:none;font-variant-numeric:tabular-nums}.initial-contribution-row input:focus-visible{border-bottom-color:var(--sp-green)}.initial-contribution-row button{width:44px;min-height:44px;border:0;background:transparent;color:var(--sp-muted);font-size:22px;cursor:pointer}.initial-contribution-row input::-webkit-calendar-picker-indicator{width:12px;margin:0;padding:0;opacity:.5}#initialContributionsDifference,.initial-contribution-empty{margin:16px 4px 6px;color:var(--sp-muted);font-size:12px;line-height:1.5;font-variant-numeric:tabular-nums}#initialContributionsError{font-size:12px;color:var(--sp-muted);margin:8px 4px 0;line-height:1.5}#initialContributionsError:empty{display:none}.initial-contributions button:disabled{opacity:.5;cursor:default}';document.head.append(style);
  let scheduled=false;function schedule(){if(scheduled)return;scheduled=true;queueMicrotask(()=>{scheduled=false;render();});}
  new MutationObserver(schedule).observe($('settingsScroll'),{childList:true});
  new MutationObserver(schedule).observe($('settingsPage'),{attributes:true,attributeFilter:['class']});
  window.V2Store.subscribe(schedule);schedule();
})();
