/* Production-only public market projection. Frozen V2 markup, animations and YJB state are preserved. */
(() => {
  'use strict';
  if (!window.NDXMarketRead || !window.V2Store) return;
  const base = window.V2Store;
  let market = {
    indexText:'--', changeText:'--', changePercentText:'--', marketStatus:'正在读取指数',
    updatedText:'--', fxText:'--', fxChangeText:'--', fxUpdatedText:'暂无汇率时间',
    drawdownText:'--', drawdownRate:null, drawdownBasisText:'历史数据暂不可用', historyText:'正在读取历史',
    performance:Array.from({length:4}, () => Array(4).fill('--')), refreshing:true, refreshSucceeded:false,refreshError:'',
  };
  let sources = {indexStatus:'读取中', fxStatus:'读取中', historyStatus:'读取中', lastUpdatedAt:'--'};
  const tierLabel = i => ['第一档','第二档','第三档','第四档','第五档','第六档','第七档','第八档'][i] || `第${i+1}档`;
  function projection(state) {
    const tiers = state.nd100Strategy.tiers;
    const known = market.drawdownRate !== null && Number.isFinite(Number(market.drawdownRate));
    const magnitude = known ? Math.abs(Number(market.drawdownRate))*100 : 0;
    let active=-1;
    if(known) tiers.forEach((t,i) => {if(magnitude>=Number(t.drawdownThreshold)) active=i;});
    return {
      ...state.nd100, ...market,
      stages:[{label:'观察',range:'0%',color:'#b5c5b8',active:known&&active===-1}, ...tiers.map((t,i) => ({
        label:tierLabel(i), range:`-${t.drawdownThreshold}%`,color:['#8cb49b','#589975','#397d5b','#285f45'][Math.min(i,3)],active:known&&i===active,
      }))],
      stageName:known ? (active<0?'观察':tierLabel(active)) : '--',
      planLabel:'计划投入',
      planText:!known?'--':active<0?'¥0':`¥${tiers[active].plannedAmount}`,
      markerPercent:known?v2DrawdownMarkerPercent(magnitude,tiers):0,
    };
  }
  function apply() {
    const state=base.getState();
    base.replaceUiState({...state, nd100:projection(state), dataSourceState:{...state.dataSourceState,...sources}});
  }
  window.MarketReadApply = payload => {
    if (!payload || typeof payload!=='object') return;
    // Only this read controller may project market fields, never holdings/history/credentials.
    const keys=Object.keys(market);
    if(payload.nd100) for(const key of keys) if(Object.hasOwn(payload.nd100,key)) market[key]=payload.nd100[key];
    if(payload.dataSourceState) sources={...sources,...payload.dataSourceState};
    apply();
  };
  window.V2Store=Object.freeze({...base,
    saveStrategy(draft) {
      const result=base.saveStrategy(draft);
      if(result.ok){apply();return {...result,message:'策略档位已保存 · 仅更新本地展示'};}
      return result;
    },
  });
  apply();

  // Diagnostics stay on the existing data-status page and tooltips; no extra home status copy.
  function frameCopy() {
    const frame=document.getElementById('frameNDX100'), doc=frame?.contentDocument;
    if(!doc)return;
    const state=base.getState(), m=state.nd100;
    const fx=doc.querySelector('.fx-value');if(fx)fx.title=m.fxUpdatedText;
    const label=doc.querySelector('.drawdown-head .small-label');
    if(label)label.title=m.drawdownBasisText;
    doc.defaultView.V2HomeRefresh?.update(!!m.refreshing,!!m.refreshSucceeded,m.refreshError);
  }
  function attachFrame() {
    const frame=document.getElementById('frameNDX100');if(!frame)return;
    function ready() {
      const button=frame.contentDocument?.getElementById('refreshButton');
      if(button&&!button.dataset.marketReadBound){
        button.dataset.marketReadBound='true';
        button.addEventListener('click',() => {
          market={...market,refreshing:true,refreshSucceeded:false};
          apply();window.NDXMarketRead.post('refresh');queueMicrotask(frameCopy);
        });
      }
      frameCopy();
    }
    frame.addEventListener('load',ready);ready();
  }
  base.subscribe(() => queueMicrotask(frameCopy));
  document.addEventListener('DOMContentLoaded',attachFrame);
})();
