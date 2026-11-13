/* Native data, single trades and plan management, injected before frozen pages boot.
   Automatic DCA/NAV/write operations remain native; the browser cannot dispatch them. */
(() => {
  'use strict';
  const base = window.V2Store;
  const send = action => window.NDXYjbRead?.post(action);
  const pending=new Map(),deleteFeedback=new Map();let creating=false,managedPlanId=null,feeLoadedPlan=null;
  const blocked = () => ({ok:false,message:'该操作尚未开放；请先核对本地持仓。'});
  const node=id=>document.getElementById?.(id);
  const amountCard=()=>document.querySelector?.('.buy-amount-card')||node('buyAmount')?.closest('.buy-card');
  const isCreate=(prefix,body)=>prefix==='buy-create:'||prefix==='sell-create:'||(prefix==='plan-action:'&&body.action==='create');
  let recoveryTimer;
  const checkRecovery=()=>{if(recoveryTimer)window.clearTimeout?.(recoveryTimer);recoveryTimer=window.setTimeout?.(()=>{recoveryTimer=null;if(creating){send('recover-create');checkRecovery();}},15000);};
  const request=(prefix,body)=>{
    if(!window.NDXYjbRead||!window.crypto?.randomUUID)return {ok:false,message:'原生交易服务不可用。'};
    let requestId;
    if(isCreate(prefix,body)){
      if(!window.NDXYjbRead.prepareCreate)return {ok:false,message:'提交恢复服务不可用，请更新 App。'};
      let intent;try{intent=JSON.parse(window.NDXYjbRead.prepareCreate(JSON.stringify({prefix,body})));}catch{return {ok:false,message:'请求暂无法安全保存，请稍后重试。'};}
      if(!intent.requestId)return {ok:false,message:intent.message||'前一笔提交仍在核对，请稍后重试。'};
      requestId=intent.requestId;pending.set(requestId,{prefix,body});
      if(intent.result){feedback(intent.result);return {ok:false,message:intent.result.message};}
      checkRecovery();
    }else requestId=window.crypto.randomUUID();
    pending.set(requestId,{prefix,body});
    send(prefix+JSON.stringify({...body,requestId}));return {ok:false,message:'正在处理，请稍候…'};
  };
  const feedback=result=>{
    const sent=pending.get(result.requestId);if(!sent)return;
    if(isCreate(sent.prefix,sent.body)){
      if(result.creationUncertain||window.NDXYjbRead.acknowledgeCreate?.(result.requestId)!==true){creating=true;checkRecovery();if(node('amountTip'))node('amountTip').textContent='提交结果正在核对，请勿重复提交。';if(node('buySubmit'))node('buySubmit').disabled=true;return;}
      if(recoveryTimer)window.clearTimeout?.(recoveryTimer);recoveryTimer=null;
    }
    pending.delete(result.requestId);
    if(sent.prefix==='record-delete:'){deleteFeedback.set(sent.body.id,result.message||'撤销未完成，请重新核对');queueMicrotask(()=>base.replaceUiState({...base.getState()}));}
    if(sent.prefix==='buy-create:'||sent.prefix==='sell-create:'||(sent.prefix==='plan-action:'&&sent.body.action==='create')){
      creating=false;const button=node('buySubmit');if(button)button.disabled=false;
      const amount=node('buyAmount');
      const sameFlow=sent.prefix==='plan-action:'?node('recurringPanel')?.classList.contains('is-open'):node('buyPage')?.classList.contains('is-sell')===(sent.prefix==='sell-create:');
      if(result.ok&&sameFlow&&amount?.value.trim()===(sent.body.amountInput??sent.body.sharesInput) ){amount.value='';amount.dispatchEvent(new Event('input',{bubbles:true}));}
      if(sameFlow){amount?.setAttribute('aria-invalid',String(!result.ok));amountCard()?.classList.toggle('has-error',!result.ok);}
      if(node('amountTip'))node('amountTip').textContent=result.message;
    }
    if(sent.prefix==='plan-action:'){
      const samePlan=sent.body.planId===managedPlanId&&node('buyPage')?.classList.contains('is-open')&&node('buyTitle')?.textContent==='定投计划管理';
      const feedback=node('inlinePlanFeedback');if(samePlan&&feedback){feedback.hidden=false;delete feedback.dataset.autoStatus;feedback.textContent=result.message;}
      if(samePlan&&sent.body.action==='update')node('buyAmount')?.setAttribute('aria-invalid',String(!result.ok));
      if(samePlan&&result.ok&&sent.body.action==='delete')node('buyBack')?.click();
      updatePlanUi();
    }
    if(node('gapEditError'))node('gapEditError').textContent=result.message;
    const toast=node('buyToast');if(toast){toast.textContent=result.message;toast.classList.add('show');setTimeout(()=>toast.classList.remove('show'),2200);}
  };
  window.YjbReadApply = payload => {
    if(payload.localError){const field=node('initialContributionsError')||node('initialPositionDateError')||node('amountTip');if(field)field.textContent=payload.localError;return;}
    const recovery=payload.creationRecovery;
    if(recovery){pending.set(recovery.requestId,{prefix:recovery.prefix,body:recovery.body});creating=true;if(node('buySubmit'))node('buySubmit').disabled=true;
      if(recovery.result)feedback(recovery.result);else checkRecovery();}
    const state = base.getState(), data = payload.data;
    if(!data){if(payload.buyResult){feedback(payload.buyResult);if(payload.buyResult.undoDeleted)node('settingsBack')?.click();}if(payload.contributionResult)window.V2InitialContributions?.result(payload.contributionResult);return;}
    const lastSyncAt = data.lastSyncAt || '尚未同步';
    const errorMessage = payload.errorMessage || '';
    const plans=payload.plans||state.plans;
    const planFor=id=>plans.find(p=>p.fundId===id&&p.enabled)||plans.find(p=>p.fundId===id);
    const cycle=payload.refreshCycle;
    const holdingsState = {status:cycle?.status==='RUNNING'?(data.holdings.length?'REFRESHING':'INITIAL_LOADING'):payload.holdingsStatus,isCached:payload.holdingsStatus==='OFFLINE_CACHE',lastSyncAt,errorMessage,
      refreshCycle:cycle?.status||null,refreshError:cycle?.errorMessage||''};
    const imports=payload.initialPositions||state.initialPositions||[];
    const records=imports.map(r=>({...r,type:'初始持仓导入',status:'已导入',dateKey:window.V2DateTime(r.importedAt,true),dateLabel:window.V2DateTime(r.importedAt,true),weekday:'',time:r.importedAt,primaryText:(r.initialShares??'--')+' 份',secondaryText:'本地初始持仓',note:'建仓时录入的份额与成本'}));
    const recordTime=t=>{const value=Date.parse(t.time||t.createdAt||t.importedAt||'');return Number.isFinite(value)?value:0;};
    const transactions=[...records,...(payload.transactions||state.transactions.filter(t=>['BUY','SELL'].includes(t.kind))).map(t=>({...t,status:t.writeReviewRequired&&t.transactionStatus==='SYNCING'?'写入待核对':typeof v2TransactionLabels==='undefined'?t.transactionStatus:v2TransactionLabels[t.transactionStatus]}))]
      .sort((a,b)=>String(b.dateKey||'').localeCompare(String(a.dateKey||''))||recordTime(b)-recordTime(a)||String(a.id||'').localeCompare(String(b.id||'')));
    base.replaceUiState({...state,status:'ready',statusText:'',
      plans,fundCatalog:(data.fundCatalog||data.holdings).map(f=>({...f,autoInvest:!!planFor(f.id)?.enabled})),holdings:data.holdings.map(f=>({...f,autoInvest:!!planFor(f.id)?.enabled})),
      details:data.details.map(f=>{const fund=data.holdings.find(h=>h.id===f.id),r=imports.find(r=>r.fundId===fund?.fundId),stats=payload.localStatistics?.funds?.[f.id];return {...f,autoInvest:v2PlanStatus(planFor(f.id)),holdingStartDate:r?.holdingStartDate||null,holdingDays:r?.holdingStartDate?Math.floor((Date.now()-Date.parse(r.holdingStartDate+'T00:00:00+08:00'))/86400000):null,annualized:'--',...(stats||{}),statisticsMessage:stats?.message||''};}),overview:payload.localStatistics?.overview?{...data.overview,metrics:[...(data.overview.metrics||[]).slice(0,3),payload.localStatistics.overview.annualized||'--']}:data.overview,
      localStatistics:payload.localStatistics||null,
      initialPositions:imports,transactions,
      yjbAccount:{status:payload.accountStatus,displayName:data.displayName||'本地持仓',maskedId:data.maskedId||'--',lastSyncAt,errorMessage},
      holdingsState,yjbSyncState:{status:holdingsState.status,lastSyncAt}
    });
    if(payload.buyResult){feedback(payload.buyResult);if(payload.buyResult.undoDeleted)node('settingsBack')?.click();}
    if(payload.contributionResult)window.V2InitialContributions?.result(payload.contributionResult);
    updatePlanUi();
  };
  window.V2Store = Object.freeze({...base,
    startLogin() { if(!window.NDX_LOCAL_PORTFOLIO)send('connect');return false; }, completeLogin(){return false;},
    startHoldingSync(){
      const state=base.getState();
      if(['INITIAL_LOADING','REFRESHING'].includes(state.holdingsState.status))return false;
      base.replaceUiState({...state,holdingsState:{...state.holdingsState,status:state.holdings.length?'REFRESHING':'INITIAL_LOADING',refreshCycle:'RUNNING',refreshError:''}});
      send('refresh');return false;
    },completeHoldingSync(){return false;},
    setAccountStatus(status){if(!window.NDX_LOCAL_PORTFOLIO&&status==='NOT_CONNECTED')send('disconnect');return false;},
    setHoldingsStatus(){return false;},reset(){return false;},
    submitTrade(draft){
      const state=base.getState(),funds=draft.type==='卖出'?state.holdings.filter(f=>!f.isPlaceholder):(state.fundCatalog||state.holdings);
      if(!['买入','卖出'].includes(draft.type)||!funds.some(f=>f.id===draft.fundId))return blocked();
      if(creating)return {ok:false,message:'正在保存，请勿重复提交。'};
      const selling=draft.type==='卖出';
      if(selling&&(!/^\d+(?:\.\d+)?$/.test(draft.amountInput||'')||!/^\d+(?:\.\d+)?$/.test(draft.sellRateInput||'')))return {ok:false,message:'请填写卖出份额和费率。'};
      const result=request(selling?'sell-create:':'buy-create:',{fundId:draft.fundId,...(selling?{sharesInput:draft.amountInput,sellRateInput:draft.sellRateInput}:{amountInput:draft.amountInput,buyRateInput:draft.buyRateInput}),date:draft.date,timePeriod:draft.timePeriod,note:draft.note||''});
      if(result.message==='正在处理，请稍候…'){creating=true;if(node('buySubmit'))node('buySubmit').disabled=true;
        // The frozen Mock shell marks any ok:false result as an error; a pending native save is a status.
        if(typeof queueMicrotask==='function')queueMicrotask(()=>{if(creating){node('buyAmount')?.setAttribute('aria-invalid','false');amountCard()?.classList.remove('has-error');}});
      }return result;
    },
    transactionAction(id,action,draft={}){
      const t=base.getState().transactions.find(t=>t.id===id);
      if(!['BUY','SELL'].includes(t?.kind)||!['check-nav','sync','cancel','edit','recover-write'].includes(action))return blocked();
      if([...pending.values()].some(r=>r.body.id===id))return {ok:false,message:'正在处理，请勿重复操作。'};
      return request(t.kind==='SELL'?'sell-action:':'buy-action:',{id,action,draft});
    },
    savePlan(draft){
      if(creating)return {ok:false,message:'正在保存，请勿重复提交。'};
      if(!(base.getState().fundCatalog||base.getState().holdings).some(f=>f.id===draft.fundId&&!f.isPlaceholder))return {ok:false,message:'请先通过普通买入建立持仓，再创建定投计划。'};
      const result=planRequest('create',draft.fundId,null,{...draft,buyRateInput:draft.buyRateInput??node('buyFeeRate')?.value});
      if(result.message==='正在处理，请稍候…'){creating=true;if(node('buySubmit'))node('buySubmit').disabled=true;clearPendingPlanError();}
      return result;
    },
    updatePlan(fundId,draft,planId){return planRequest('update',fundId,planId,{...draft,buyRateInput:draft.buyRateInput??node('buyFeeRate')?.value??base.getState().plans.find(p=>p.id===planId)?.buyRateInput});},
    setPlanEnabled(fundId,enabled,planId){return planRequest('enabled',fundId,planId,{enabled});},
    deletePlan(fundId,planId){return planRequest('delete',fundId,planId);},
    executePlan(){return {ok:false,message:'正式净值公布后自动记账并同步，无需手动执行。'};}
    ,editHoldingStartDate(id,date){const r=(base.getState().initialPositions||[]).find(r=>r.id===id);if(!r)return {ok:false,message:'初始持仓记录不存在'};const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date()),importDay=window.V2DateTime?window.V2DateTime(r.importedAt,true):new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(r.importedAt));if(date&&(!/^\d{4}-\d{2}-\d{2}$/.test(date)||!Number.isFinite(Date.parse(date+'T00:00:00Z'))||new Date(date+'T00:00:00Z').toISOString().slice(0,10)!==date||date<'1900-01-01'||date>[today,importDay].sort()[0]))return {ok:false,message:'请输入有效且不晚于导入日期的日期'};send('holding-start:'+JSON.stringify({id,date}));return {ok:true};}
    ,saveInitialContributions(id,entries,requestId){const action='initial-contributions:'+JSON.stringify({id,entries,requestId});if(action.length>1048576)return {ok:false,message:'明细内容过长，请减少条目。'};send(action);return {ok:true,pending:true};}
  });
  function clearPendingPlanError(){if(typeof queueMicrotask==='function')queueMicrotask(()=>{node('buyAmount')?.setAttribute('aria-invalid','false');amountCard()?.classList.remove('has-error');});}
  function planRequest(action,fundId,planId,body={}){
    const matches=base.getState().plans.filter(p=>p.fundId===fundId&&(!planId||p.id===planId));
    const p=action==='create'?null:matches.length===1?matches[0]:null;
    if(action!=='create'&&!p)return {ok:false,message:'请选择当前基金的一条计划。'};
    if([...pending.values()].some(r=>r.prefix==='plan-action:'&&(action==='create'?r.body.action==='create':r.body.planId===p.id)))return {ok:false,message:'正在处理计划，请勿重复操作。'};
    if(['create','update'].includes(action)){
      const check=v2ValidatePlan(body);if(!check.ok)return check;
      if(!/^\d+(?:\.\d+)?$/.test(body.buyRateInput||'')||Number(body.buyRateInput)>=100)return {ok:false,message:'请填写大于等于 0、小于 100% 的买入费率。'};
    }
    const result=request('plan-action:',{...body,action,fundId,...(p?{planId:p.id,revision:p.revision}:{})});
    if(result.message==='正在处理，请稍候…')clearPendingPlanError();
    return result;
  }
  function updateCatchupUi(plan,managing){
    let panel=node('nativeCatchupReview');
    const review=managing&&plan?.catchupReview;
    if(!review){panel?.remove();return;}
    if(!panel){
      panel=document.createElement('section');panel.id='nativeCatchupReview';panel.className='buy-card';panel.setAttribute('aria-label','定投积压确认');
      panel.style.cssText='margin-top:12px;padding:16px;border-radius:20px;color:var(--text-primary,#17212c);background:var(--surface-primary,#fff);';
      const feedback=node('inlinePlanFeedback');(feedback?.parentElement||node('recurringPanel'))?.append(panel);
    }
    const busy=[...pending.values()].some(r=>r.prefix==='plan-action:'&&r.body.planId===plan.id);
    const key=JSON.stringify([review,plan.enabled,busy]);if(panel.dataset.contentKey===key)return;panel.dataset.contentKey=key;
    panel.replaceChildren();
    const text=(tag,value,style)=>{const el=document.createElement(tag);el.textContent=value;if(style)el.style.cssText=style;panel.append(el);return el;};
    text('strong','积压期次待确认','font-size:14px;');
    text('p',review.fromDate.replaceAll('-','.')+' — '+review.toDate.replaceAll('-','.')+' · 共 '+review.count+' 期','font-size:12px;color:var(--text-secondary,#89929b);margin:10px 0 6px;');
    text('strong',window.V2Display?.('¥'+review.totalAmount)||('¥'+review.totalAmount),'font-size:22px;font-variant-numeric:tabular-nums;');
    text('p','请核对这些期次是否实际投入，再决定是否补记并同步。','font-size:12px;line-height:1.5;color:var(--text-secondary,#89929b);');
    const list=text('div','');list.style.cssText='max-height:160px;overflow:auto;';
    for(const slot of review.slots||[]){const row=document.createElement('p');row.style.cssText='font-size:12px;color:var(--text-secondary,#89929b);margin:8px 0;';row.textContent=slot.date.replaceAll('-','.')+' · '+window.V2Display('¥'+slot.amount);list.append(row);}
    const actions=text('div','');actions.style.cssText='display:flex;gap:12px;margin-top:10px;';
    for(const [action,label] of [['catchup-skip','跳过本批次'],['catchup-approve','已实际投入，补执行']]){
      const b=document.createElement('button');b.type='button';b.className='settings-action';b.textContent=label;b.dataset.catchupAction=action;b.disabled=busy||!plan.enabled;
      b.style.cssText='flex:1;min-height:44px;padding:8px;color:var(--profit,#328b71);font-size:13px;';
      b.addEventListener('click',()=>{const r=planRequest(action,plan.fundId,plan.id,{reviewId:review.id});const feedback=node('inlinePlanFeedback');if(feedback){feedback.hidden=false;feedback.textContent=r.message;}updatePlanUi();});actions.append(b);
    }
  }
  function updatePlanUi(){
    const page=node('buyPage'),feedback=node('inlinePlanFeedback'),execute=node('nativePlanExecute');
    const managing=page?.classList.contains('is-open')&&node('buyTitle')?.textContent==='定投计划管理';
    // A native click can run a microtask between capture and the frozen open-page handler.
    // Retain the selected plan across that brief closed state; controls remain hidden.
    if(!managing){updateCatchupUi(null,false);if(execute){execute.hidden=true;execute.style.display='none';}if(!page?.classList.contains('is-open'))feeLoadedPlan=null;return;}
    const p=base.getState().plans.find(p=>p.id===managedPlanId);updateCatchupUi(p,managing);if(!p)return;
    if(feeLoadedPlan!==p.id){if(node('buyFeeRate'))node('buyFeeRate').value=p.buyRateInput;feeLoadedPlan=p.id;node('buyFeeRate')?.dispatchEvent(new Event('input',{bubbles:true}));}
    const toggle=node('planToggle');if(toggle){toggle.setAttribute('aria-pressed',String(p.enabled));toggle.classList.toggle('off',!p.enabled);}
    const selects=node('cycleRow')?.querySelectorAll('select');
    const nextText=selects?.length===2?v2PlanNextDate({...p,frequency:selects[0].value,weekday:Number(selects[1].value),monthDay:Number(selects[1].value)}):p.nextDateText;
    const value=node('firstDateValue');if(value&&value.textContent!==nextText)value.textContent=nextText;
    if(feedback&&(feedback.textContent==='仅保存 Mock 计划，不执行交易。'||feedback.dataset.autoStatus==='true'||p.automationProblem)){
      const problems={WRITE_PENDING:'同步结果正在核对，不会重复提交持仓。',CATCHUP_REVIEW:'积压期次需确认，请核对下方日期和金额。',RATE_LIMITED:'净值请求频繁，稍后再检查。',AUTH_EXPIRED:'暂时无法读取数据。',EXTERNAL_CHANGED:'持仓发生变化，自动记账已暂停，请核对交易详情。',PENDING_TRANSACTION:'该基金存在未完成交易，处理后继续自动定投。',NAV_OUTSIDE_WINDOW:'未处理日期超出可读取的净值范围，已停止补记，请核对。',WRITE_UNVERIFIED:'写入证据暂不可用，未发送写入。',NETWORK:'暂时无法读取数据，稍后自动重试。',VERIFY_FAILED:'同步结果未验证，请先核对交易详情。'};
      feedback.dataset.autoStatus='true';feedback.textContent=p.automationProblem?(problems[p.automationProblem]||'自动定投暂未完成，请核对交易详情。'):'正式净值公布后自动记账并更新本地持仓。休市日期不生成记录；记录可能延迟出现。';
    }
    if(execute){execute.hidden=true;execute.style.display='none';}
  }
  const state=base.getState();
  base.replaceUiState({...state,status:'ready',statusText:'',fundCatalog:[],holdings:[],details:[],plans:[],transactions:[],transactionDetail:null,
    overview:{amount:'--',metrics:['--','--','--','--']},
    yjbAccount:{status:'NOT_CONNECTED',displayName:'',maskedId:'',lastSyncAt:'尚未同步',errorMessage:''},
    yjbSyncState:{status:'NOT_CONNECTED',lastSyncAt:'尚未同步'},
    holdingsState:{status:'NOT_CONNECTED',isCached:false,lastSyncAt:'尚未同步',errorMessage:''}
  });
  // Only diagnostic Mock controls and obsolete account-source copy change in Production.
  const style=document.createElement('style');style.textContent='.v2-gap-demo{display:none!important}';document.head.append(style);
  document.addEventListener('DOMContentLoaded',()=>{
    const target=document.getElementById('settingsScroll');
    const tip=document.getElementById('amountTip');
    // Existing wheels use this projection. Native schedules remain the authoritative stored dates.
    v2PlanNextDate=plan=>{
      if(!plan.enabled)return '已暂停';
      const saved=base.getState().plans.find(p=>p.id===plan.id);
      if(saved&&saved.frequency===plan.frequency&&(plan.frequency==='monthly'?saved.monthDay===Number(plan.monthDay):['weekly','biweekly'].includes(plan.frequency)?saved.weekday===Number(plan.weekday):true))return saved.nextDateText;
      const day=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
      const d=new Date(day+'T00:00:00Z');
      if(plan.frequency==='monthly'){
        const y=d.getUTCFullYear(),m=d.getUTCMonth(),wanted=Number(plan.monthDay);
        let next=new Date(Date.UTC(y,m,Math.min(wanted,new Date(Date.UTC(y,m+1,0)).getUTCDate())));
        if(next<=d)next=new Date(Date.UTC(y,m+1,Math.min(wanted,new Date(Date.UTC(y,m+2,0)).getUTCDate())));
        return next.toISOString().slice(0,10);
      }
      d.setUTCDate(d.getUTCDate()+(plan.frequency==='daily'?1:(Number(plan.weekday)%7-d.getUTCDay()+7)%7||7));
      return d.toISOString().slice(0,10);
    };
    document.addEventListener('click',e=>{
      const row=e.target.closest?.('[data-plan-id]');if(row){managedPlanId=row.dataset.planId;feeLoadedPlan=null;}
      if(e.target.closest?.('#inlinePlanDelete')&&managedPlanId){e.preventDefault();e.stopImmediatePropagation();const p=base.getState().plans.find(p=>p.id===managedPlanId);if(p){const r=window.V2Store.deletePlan(p.fundId,p.id);if(node('inlinePlanFeedback'))node('inlinePlanFeedback').textContent=r.message;}return;}
      if(row||e.target.closest?.('#buyBack,#buyRecurringConfirm,#planToggle'))queueMicrotask(updatePlanUi);
    },true);
    if(node('buyPage'))new MutationObserver(updatePlanUi).observe(node('buyPage'),{attributes:true,attributeFilter:['class']});
    if(node('buyTitle'))new MutationObserver(updatePlanUi).observe(node('buyTitle'),{childList:true});
    if(node('firstDateValue'))new MutationObserver(updatePlanUi).observe(node('firstDateValue'),{childList:true});
    const updateCopy=()=>{
      const s=base.getState(),t=s.transactions.find(t=>t.id===s.transactionDetail);
      if(['BUY','SELL'].includes(t?.kind)&&node('settingsTitle')?.textContent==='交易详情'){
        if(!target.querySelector('[data-native-delete]')){const b=document.createElement('button');b.type='button';b.className='settings-action';b.style.color='var(--loss,#b45461)';b.dataset.nativeDelete='true';b.textContent='撤销并删除记录';b.addEventListener('click',()=>{if([...pending.values()].some(r=>r.prefix==='record-delete:'&&r.body.id===t.id))return;deleteFeedback.delete(t.id);b.disabled=true;const r=request('record-delete:',{id:t.id});if(r.message&&r.message!=='正在处理，请稍候…')deleteFeedback.set(t.id,r.message);queueMicrotask(updateCopy);});target.append(b);}
        {const b=target.querySelector('[data-native-delete]'),busy=[...pending.values()].some(r=>r.prefix==='record-delete:'&&r.body.id===t.id),label=busy?'正在核对，请稍候…':'撤销并删除记录';if(b){b.disabled=busy;if(b.textContent!==label)b.textContent=label;}const message=deleteFeedback.get(t.id)||'';let status=target.querySelector('[data-native-delete-feedback]');if(!status&&message){status=document.createElement('p');status.dataset.nativeDeleteFeedback='true';status.className='v2-gap-error';status.setAttribute('role','status');b.after(status);}if(status&&status.textContent!==message)status.textContent=message;}
        // Existing detail surface: only bind real actions, never run the Mock rebase/completion.
        const rebase=target.querySelector('[data-gap-action=rebase]');if(rebase)rebase.remove();
        const cancel=target.querySelector('[data-gap-action=cancel]');if(cancel)cancel.disabled=!!t.postMayHaveBeenSent;
        if(t.postMayHaveBeenSent&&t.transactionStatus!=='SYNCED'&&!target.querySelector('[data-native-verify]')){
          const button=document.createElement('button');button.type='button';button.className='settings-action';button.dataset.nativeVerify='true';button.textContent='重新核对同步结果';
          button.addEventListener('click',()=>window.V2Store.transactionAction(t.id,'sync'));target.append(button);
        }
        if(t.writeReviewRequired&&t.postMayHaveBeenSent&&t.transactionStatus!=='SYNCED'){
          const status=target.querySelector('.settings-status');if(status){if(status.getAttribute('aria-busy')!=='false')status.setAttribute('aria-busy','false');const title=status.querySelector('strong'),label=t.transactionStatus==='EXTERNAL_CHANGED'?'持仓冲突':'写入结果待核对';if(title&&title.textContent!==label)title.textContent=label;}
          if(!target.querySelector('[data-native-recovery]')){const b=document.createElement('button');b.type='button';b.className='settings-action';b.dataset.nativeRecovery='true';b.textContent='核对并恢复原交易';b.addEventListener('click',()=>window.V2Store.transactionAction(t.id,'recover-write'));target.append(b);}
        }
        if(t.writeDiagnosticText&&!target.querySelector('[data-native-write-diagnostic]')){const p=document.createElement('p');p.className='settings-footnote';p.dataset.nativeWriteDiagnostic='true';p.textContent=t.writeDiagnosticText;target.append(p);}
        if(t.transactionStatus==='WAITING_NAV'&&!target.querySelector('[data-native-nav]')){
          const button=document.createElement('button');button.type='button';button.className='settings-action';button.dataset.nativeNav='true';button.textContent='检查目标日期正式净值';
          button.addEventListener('click',()=>window.V2Store.transactionAction(t.id,'check-nav'));target.append(button);
        }
        if(t.syncError&&!target.querySelector('[data-native-problem]')){const p=document.createElement('p');p.className=t.transactionStatus==='SYNCING'?'settings-footnote':'v2-gap-error';p.dataset.nativeProblem='true';p.textContent=t.syncError;target.append(p);}
        if(t.kind==='SELL'&&!target.querySelector('[data-native-sell-note]')){const p=document.createElement('p');p.className='settings-footnote';p.dataset.nativeSellNote='true';p.textContent='单位成本保持不变。卖出金额按正式净值和输入费率计算，不代表实际到账；记账会更新本地剩余持仓。';target.append(p);}
        const fee=node('gapFee');if(fee){fee.readOnly=true;fee.value='正式净值确认后自动计算';}
        const save=target.querySelector('#v2TransactionEdit button');if(save&&save.textContent!=='保存修改')save.textContent='保存修改';
        for(const p of target.querySelectorAll('.settings-footnote'))if(p.textContent.includes('仅演示状态切换'))p.textContent='持仓已变化，请先核对本地持仓；本阶段不会自动合并冲突。';
      }
      for(const node of target.querySelectorAll('.settings-footnote')) {
        if(node.textContent==='Mock 演示账户，不请求或保存真实账号凭据。')node.textContent='持仓和交易记录保存在本机。';
        if(node.textContent.includes('当前基金的 Mock 计划'))node.textContent='点击具体计划查看或修改。正式净值公布后自动生成买入记录并更新持仓；休市日期不生成记录。';
      }
      for(const row of target.querySelectorAll('.settings-row')) {
        if(row.querySelector('.settings-row-title')?.textContent==='数据来源') {
          const label=row.querySelector('.settings-row-end'),s=base.getState();
          const text=s.holdingsState.isCached?'缓存净值':s.holdingsState.lastSyncAt==='尚未同步'?'尚未同步':'持仓最近一次刷新';
          if(label.textContent!==text)label.textContent=text;
        }
      }
    };
    if(target){new MutationObserver(updateCopy).observe(target,{childList:true,subtree:true});updateCopy();}
    if(tip)new MutationObserver(()=>{
      if(tip.textContent.includes('份 · Mock 展示值'))tip.textContent=tip.textContent.replace('份 · Mock 展示值','份 · 本地持仓');
      if(tip.textContent==='Mock 输入演示 · 保存后不计算份额或收益')tip.textContent='';
    }).observe(tip,{childList:true,subtree:true});
    // Presentation-only fee preview; exact decimal strings, never an input to native calculations.
    const fee=node('buyFeeAmount');const updateFee=()=>{
      const a=node('buyAmount')?.value.trim()||'0',r=node('buyFeeRate')?.value.trim()||'0';
      if(!/^\d+(?:\.\d+)?$/.test(a)||!/^\d+(?:\.\d+)?$/.test(r)||a.length>100||r.length>100)return;
      const ap=a.split('.'),rp=r.split('.'),scale=(ap[1]?.length||0)+(rp[1]?.length||0);
      const numerator=BigInt(ap.join(''))*BigInt(rp.join('')),denominator=10n**BigInt(scale);
      const cents=(numerator+denominator/2n)/denominator,formatted=(cents/100n)+'.'+String(cents%100n).padStart(2,'0')+'元';
      if(fee&&fee.textContent!==formatted)fee.textContent=formatted;
    };
    if(fee)new MutationObserver(updateFee).observe(fee,{childList:true,subtree:true});
    for(const id of ['buyAmount','buyFeeRate'])node(id)?.addEventListener('input',updateFee);
  });
})();

/* Fund addition reuses the current settings surface and the App's authenticated session. */
(() => {
  'use strict';
  const $=id=>document.getElementById(id),send=(action,body)=>window.NDXYjbRead?.post(action+':'+JSON.stringify(body));
  let pending=null,selection=null,accounts=[],rows=[],intents=[],settingsObserver;
  const active=()=>$('settingsPage')?.classList.contains('is-open')&&$('settingsTitle')?.textContent==='添加基金';
  const message=text=>{if($('fundAddMessage'))$('fundAddMessage').textContent=text;};
  const request=(action,body)=>{if(pending)return;const requestId=crypto.randomUUID();pending={requestId,action};send(action,{...body,requestId});for(const b of $('fundAddForm')?.querySelectorAll('button')||[])b.disabled=true;message(action==='fund-search'?'正在搜索…':'正在核对，请勿重复提交…');};
  const prior=window.YjbReadApply;
  window.YjbReadApply=payload=>{
    prior(payload);if(payload.localFundIntents)intents=payload.localFundIntents;
    const r=payload.fundAddResult;if(!r||r.requestId!==pending?.requestId)return;
    const action=pending.action;pending=null;
    if(action==='fund-remove'){
      const doc=$('framePortfolio')?.contentDocument;if(r.ok){doc?.getElementById('hsBack')?.click();doc?.getElementById('level3Back')?.click();}else {let error=doc?.getElementById('fundRemoveError');if(!error&&doc){error=doc.createElement('p');error.id='fundRemoveError';error.className='hs-note';doc.getElementById('hsScroll')?.append(error);}if(error)error.textContent=r.message||'未能移除，请重试。';}return;
    }
    if(!active())return;
    for(const b of $('fundAddForm')?.querySelectorAll('button')||[])b.disabled=false;
    if(!r.ok){message(r.message||'操作未完成，请重试。');return;}
    if(action==='fund-search'){rows=r.rows||[];accounts=r.accounts||[];selection=null;renderResults();message(rows.length?'':'未找到相关基金');}
    else{$('settingsBack').click();}
  };
  function renderResults(){
    const list=$('fundSearchResults');list.replaceChildren();$('fundInitialFields').hidden=true;$('fundSearchReselect').hidden=true;
    for(const f of rows){
      const button=document.createElement('button');button.type='button';button.className='settings-choice';button.dataset.resultFund=f.fundId;
      const name=document.createElement('span');name.textContent=f.name;const code=document.createElement('small');code.textContent=f.code;button.append(name,code);
      button.addEventListener('click',()=>{
        selection=f;for(const b of list.children){b.setAttribute('aria-pressed',String(b===button));b.hidden=b!==button;}
        $('fundSearchReselect').hidden=false;$('fundSearchKeyword').blur();
        $('fundSelectedName').textContent=f.name;$('fundInitialFields').hidden=false;
        const select=$('fundAddAccount');select.replaceChildren();for(const a of accounts){const option=document.createElement('option');option.value=a.id;option.textContent=a.name;select.append(option);}select.closest('label').hidden=accounts.length<=1;
        const update=()=>{
          const id='yjb:'+select.value+':'+f.fundId;
          const existing=(V2Store.getState().fundCatalog||V2Store.getState().holdings).some(x=>x.id===id);
          $('fundAddSubmit').disabled=existing||!accounts.length;
          message(existing?'该基金已添加，请返回持仓查看。':accounts.length?'':'请先刷新本地持仓。');
          const intent=intents.find(x=>x.id===id&&x.kind==='ADDING');$('fundInitialShares').value=intent?.shares||'';$('fundInitialUnitCost').value=intent?.unitCost||'';updateTotal();
        };select.onchange=update;update();
        requestAnimationFrame(()=>$('fundInitialFields')?.scrollIntoView({block:'nearest',behavior:'smooth'}));
      });list.append(button);
    }
  }
  function product(a,b){
    if(!/^\d+(?:\.\d+)?$/.test(a)||!/^\d+(?:\.\d+)?$/.test(b)||a.length>48||b.length>48)return '--';
    const scale=(a.split('.')[1]?.length||0)+(b.split('.')[1]?.length||0),n=BigInt(a.replace('.',''))*BigInt(b.replace('.',''));let text=n.toString().padStart(scale+1,'0');if(scale)text=text.slice(0,-scale)+'.'+text.slice(-scale);return window.V2Display('¥'+text);
  }
  function updateTotal(){$('fundInitialTotal').textContent=product($('fundInitialShares').value.trim(),$('fundInitialUnitCost').value.trim());}
  function open(trigger){
    if(pending){message('上一笔请求仍在处理中，请勿重复提交。');return;}
    V2App.openProfileEntry('添加基金',trigger);selection=null;rows=[];accounts=[];
    $('settingsScroll').innerHTML='<form id="fundAddForm"><section class="settings-section"><div class="settings-card v2-gap-fields"><label for="fundSearchKeyword">基金名称或代码</label><div class="fund-search-line"><input id="fundSearchKeyword" maxlength="64" autocomplete="off"><button type="button" id="fundSearchSubmit" class="settings-action">搜索</button></div></div></section><div id="fundSearchResults" class="settings-card"></div><button type="button" id="fundSearchReselect" class="settings-action" hidden>重新选择基金</button><section id="fundInitialFields" class="settings-section" hidden><div class="settings-card v2-gap-fields"><strong id="fundSelectedName"></strong><label>本地持仓<select id="fundAddAccount"></select></label><label for="fundInitialShares">初始份额</label><input id="fundInitialShares" inputmode="decimal" autocomplete="off"><label for="fundInitialUnitCost">单位成本</label><input id="fundInitialUnitCost" inputmode="decimal" autocomplete="off"><div class="fund-add-total"><span>初始总成本</span><strong id="fundInitialTotal">--</strong></div><button id="fundAddSubmit" type="submit" class="settings-action">确认添加</button></div></section><p id="fundAddMessage" class="v2-gap-error" role="status" aria-live="polite"></p></form>';
    $('fundSearchReselect').onclick=()=>{selection=null;renderResults();};
    $('fundSearchSubmit').onclick=()=>{const keyword=$('fundSearchKeyword').value.trim();if(!keyword||keyword.length>64){message('请输入基金名称或代码。');return;}request('fund-search',{keyword});};
    $('fundSearchKeyword').onkeydown=e=>{if(e.key==='Enter'){e.preventDefault();$('fundSearchSubmit').click();}};
    for(const id of ['fundInitialShares','fundInitialUnitCost'])$(id).oninput=updateTotal;
    $('fundAddForm').onsubmit=e=>{
      e.preventDefault();if(!selection)return;const shares=$('fundInitialShares').value.trim(),unitCost=$('fundInitialUnitCost').value.trim(),valid=x=>/^\d+(?:\.\d{1,16})?$/.test(x)&&x.replace('.','').length<=32;
      if(!valid(shares)||!valid(unitCost)||(!/[1-9]/.test(shares)&&/[1-9]/.test(unitCost))){message('请输入非负份额和单位成本；份额为 0 时成本也须为 0。');return;}
      request('fund-add',{fundId:selection.fundId,accountId:$('fundAddAccount').value,shares,unitCost});
    };
  }
  document.addEventListener('DOMContentLoaded',()=>{
    const css=document.createElement('style');css.textContent='#fundAddForm input,#fundAddForm select{width:100%;min-height:44px;background:var(--surface-primary,#fff);color:var(--text-primary,#17212c);border:0;border-bottom:1px solid var(--border-color,#e5e8ed);border-radius:0;padding:8px;box-sizing:border-box;font:inherit}#fundSearchKeyword:focus,#fundSearchKeyword:focus-visible{outline:none;box-shadow:none;border-bottom-color:var(--sp-line,#e5e8ed)}#fundSearchResults .settings-choice[hidden],#fundSearchReselect[hidden]{display:none}#fundAddForm label{display:block;margin-top:12px;color:var(--text-secondary,#88929b);font-size:13px}#fundAddForm .v2-gap-fields{padding:16px}#fundSearchResults .settings-choice{display:flex;justify-content:space-between;align-items:center;gap:12px;min-height:52px;width:100%;padding:12px 16px;text-align:left;background:transparent;color:var(--text-primary,#17212c);border:0;border-bottom:1px solid var(--border-color,#e5e8ed)}#fundSearchResults span{min-width:0;overflow-wrap:anywhere}#fundSearchResults small{color:var(--text-secondary,#88929b)}#fundSearchResults [aria-pressed=true]{color:var(--accent,#339477)}.fund-search-line{display:flex;gap:12px;align-items:center}.fund-search-line input{min-width:0;flex:1}.fund-search-line button{flex:0 0 60px;min-height:44px}.fund-add-total{display:flex;justify-content:space-between;gap:12px;margin:20px 0;font-variant-numeric:tabular-nums}#fundAddSubmit{width:100%;min-height:44px}';document.head.append(css);
    const frame=$('framePortfolio');
    const emptyEntry=()=>{
      const doc=frame?.contentDocument,layer=doc?.getElementById('gapHoldingStatus'),state=V2Store.getState();
      if(!layer||state.holdingsState.status!=='EMPTY'||state.yjbAccount.status!=='CONNECTED'||layer.querySelector('#fundAddEmpty'))return;
      for(const p of layer.querySelectorAll('p'))if(p.textContent.includes('请先在养基宝添加基金'))p.textContent='当前暂无基金持仓。';
      const button=doc.createElement('button');button.id='fundAddEmpty';button.textContent='添加基金';button.onclick=()=>open(button);const row=doc.createElement('p');row.append(button);layer.firstElementChild?.append(row);
    };
    window.V2Store.subscribe(()=>queueMicrotask(emptyEntry));
    const attach=()=>{emptyEntry();
      const doc=frame?.contentDocument,sort=doc?.getElementById('sortButton');if(!sort||doc.getElementById('fundAddButton'))return;
      const button=doc.createElement('button');button.type='button';button.id='fundAddButton';button.className='compact-action';button.textContent='添加';button.style.minHeight='44px';button.onclick=()=>open(button);
      const divider=doc.createElement('span');divider.textContent='|';divider.setAttribute('aria-hidden','true');divider.style.cssText='color:var(--text-3);opacity:.5';sort.before(button,divider);
    };frame?.addEventListener('load',()=>{attach();queueMicrotask(emptyEntry);});attach();
    const app=window.V2App;window.V2App=Object.freeze({...app,detailSettings(id){settingsObserver?.disconnect();app.detailSettings(id);const doc=frame.contentDocument;const extend=()=>{
      const container=doc.getElementById('hsScroll'),f=V2Store.getState().holdings.find(f=>f.id===id);if(!f?.isPlaceholder||!container||doc.getElementById('fundRemovePlaceholder'))return;
      const button=doc.createElement('button');button.type='button';button.id='fundRemovePlaceholder';button.className='hs-row';button.textContent='移除基金';button.style.color='var(--red,#c0525e)';button.onclick=()=>{
        if(pending)return;if(!confirm('移除此基金卡片？'))return;
        const requestId=crypto.randomUUID();pending={requestId,action:'fund-remove'};send('fund-remove',{id,requestId});
      };container.querySelector('.hs-card')?.append(button);
    };extend();settingsObserver=new MutationObserver(extend);settingsObserver.observe(doc.getElementById('hsScroll'),{childList:true});}});
  });
})();
