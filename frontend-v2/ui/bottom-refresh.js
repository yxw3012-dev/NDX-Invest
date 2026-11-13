/* Native page scrolling reveals a hidden content-footer refresh area. */
(() => {
 'use strict';
 const markup='<span class="refresh-hint"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="m7 10 5 5 5-5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg></span><span class="refresh-symbol"><span class="refresh-arrow"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M3 12a9 9 0 0 1 9-9c2.52 0 4.93 1 6.74 2.74L21 8m0-5v5h-5M21 12a9 9 0 0 1-9 9c-2.52 0-4.93-1-6.74-2.74L3 16m0 5v-5h5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg></span><span class="refresh-check"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="m5 12 4.5 4.5L19 7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></span><span class="refresh-error-icon"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="m7 7 10 10M17 7 7 17" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg></span></span><span class="refresh-error-text" role="status"></span>';
 const css=`
 .v2-refresh-scroll{position:relative;overflow-y:scroll;overscroll-behavior:contain;touch-action:pan-y;scrollbar-width:none;will-change:auto;-webkit-overflow-scrolling:touch}
 .v2-refresh-scroll::-webkit-scrollbar{display:none}
 .v2-bottom-refresh.refresh{position:relative;display:block;left:auto;bottom:auto;transform:none;width:100%;height:144px;flex-shrink:0;margin:10px auto 0;border:0;padding:0;border-radius:0;background:transparent!important;box-shadow:none;color:var(--text-2,#77838c);cursor:pointer;touch-action:pan-y;-webkit-tap-highlight-color:transparent}
 .v2-bottom-refresh[hidden]{display:none!important}
 .v2-bottom-refresh .refresh-hint,.v2-bottom-refresh .refresh-symbol{position:absolute;left:50%;width:56px;display:grid;place-items:center;pointer-events:none;transition:opacity .5s ease,transform .6s cubic-bezier(.2,.8,.2,1)}
 .v2-bottom-refresh .refresh-hint{top:0;height:48px;transform:translateX(-50%)}
 .v2-bottom-refresh .refresh-hint svg{width:18px;height:18px;opacity:.6}
 .v2-bottom-refresh .refresh-symbol{top:68px;height:56px;border-radius:50%;background:var(--surface-soft,var(--surface-2,#eceef1));opacity:0;transform:translateX(-50%) scale(.58)}
 .v2-bottom-refresh .refresh-arrow,.v2-bottom-refresh .refresh-check{position:absolute;inset:0;display:grid;place-items:center;transition:opacity .5s ease,transform .6s cubic-bezier(.18,.85,.24,1.12)}
 .v2-bottom-refresh .refresh-arrow svg,.v2-bottom-refresh .refresh-check svg{width:30px;height:30px}
 .v2-bottom-refresh .refresh-check{opacity:0;transform:scale(.62);color:var(--brand,var(--green,#398f79))}
 .v2-bottom-refresh[data-refresh-phase=loading] .refresh-symbol,.v2-bottom-refresh[data-refresh-phase=success] .refresh-symbol,.v2-bottom-refresh[data-refresh-phase=returning] .refresh-symbol{opacity:1;transform:translateX(-50%) scale(1)}
 .v2-bottom-refresh[data-refresh-phase=loading] .refresh-hint,.v2-bottom-refresh[data-refresh-phase=success] .refresh-hint,.v2-bottom-refresh[data-refresh-phase=returning] .refresh-hint{opacity:0}
 .v2-bottom-refresh[data-refresh-phase=success] .refresh-arrow{opacity:0;transform:scale(.62)}
 .v2-bottom-refresh[data-refresh-phase=success] .refresh-check{opacity:1;transform:scale(1)}
 .v2-bottom-refresh[data-refresh-phase=loading] .refresh-arrow svg{animation:v2-bottom-spin .8s linear infinite}
 .v2-bottom-refresh[data-refresh-phase=revealing] .refresh-symbol,.v2-bottom-refresh[data-refresh-phase=revealing] .refresh-hint{transition:none}
 .v2-bottom-refresh:focus-visible{outline:2px solid var(--brand,#398f79);outline-offset:-2px}
 @keyframes v2-bottom-spin{to{transform:rotate(360deg)}}
 @media(prefers-reduced-motion:reduce){.v2-bottom-refresh :is(.refresh-symbol,.refresh-hint,.refresh-arrow,.refresh-check){transition:none}}
 `;

 function mount(button,options={}) {
  const doc=button.ownerDocument,view=doc.defaultView;let host=options.scrollRoot;
  if(!host)throw Error('Content footer requires a scroll root');
  const previousRootOverscroll=doc.documentElement.style.overscrollBehavior,previousBodyOverscroll=doc.body.style.overscrollBehavior;
  if(options.wrapContent){const viewport=doc.createElement('div');host.before(viewport);viewport.append(host);host=viewport;}
  if(!doc.getElementById('v2BottomRefreshStyle')){const style=doc.createElement('style');style.id='v2BottomRefreshStyle';style.textContent=css+extraCss;doc.head.append(style);}
  host.classList.add('v2-refresh-scroll');host.append(button);button.classList.add('refresh','v2-bottom-refresh');button.type='button';button.innerHTML=markup;
  const symbol=button.querySelector('.refresh-symbol'),hint=button.querySelector('.refresh-hint'),error=button.querySelector('.refresh-error-text');
  let loading=false,enabled=true,requested=false,armed=false,eligible=false,touching=false,startY=0,travel=0,frame=0,settle=0,timer=0,closing=0,wheelEnd=0;
  const reduced=()=>view.matchMedia('(prefers-reduced-motion:reduce)').matches;
  const foldedTop=()=>Math.max(0,button.offsetTop+48-host.clientHeight);
  const atEnd=()=>host.scrollTop>=foldedTop()-2;
  const resetPaint=()=>{symbol.style.cssText='';hint.style.cssText='';};
  function phase(value){if(button.dataset.refreshPhase!==value)button.dataset.refreshPhase=value;button.setAttribute('aria-busy',String(value==='loading'));button.setAttribute('aria-label',value==='failure'?'刷新失败，向下滑动收起':value==='loading'?'正在刷新数据':value==='success'?'数据更新完成':'浏览到底后再次上滑刷新，或点击刷新');}
  function fold(){
   eligible=false;armed=false;requested=false;view.clearTimeout(settle);host.scrollTo({top:Math.min(host.scrollTop,foldedTop()),behavior:reduced()?'instant':'smooth'});
   view.clearTimeout(closing);closing=view.setTimeout(()=>{button.classList.remove('is-expanded');resetPaint();error.textContent='';phase('idle');armed=atEnd();},reduced()?0:500);
  }
  function cancel(){view.clearTimeout(timer);view.clearTimeout(closing);view.clearTimeout(settle);touching=false;eligible=false;armed=false;requested=false;button.classList.remove('is-expanded');resetPaint();error.textContent='';phase('idle');layout();}
  function layout(){
   const active=!button.hidden;doc.documentElement.style.overscrollBehavior=active?'auto':previousRootOverscroll;doc.body.style.overscrollBehavior=active?'auto':previousBodyOverscroll;
   if(button.hidden)return;
   const top=host.getBoundingClientRect().top,nav=view.parent.document.querySelector('.global-nav'),limit=nav?nav.getBoundingClientRect().top-12:view.innerHeight-80;
   const height=Math.max(48,limit-top);if(Math.abs(host.clientHeight-height)>1)host.style.height=height+'px';
   const padding=parseFloat(view.getComputedStyle(host).paddingBottom)||0;
   const contentEnd=button.offsetTop-(parseFloat(view.getComputedStyle(button).marginTop)||0);
   const gap=Math.max(10,host.clientHeight-padding-contentEnd-48);if(Math.abs(parseFloat(button.style.marginTop||'10')-gap)>1)button.style.marginTop=gap+'px';
  }
  function update(busy,succeeded=false,message=''){
   busy=!!busy;button.disabled=busy||!enabled;
   if(busy&&!loading&&requested){view.clearTimeout(timer);view.clearTimeout(closing);button.classList.add('is-expanded');resetPaint();phase('loading');host.scrollTo({top:host.scrollHeight-host.clientHeight,behavior:reduced()?'instant':'smooth'});}
   if(!busy&&loading&&requested){
    requested=false;resetPaint();phase(succeeded?'success':'failure');
    if(succeeded)timer=view.setTimeout(fold,1000);
    else{error.textContent=message||'数据更新失败，请稍后重试';host.scrollTo({top:host.scrollHeight-host.clientHeight,behavior:reduced()?'instant':'smooth'});}
   }
   loading=busy;
  }
  const exposure=()=>Math.max(0,Math.min(1,(host.scrollTop+host.clientHeight-button.offsetTop-48)/120));
  function finishScroll(){
   if(touching||loading||['success','failure'].includes(button.dataset.refreshPhase))return;
   if(eligible&&enabled&&!button.hidden&&exposure()>=.5){eligible=false;button.click();}
   else if(eligible){phase('idle');fold();}
   else armed=atEnd();
  }
  function paint(){frame=0;if(loading||['success','failure'].includes(button.dataset.refreshPhase))return;
   if(eligible){const ratio=exposure();phase('revealing');symbol.style.cssText='opacity:'+ratio+';transform:translateX(-50%) scale('+(.58+.42*ratio)+')';hint.style.opacity=String(1-ratio);}
   view.clearTimeout(settle);if(!touching)settle=view.setTimeout(finishScroll,180);
  }
  function begin(target){view.clearTimeout(settle);travel=0;eligible=!loading&&button.dataset.refreshPhase==='idle'&&armed&&atEnd()&&options.canStart?.(target)!==false;if(eligible){view.clearTimeout(closing);button.classList.add('is-expanded');phase('revealing');}armed=false;}
  host.addEventListener('scroll',()=>{if(!frame)frame=view.requestAnimationFrame(paint);},{passive:true});
  host.addEventListener('touchstart',event=>{touching=true;startY=event.touches[0]?.clientY||0;begin(event.target);},{passive:true});
  host.addEventListener('touchmove',event=>{if(event.touches.length===1)travel=startY-event.touches[0].clientY;},{passive:true});
  host.addEventListener('touchend',()=>{touching=false;if(button.dataset.refreshPhase==='failure'){if(travel<=-24)fold();return;}if(eligible&&travel<8){phase('idle');fold();return;}if(!frame)frame=view.requestAnimationFrame(paint);},{passive:true});
  host.addEventListener('touchcancel',()=>{touching=false;eligible=false;if(button.dataset.refreshPhase==='revealing')fold();},{passive:true});
  host.addEventListener('wheel',event=>{if(event.deltaY<0&&button.dataset.refreshPhase==='failure'){fold();return;}if(event.deltaY>0&&Date.now()-wheelEnd>240)begin(event.target);wheelEnd=Date.now();if(!frame)frame=view.requestAnimationFrame(paint);},{passive:true});
  host.addEventListener('keydown',event=>{if(event.key==='ArrowUp'&&button.dataset.refreshPhase==='failure')fold();if(['ArrowDown','PageDown','End',' '].includes(event.key))begin(event.target);});
  button.addEventListener('click',event=>{if(loading||!enabled||button.dataset.refreshPhase==='failure'||options.canStart?.(button)===false){event.stopImmediatePropagation();return;}requested=true;eligible=false;armed=false;button.classList.add('is-expanded');options.onRefresh?.();},true);
  const observer=new view.ResizeObserver(()=>view.requestAnimationFrame(layout));observer.observe(host);for(const child of host.children)if(child!==button)observer.observe(child);
  view.addEventListener('resize',layout);doc.fonts?.ready.then(layout);phase('idle');layout();
  if(view.frameElement)new view.MutationObserver(layout).observe(view.frameElement,{attributes:true,attributeFilter:['class']});
  return Object.freeze({update,cancel,setEnabled(value){enabled=!!value;button.disabled=loading||!enabled;if(!enabled&&button.dataset.refreshPhase!=='failure')cancel();else layout();}});
 }
 const extraCss=`
 .v2-bottom-refresh.refresh{height:48px;overflow:hidden}
 .v2-bottom-refresh.refresh.is-expanded{height:168px}
 .v2-bottom-refresh .refresh-error-icon{position:absolute;inset:0;display:grid;place-items:center;opacity:0;transform:scale(.62);transition:opacity .5s ease,transform .6s cubic-bezier(.2,.8,.2,1);color:var(--text-2,#77838c)}
 .v2-bottom-refresh .refresh-error-icon svg{width:30px;height:30px}
 .v2-bottom-refresh .refresh-error-text{display:none;position:absolute;top:132px;left:4px;right:4px;line-height:20px;font-size:11px;font-weight:400;text-align:center;color:var(--text-2,#77838c);white-space:normal;overflow-wrap:anywhere}
 .v2-bottom-refresh[data-refresh-phase=failure] .refresh-symbol{opacity:1;transform:translateX(-50%) scale(1)}
 .v2-bottom-refresh[data-refresh-phase=failure] .refresh-hint{opacity:0}
 .v2-bottom-refresh[data-refresh-phase=failure] .refresh-arrow{opacity:0;transform:scale(.62)}
 .v2-bottom-refresh[data-refresh-phase=failure] .refresh-error-icon{opacity:1;transform:scale(1)}
 .v2-bottom-refresh[data-refresh-phase=failure].is-expanded{height:auto;min-height:168px;padding:132px 8px 16px;box-sizing:border-box}
 .v2-bottom-refresh[data-refresh-phase=failure] .refresh-error-text{display:block;position:relative;top:auto;left:auto;right:auto}
 @media(prefers-reduced-motion:reduce){.v2-bottom-refresh .refresh-error-icon{transition:none}}
 `;
 window.V2BottomRefresh=Object.freeze({mount});
})();
