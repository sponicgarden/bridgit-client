/* Shared applet UI defaults. No capture, model or network authority is created here. */
(function () {
 'use strict';
 if (window.BridgitUI) return;
 var CAMERA = '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M3 7h4l2-3h6l2 3h4v13H3z"/><circle cx="12" cy="13" r="4"/></svg>';
 function progress(value) {
  var names = ['taken','processed','failed','active','queued'], p = {};
  names.forEach(function (name) { var n=value[name]; if (!Number.isSafeInteger(n)||n<0) throw Error('Invalid progress: '+name); p[name]=n; });
  p.total=value.total == null ? null : value.total;
  if (p.total !== null && (!Number.isSafeInteger(p.total)||p.total<0||p.total!==p.processed+p.failed+p.active+p.queued)) throw Error('Progress total must equal processed + failed + active + queued');
  p.pending=p.active+p.queued;
  return p;
 }
 function renderProgress(node, value) {
  var p=progress(value), text=p.taken+' taken · '+p.processed+' processed · '+p.pending+' pending · '+p.failed+' failed';
  node.replaceChildren();node.setAttribute('role','status');node.setAttribute('aria-live','polite');
  var label=document.createElement('span');label.textContent=text;node.append(label);
  var meter=document.createElement('progress');meter.setAttribute('aria-label','Photos processed');
  if(p.total!==null&&p.total>0){meter.max=p.total;meter.value=p.processed;} // failures are never processed success
  node.append(meter);return p;
 }
 var dialog, image, caption, previous, next, retry, status, sequence=[], index=0, more=null, resolve=null, opener=null, generation=0, busy=false, owned=[];
 function clearOwned(){owned.forEach(function(u){URL.revokeObjectURL(u);});owned=[];}
 function style() {
  if(document.getElementById('bridgit-ui-style'))return;
  var s=document.createElement('style');s.id='bridgit-ui-style';s.textContent=
   '.bridgit-media{color-scheme:light dark;background:light-dark(#fff,#172033);color:light-dark(#182033,#f4f6ff);border:1px solid #8590a055;border-radius:20px;padding:16px;width:min(94vw,1000px);max-width:94vw;max-height:94dvh;box-sizing:border-box;box-shadow:0 20px 80px #0005}.bridgit-media::backdrop{background:#080f20bd}.bridgit-media img{display:block;width:100%;height:min(65dvh,720px);object-fit:contain;border-radius:12px;background:#080f20}.bridgit-media footer{display:flex;gap:10px;align-items:center;justify-content:space-between;margin-top:12px;flex-wrap:wrap}.bridgit-media button{min-width:44px;min-height:44px;border:1px solid #8590a055;border-radius:12px;background:transparent;color:inherit;font:inherit;padding:10px 16px}.bridgit-media button:focus-visible,.bridgit-image:focus-visible{outline:3px solid #728cff;outline-offset:3px}.bridgit-media button:disabled{opacity:.4}.bridgit-image{cursor:zoom-in;min-width:44px;min-height:44px;object-fit:contain}.bridgit-capture{display:inline-flex;align-items:center;justify-content:center;gap:8px;min-height:44px}';
  document.head.append(s);
 }
 function ensure() {
  if(dialog)return;style();
  dialog=document.createElement('dialog');dialog.className='bridgit-media';dialog.setAttribute('aria-label','Photo viewer');
  image=document.createElement('img');caption=document.createElement('span');caption.id='bridgit-media-caption';dialog.setAttribute('aria-describedby',caption.id);
  status=document.createElement('p');status.setAttribute('role','status');
  retry=document.createElement('button');retry.type='button';retry.textContent='Retry photo';retry.hidden=true;retry.onclick=function(){show(index);};
  var footer=document.createElement('footer');previous=document.createElement('button');next=document.createElement('button');var close=document.createElement('button');
  previous.type=next.type=close.type='button';previous.textContent='←';previous.setAttribute('aria-label','Previous photo');next.textContent='→';next.setAttribute('aria-label','Next photo');close.textContent='Close';close.autofocus=true;
  previous.onclick=function(){show(index-1);};next.onclick=function(){advance();};close.onclick=function(){dialog.close();};
  footer.append(previous,caption,next,close);dialog.append(image,status,retry,footer);document.body.append(dialog);
  dialog.addEventListener('keydown',function(e){if(e.key==='ArrowLeft'){e.preventDefault();if(!busy)show(index-1);}if(e.key==='ArrowRight'){e.preventDefault();if(!busy)advance();}});
  dialog.addEventListener('close',function(){generation++;busy=false;image.removeAttribute('src');clearOwned();sequence=[];more=null;resolve=null;if(opener?.isConnected)opener.focus();opener=null;});
  image.onerror=function(){if(dialog.open){status.textContent='Photo unavailable.';retry.hidden=false;}};
 }
 async function show(at) {
  if(at<0||at>=sequence.length)return;var run=++generation, item=sequence[at];index=at;busy=true;
  image.removeAttribute('src');clearOwned();retry.hidden=true;image.alt=item.alt||item.caption||'Photo';caption.textContent=(at+1)+' of '+sequence.length+(more?' +':'')+(item.caption?' · '+item.caption:'');status.textContent='Loading photo…';previous.disabled=at===0;next.disabled=at===sequence.length-1&&!more;
  try {var src=resolve?await resolve(item):item.src;if(run!==generation||!dialog.open)return;
   if(typeof Blob!=='undefined'&&src instanceof Blob){src=URL.createObjectURL(src);owned.push(src);}
   if(typeof src!=='string'||!src)throw Error('Missing photo');image.src=src;status.textContent='';
  }catch(e){if(run===generation){status.textContent='Photo unavailable.';retry.hidden=false;}}
  finally{if(run===generation)busy=false;}
 }
 async function advance(){
  if(busy)return;
  if(index+1<sequence.length){show(index+1);return;}
  if(!more)return;var run=generation;busy=true;next.disabled=true;status.textContent='Loading more photos…';
  try{var page=await more();if(run!==generation||!dialog.open)return;
   var ids=new Set(sequence.map(function(x){return x.id;}));(page.items||[]).forEach(function(x){if(!ids.has(x.id)){sequence.push(x);ids.add(x.id);}});more=page.loadMore||null;busy=false;
   if(index+1<sequence.length)show(index+1);else{status.textContent='No more photos';next.disabled=!more;}
  }catch(e){if(run===generation){status.textContent='Could not load photos. Try Next again.';next.disabled=false;}}
  finally{if(run===generation)busy=false;}
 }
 function openMedia(options){
  if(!Array.isArray(options.items)||!options.items.length)return;ensure();generation++;clearOwned();
  sequence=options.items.slice();more=options.loadMore||null;resolve=options.resolve||null;opener=options.opener||document.activeElement;
  if(!dialog.open)dialog.showModal();show(Number.isInteger(options.index)?options.index:0);
 }
 function enhance(root){
  style();
  var scopes=root?[root]:Array.from(document.querySelectorAll('[data-bridgit-ui]'));
  scopes.forEach(function(scope){
  scope.querySelectorAll('img:not([data-bridgit-enhanced])').forEach(function(img){
   // Existing custom controls and explicit, reviewed opt-outs keep their handlers.
   if(img.closest('button,a,dialog,[role="button"],[onclick]')||img.closest('[data-bridgit-viewer="off"]'))return;
   img.dataset.bridgitEnhanced='true';img.tabIndex=0;img.setAttribute('role','button');img.setAttribute('aria-label','Open photo'+(img.alt?': '+img.alt:''));img.classList.add('bridgit-image');
   function open(){var nodes=Array.from(scope.querySelectorAll('img[data-bridgit-enhanced]')).filter(function(n){return n.isConnected&&!n.closest('[hidden]');});
    openMedia({items:nodes.map(function(n,i){return{id:String(i),src:n.currentSrc||n.src,alt:n.alt};}),index:nodes.indexOf(img),opener:img});}
   img.addEventListener('click',open);img.addEventListener('keydown',function(e){if(e.key==='Enter'||e.key===' '){e.preventDefault();open();}});
  });
  scope.querySelectorAll('[data-bridgit-action="capture-photo"]:not([data-bridgit-capture])').forEach(function(button){
   button.dataset.bridgitCapture='true';button.classList.add('bridgit-capture');if(!button.getAttribute('aria-label')&&!button.textContent.trim())button.setAttribute('aria-label','Take photo');button.insertAdjacentHTML('afterbegin',CAMERA);
  });
  });
 }
 window.BridgitUI={contract:'bridgit-ui/1',cameraIcon:CAMERA,progress:progress,renderProgress:renderProgress,openMedia:openMedia,enhance:enhance};
 if(typeof document!=='undefined'){enhance();new MutationObserver(function(records){if(records.some(function(r){return !dialog?.contains(r.target)&&r.target.id!=='bridgit-ui-style';}))enhance();}).observe(document.body,{childList:true,subtree:true});}
})();
