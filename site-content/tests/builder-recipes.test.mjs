import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import path from 'node:path';
import vm from 'node:vm';
import {fileURLToPath} from 'node:url';
import {buildRecipeFiles,renderCard,validateRecipes,validateRequest,appletGuideKotlin} from '../scripts/builder-recipes.mjs';
const root=fileURLToPath(new URL('../',import.meta.url));
const read=p=>readFile(path.join(root,p),'utf8');
const data=JSON.parse(await read('content/builder/recipes.json'));
const packages=JSON.parse(await read('content/builder/applet-packages.json')).packages;
const template=await read('content/builder/recipe-card.html');
const openapi=JSON.parse(await read('public/learn/openapi.json'));
function card(recipe,{reply={ok:true,status:200,body:{}},bridge=true,config={}}={}) {
  const nodes=new Map(),events={},calls=[],answers=[],timers=[];
  const el=id=>{if(!nodes.has(id))nodes.set(id,{textContent:'',value:'',hidden:false,disabled:false,innerHTML:''});return nodes.get(id)};
  const document={body:{dataset:{}},getElementById:el,addEventListener:(name,fn)=>events[name]=fn};
  const api={act:async request=>{calls.push(request);return typeof reply==='function'?reply(request):reply},respond:value=>answers.push(JSON.parse(value))};
  const html=renderCard(template,recipe,{resource_id:'wfixture',gallery_id:42,...config});
  const context=vm.createContext({document,window:bridge?{Bridgit:api}:{},setInterval:fn=>timers.push(fn),console});
  vm.runInContext(html.match(/<script>([\s\S]*?)<\/script>/)[1],context);
  return {context,el,document,calls,answers,events,timers,html};
}
test('all generated recipe assets are current and source-evidenced',async()=>{
  const files=await buildRecipeFiles(root);
  assert.equal(Object.keys(files).length,41);
  for(const name of Object.keys(files))assert.doesNotMatch(name,/card\.html$/,'pieces cards need no generated card.html');
  for(const [name,text] of Object.entries(files))assert.equal(await read('public/'+name),text,name);
  const published=JSON.parse(files['learn/builder/recipes.json']);
  for(const r of published.recipes){assert.equal(r.support.ready,null);assert.ok(r.operations.length>=2);assert.ok(r.evidence.length>=4);assert.match(r.card_request.body.html,/<!doctype html>/);assert.ok(Buffer.byteLength(r.card_request.body.html)<256*1024);assert.doesNotMatch(r.card_request.body.html,/<(?:iframe|object|embed)\b|\bfetch\s*\(|<script\s+src|data:image\/svg/i);}
});
test('every recipe is an installable applet package that fills back to the recipe',async()=>{
  const {sha256,checkPackage}=await import('../scripts/applet-packages.mjs');
  const files=await buildRecipeFiles(root);
  const fill=(v,cfg)=>Array.isArray(v)?v.map(x=>fill(x,cfg)):v&&typeof v==='object'?('$input' in v?cfg[v.$input]:Object.fromEntries(Object.entries(v).map(([k,x])=>[k,fill(x,cfg)]))):v;
  for(const r of data.recipes){
    const text=files[`recipes/${r.id}/applet.json`], d=checkPackage(JSON.parse(text));
    assert.equal(d.runtime.adapter,r.id==='pet-monitor'?'bridgit.package.v1':'android.package.v1');assert.equal(d.runtime.supports_dynamic_execution,false);assert.equal(d.permissions.automatic_grants,false);
    assert.ok(d.permissions.required.includes('interactions'),r.id);assert.ok(Array.isArray(d.ui.card.pieces),r.id);assert.equal(d.ui.card.asset,undefined);assert.equal(d.assets,undefined);
    const releaseVersions = {'pet-monitor':'3.1.2','receipt-scanner':'2.0.1'};
    assert.equal(d.version,releaseVersions[r.id] ?? '2.0.0',r.id);
    for(const k of ['observation_schema','metrics','lifecycle'])assert.equal(d[k],undefined,`${r.id}: ${k} is filled by validate`);
    if(r.id==='pet-monitor'){
      assert.equal(d.migration.from,'3.1.1');
      assert.equal(d.data_policy.device_only.remote_models,true);
      assert.ok(d.permissions.remote.length && d.budget.day_usd>0);
      assert.ok(d.processing.flows.find(f=>f.id==='review').steps.some(s=>s.type==='record' && s.table==='checks'));
    } else if (r.id==='receipt-scanner') {
      assert.equal(d.migration.from,'2.0.0');
      assert.ok(d.permissions.required.includes('gallery'));
    } else { assert.equal(d.migration,undefined); assert.equal(d.data_policy,undefined); }
    assert.ok(Buffer.byteLength(text)<16*1024,r.id);
    const page=files[`recipes/${r.id}/index.html`];assert.match(page,/class="cp-card"/);assert.match(page,/It is a preview|A preview/);assert.match(page,new RegExp(`/install/\\?src=%2Frecipes%2F${r.id}%2Fapplet\\.json&amp;sha256=${sha256(text)}`));
    const start=r.examples.find(e=>e.id==='start'&&e.request.path==='/v1/watches');
    if(start){const filled=fill(d.processing.watches[0].request,d.defaults);
      // The package may start a package flow where the plain watch makes a raw call (packages[id].set): put the raw call back to compare.
      for(const {path:p} of packages[r.id].set??[]){const keys=p.split('/').slice(1);let a=filled,b=start.request.body;for(const k of keys.slice(0,-1)){a=a[k];b=b[k];}a[keys.at(-1)]=b[keys.at(-1)];}
      assert.deepEqual(filled,start.request.body,r.id);assert.ok(d.permissions.required.includes('watches'));assert.ok(Object.values(d.ui.card.sources).some(s=>s.watch?.$watch==='main'),r.id);}
    if(r.start_request){const m=fill(d.processing.schedule,d.defaults).manifest;assert.equal(m.actions[0].when.daily[0],'08:00');assert.equal(m.actions[0].when.count,undefined);assert.ok(d.permissions.required.includes('schedule'));}
  }
});
test('unknown operations, malformed request fields and unsafe rates fail publication',()=>{
  const copy=()=>structuredClone(data);
  let d=copy();d.recipes[2].examples[0].request.path='/v1/audio/record';assert.throws(()=>validateRecipes(d,openapi),/Unknown operation/);
  d=copy();d.recipes[2].examples[0].request.body.duration_seconds='10';assert.throws(()=>validateRecipes(d,openapi),/invalid duration/);
  d=copy();d.recipes[0].examples[0].request.body.max_paid_per_hour=241;assert.throws(()=>validateRecipes(d,openapi),/Unbounded/);
  assert.throws(()=>validateRequest(openapi,{method:'POST',path:'/v1/interactions',body:{kind:'html'}}),/missing title/);
  assert.throws(()=>validateRequest(openapi,{method:'GET',path:'/v1/weather',query:{secret:'x'}}),/unknown query/);
});
test('each embedded card operation matches canonical OpenAPI and does nothing on load',async()=>{
  for(const r of data.recipes){const c=card(r);assert.equal(c.calls.length,0,r.id);assert.equal(c.answers.length,0);assert.equal(c.document.body.dataset.state,'empty');await c.context.refresh();assert.equal(c.calls.length,1,r.id);validateRequest(openapi,JSON.parse(JSON.stringify(c.calls[0])));}
});
test('rendering is distinct owner-facing UI and escapes untrusted source strings',async()=>{
  const fixtures={
    'pet-monitor':{last_seen_at:new Date().toISOString(),today:{checks:4,watched_minutes:2,labels:{eating:{episodes:1,minutes:1}}},episodes:[{label:'eating',start:new Date().toISOString(),minutes:1}]},
    'room-change-watcher':{checks:3,matches:1,last_check:{at:new Date().toISOString()},recent_checks:[{at:new Date().toISOString(),matched:true}]},
    'receipt-scanner':{text:'TOTAL 12.50 EUR',script:'Latin',on_device:true},
    'voice-notebook':{transcript:'Buy oats',duration_seconds:10,on_device:true},
    'daily-briefing-panel':{now:{summary:'Rain <img onerror=bad>',temperature_c:12,wind_speed_mps:4},next_24_hours:{min_temperature_c:8,max_temperature_c:13,precipitation_mm:2},fetched_at:new Date().toISOString()},
    'external-device-dashboard':{devices:[{name:'Lamp <script>bad',adapter:'shelly_gen1',availability:'not_checked'}]},
    'situation-counter':{last_seen_at:new Date().toISOString(),today:{checks:30,watched_minutes:10,labels:{pot_boiling_over:{episodes:2,minutes:3}}},episodes:[{label:'pot_boiling_over',start:new Date().toISOString(),minutes:1}]}
  };
  const expected=['episodes','Script','Bounded recording','Image changed','Temperature','Not checked','Times seen today'];
  for(const [i,r] of data.recipes.entries()){const c=card(r,{reply:{ok:true,status:200,body:fixtures[r.id]}});await c.context.refresh();assert.match(c.el('detail').innerHTML,new RegExp(expected[i]));assert.doesNotMatch(c.el('detail').innerHTML,/<script>|<img/);assert.doesNotMatch(c.el('detail').innerHTML,/"devices"|"paid_calls"|"today"/);assert.equal(c.document.body.dataset.state,'ready');}
});
test('browser/missing config and bridge errors never simulate results',async()=>{
  let c=card(data.recipes[2],{bridge:false});await c.context.refresh();assert.equal(c.document.body.dataset.state,'error');assert.equal(c.calls.length,0);
  c=card(data.recipes[0],{config:{resource_id:null}});await c.context.refresh();assert.equal(c.document.body.dataset.state,'error');assert.equal(c.calls.length,0);
  c=card(data.recipes[2],{reply:{ok:false,status:403,error:'agent_not_allowed',body:{message:'Microphone not granted'}}});await c.context.refresh();assert.equal(c.document.body.dataset.state,'error');assert.equal(c.el('message').textContent,'Microphone not granted');assert.equal(c.el('draft').value,'');
});
test('loading, empty and stale are honest source states',async()=>{
  let resolve;const c=card(data.recipes[4],{reply:()=>new Promise(r=>resolve=r)});const pending=c.context.refresh();assert.equal(c.document.body.dataset.state,'loading');resolve({ok:true,status:200,body:{stale:true,fetched_at:'2020-01-01T00:00:00Z'}});await pending;assert.equal(c.document.body.dataset.state,'stale');
  const empty=card(data.recipes[5],{reply:{ok:true,status:200,body:{devices:[]}}});await empty.context.refresh();assert.equal(empty.document.body.dataset.state,'empty');
  const fresh=card(data.recipes[2],{reply:{ok:true,status:200,body:{recorded_at:'2020-01-01T00:00:00Z',transcript:'old'}}});await fresh.context.refresh();assert.equal(fresh.document.body.dataset.state,'stale');
});
test('watch pause and stop require the returned state and ID; failed actions leave retry available',async()=>{
  const c=card(data.recipes[0],{reply:req=>({ok:true,status:200,body:req.method==='DELETE'?{deleted:'wrong'}:{user_paused:req.body.paused}})});
  await c.context.pause();assert.equal(c.document.body.dataset.state,'paused');assert.equal(c.el('refresh').disabled,true);await c.context.pause();assert.equal(c.context.paused,false);
  await c.context.stop();assert.equal(c.document.body.dataset.state,'error');assert.equal(c.context.stopped,false);assert.equal(c.el('stop').disabled,false);
  const stopped=card(data.recipes[0],{reply:{ok:true,status:200,body:{deleted:'wfixture'}}});await stopped.context.stop();assert.equal(stopped.document.body.dataset.state,'stopped');assert.equal(stopped.answers.length,0);await stopped.context.refresh();assert.equal(stopped.calls.length,1);
});
test('one answer waits for persisted event and does not claim controller execution',()=>{
  const c=card(data.recipes[2]);c.el('draft').value='Buy oats';c.context.send('confirm');assert.equal(c.answers.length,1);assert.equal(c.context.answered,false);assert.match(c.el('confirmation').textContent,/Waiting/);c.context.send('confirm');assert.equal(c.answers.length,1);c.events['bridgit:answered']();assert.equal(c.context.answered,true);assert.match(c.el('confirmation').textContent,/processing has not been confirmed/);c.context.send('confirm');assert.equal(c.answers.length,1);
});
test('late refresh cannot overwrite stopped state or claim capture cancellation',async()=>{
  let resolve;const c=card(data.recipes[2],{reply:()=>new Promise(r=>resolve=r)});const pending=c.context.refresh();assert.equal(c.el('stop').disabled,true); // capture has no invented cancellation endpoint
  c.context.busy=false;await c.context.stop();resolve({ok:true,status:200,body:{transcript:'late'}});await pending;assert.equal(c.document.body.dataset.state,'stopped');assert.equal(c.el('draft').value,'');assert.match(c.el('message').textContent,/Controller shutdown is requested separately/);
});
test('pet monitor package is the spec example; tables back the receipt and voice cards',async()=>{
  const files=await buildRecipeFiles(root);
  // The authoring guide's full example must be usable as the published package.
  const md=await read('../docs/applet-vocabulary.md');
  const spec=JSON.parse(md.split('### Full example: Pet monitor')[1].match(/```json\n([\s\S]*?)```/)[1]);
  const pet=JSON.parse(files['recipes/pet-monitor/applet.json']);
  assert.deepEqual(spec,pet);
  for(const [id,table,cols] of [['receipt-scanner','receipts',{text:'text',gallery_id:'integer'}],['voice-notebook','notes',{text:'text',source_at:'time'}]]){
    const d=JSON.parse(files[`recipes/${id}/applet.json`]),t=d.processing.tables.find(t=>t.id===table);
    assert.deepEqual(Object.fromEntries(Object.entries(t.columns).map(([k,c])=>[k,c.type])),cols);
    const pieces=d.ui.card.pieces,buttons=pieces.filter(p=>p.type==='buttons').flatMap(p=>p.items);
    assert.ok(pieces.some(p=>p.type==='draft'));assert.ok(buttons.some(b=>b.do.record===table));assert.ok(pieces.some(p=>p.type==='table'||p.type==='timeline'));
  }
});
test('card checks refuse unknown sources, drafts, tables and mixed cards',async()=>{
  const {checkCard}=await import('../scripts/applet-packages.mjs');
  const files=await buildRecipeFiles(root);
  const base=JSON.parse(files['recipes/voice-notebook/applet.json']);
  const bad=f=>{const d=structuredClone(base);f(d);return ()=>checkCard(d)};
  assert.doesNotThrow(bad(()=>{}));
  assert.throws(bad(d=>d.ui.card.asset='card.html'),/exactly one of asset or pieces/);
  assert.throws(bad(d=>d.ui.card.pieces.push({type:'chart'})),/unknown piece/);
  assert.throws(bad(d=>d.ui.card.pieces.push({type:'text',text:{$source:'nope.x'}})),/names no source/);
  assert.throws(bad(d=>d.ui.card.pieces.push({type:'buttons',items:[{label:'Save',do:{record:'notes',row:{text:{$draft:'other'}}}}]})),/names no draft/);
  assert.throws(bad(d=>d.ui.card.pieces.push({type:'buttons',items:[{label:'Save',do:{record:'meals',row:{}}}]})),/unknown table/);
  assert.throws(bad(d=>d.ui.card.pieces.push({type:'buttons',items:[{label:'Save',do:{record:'notes',row:{total:1}}}]})),/unknown column/);
  assert.throws(bad(d=>d.ui.card.sources.w={call:{method:'POST',path:'/v1/ocr'}}),/GET only/);
});
test('card configuration cannot close its script or accept path injection',()=>{
  const r=structuredClone(data.recipes[0]);r.title='</script><img src=x>';
  const html=renderCard(template,r);assert.ok(html.includes('\\u003c/script>'));
  assert.throws(()=>renderCard(template,r,{resource_id:'wfoo/../delete'}),/invalid watch/);
});

test('missing transcripts and failed latest observations do not become ready findings',async()=>{
  const noSpeech=card(data.recipes[2],{reply:{ok:true,status:200,body:{transcript:null,on_device:true}}});await noSpeech.context.refresh();assert.equal(noSpeech.document.body.dataset.state,'empty');assert.equal(noSpeech.el('answer').hidden,true);
  const failure=card(data.recipes[3],{reply:{ok:true,status:200,body:{last_check:{at:new Date().toISOString(),error:'no_photo',message:'Camera unavailable'},checks:4}}});await failure.context.refresh();assert.equal(failure.document.body.dataset.state,'error');assert.equal(failure.el('message').textContent,'Camera unavailable');
});

test('compact applet guide and app reference share one authored source',async()=>{
  assert.equal(await read('../app/src/main/java/cc/bridgit/app/api/AppletBuildGuide.kt'),await appletGuideKotlin(root));
  const page=await read('public/recipes/build-an-applet/index.html');
  const fragment=await read('content/builder/applet-guide.html');
  assert.ok(page.includes(fragment));
  assert.ok(page.includes('https://bridgit.cc/learn/catalog-index.json'));
  assert.ok(!page.includes('All endpoints'));
  assert.ok(Buffer.byteLength(page)<20*1024,'compact entry stays below 20KiB');
});


test('compact quickstart is generated from one source and grounds handoffs in this device', async () => {
  const files = await buildRecipeFiles(root);
  const brief = await read('content/builder/quickstart.md');
  assert.equal(files['learn/builder/quickstart.md'], brief);
  assert.ok(brief.split(/\s+/).length < 850, 'the agent entry point stays compact');
  for (const text of ['/v2/capabilities', 'device_id', 'Add control agent', '/v1/applets/catalog', '/v1/capabilities', '/v1/services', '/v1/applets/validate', '/v1/applets/install', 'Idempotency-Key']) assert.ok(brief.includes(text), text);
  assert.ok(files['recipes/build-an-applet/index.html'].includes('/learn/builder/quickstart.md'));
});
