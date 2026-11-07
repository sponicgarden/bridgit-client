// Pure, bounded contract evaluator. This module is copied to the registry unchanged.
export const CONTRACT = 'bridgit-practices/1';
export const CATALOG_VERSION = '1.0.0';
export const GUIDE = 'https://bridgit.cc/learn/builder/applet-practices.md';
const object = x => x !== null && typeof x === 'object' && !Array.isArray(x);
export const canonical = x => Array.isArray(x) ? '['+x.map(canonical).join(',')+']' : object(x) ? '{'+Object.keys(x).sort().map(k=>JSON.stringify(k)+':'+canonical(x[k])).join(',')+'}' : JSON.stringify(x);
const text = (x,max=2000) => typeof x === 'string' && x.trim().length > 0 && x.length <= max;
const keys = (x,allowed) => object(x) && Object.keys(x).every(k=>allowed.includes(k));
export function atPointer(doc,path) {
 if(typeof path!=='string'||!/^\/(?:[^~]|~[01])*$/.test(path)||path.length>300||path.startsWith('/ui/practices')) throw Error('Exception target must be a package JSON pointer outside ui.practices.');
 let value=doc;
 for(const token of path.slice(1).split('/').map(k=>k.replace(/~1/g,'/').replace(/~0/g,'~'))) {
  if(value===null||typeof value!=='object'||Array.isArray(value)&&(!/^(0|[1-9]\d*)$/.test(token)||Number(token)>=value.length)||!Object.hasOwn(value,token)) throw Error('Exception target does not exist: '+path);
  value=value[token];
 }
 return value;
}
export function checkPracticeMetadata(d,catalog,previous=null) {
 const q=d?.ui?.practices; if(q===undefined) return;
 if(!keys(q,['contract','catalog','review','exceptions'])||q.contract!==CONTRACT||q.catalog!==CATALOG_VERSION) throw Error('Unsupported ui.practices contract/catalog. Read '+GUIDE);
 const review=q.review, ids=new Set(catalog.practices.map(p=>p.id));
 if(!keys(review,['decision','reason','practice_ids','proposal'])||!['no-new-practice','add','change'].includes(review.decision)||!text(review.reason)||!Array.isArray(review.practice_ids)||review.practice_ids.length<1||review.practice_ids.length>64||new Set(review.practice_ids).size!==review.practice_ids.length||review.practice_ids.some(id=>!ids.has(id))||review.decision!=='no-new-practice'&&!text(review.proposal)) throw Error('ui.practices.review needs a decision, reason, known practice_ids and a proposal for add/change.');
 const exceptions=Object.hasOwn(q,'exceptions')?q.exceptions:[];
 if(!Array.isArray(exceptions)||exceptions.length>32) throw Error('At most 32 practice exceptions.');
 const seen=new Set();
 for(const e of exceptions) {
  const p=catalog.practices.find(p=>p.id===e?.practice);
  if(!keys(e,['id','practice','target','reason','alternative','acceptance','review_on'])||!text(e.id,80)||seen.has(e.id)||!p||!p.exceptions_allowed||!text(e.reason)||!text(e.alternative)||!text(e.review_on,200)||!Array.isArray(e.acceptance)||e.acceptance.length<1||e.acceptance.length>20||e.acceptance.some(a=>!text(a,1000))) throw Error('Invalid, duplicate or non-waivable practice exception.');
  seen.add(e.id);const target=atPointer(d,e.target);
  const old=previous?.ui?.practices?.exceptions?.find(x=>x.id===e.id);
  if(old&&(canonical(old)!==canonical(e)||canonical(atPointer(previous,old.target))!==canonical(target))) throw Error('Changed exception '+e.id+' needs a new id and review.');
 }
}
export function practiceSurfaces(d) {
 const card=d.ui?.card, html=Object.values(d.assets??{}).filter(a=>a?.media_type==='text/html').map(a=>a.text??'').join('\n');
 const pieces=JSON.stringify(card?.pieces??[]), processing=JSON.stringify(d.processing??{});
 const ui=!!card||!!d.ui?.portal;
 return {all:true,ui,tables:/"type":"table"/.test(pieces)||/<table\b/i.test(html)||!!d.ui?.portal?.tables?.length,
 media:/"type":"(?:gallery|photo|video)"/.test(pieces)||/<(?:img|video)\b/i.test(html)||/"type":"photo"/.test(processing),
 capture:/camera|capture|photo|video/.test(processing+pieces+html),async:!!d.processing?.workflow||!!d.processing?.flows?.length||!!d.processing?.watches?.length||/Bridgit\.act/.test(html),
 data:!!d.processing?.tables?.length,editable:Object.keys(d.config_schema?.properties??{}).length>0||!!d.processing?.tables?.length,upgrade:true};
}
// Locate opt-outs in their actual package nodes, not by searching serialized text.
export function hasOffAttribute(html,name) {
 const decoded=value=>value.replace(/&#(?:x([0-9a-f]+)|([0-9]+));?/gi,(_,hex,dec)=>{const n=parseInt(hex??dec,hex?16:10);return n>0&&n<=0x10ffff?String.fromCodePoint(n):'�';}).replace(/&(Tab|NewLine|quot|apos|amp);/g,(_,name)=>({Tab:'\t',NewLine:'\n',quot:'"',apos:"'",amp:'&'}[name]));
 const pattern=new RegExp('(?:\\s|^)'+name+'\\s*=\\s*(?:"([^"]*)"|\'([^\']*)\'|([^\\s>]+))','gi');
 return [...html.replace(/<!--[\s\S]*?-->/g,'').matchAll(pattern)].some(m=>decoded(m[1]??m[2]??m[3])==='off');
}
export function practiceOptOuts(d) {
 const out=[];
 const walk=(v,path)=>{
  if(!v||typeof v!=='object')return;
  if(object(v)&&v.type==='table'&&/^\/ui\/card\/pieces\/\d+$/.test(path)&&v.sort===false)out.push({practice:'TABLE-001',target:path});
  if(path.startsWith('/assets/')&&object(v)&&v.media_type==='text/html'&&typeof v.text==='string'){
   if(d.ui?.card?.asset===path.slice('/assets/'.length).replace(/~1/g,'/').replace(/~0/g,'~')&&!/data-bridgit-ui\b/.test(v.text))out.push({practice:'UI-001',target:path});
   if(hasOffAttribute(v.text,'data-bridgit-sort'))out.push({practice:'TABLE-001',target:path});
   if(hasOffAttribute(v.text,'data-bridgit-viewer'))out.push({practice:'MEDIA-001',target:path});
  }
  for(const [k,x] of Object.entries(v)){if(path==='/ui'&&k==='practices')continue;walk(x,path+'/'+k.replace(/~/g,'~0').replace(/\//g,'~1'));}
 };
 walk(d,'');return out;
}
export function assessPractices(d,catalog,previous=null) {
 checkPracticeMetadata(d,catalog,previous);
 const q=d.ui?.practices, surfaces=practiceSurfaces(d), results=[];
 const optouts=practiceOptOuts(d), oldOptouts=previous?practiceOptOuts(previous):[];
 for(const p of catalog.practices) {
  const applicable=surfaces[p.applicability]===true;
  const exceptions=(q?.exceptions??[]).filter(e=>e.practice===p.id);
  let status=!applicable?'not-applicable':p.enforcement==='review'?'unverified':'runtime-default';
  if(p.id==='GOV-001') status=q?'structural-pass':'fail';
  if(p.id==='GOV-002') status='structural-pass';
  const targets=optouts.filter(o=>o.practice===p.id).map(o=>o.target);
  const unexcepted=targets.filter(target=>!exceptions.some(e=>e.target===target));
  const inherited=target=>oldOptouts.some(o=>o.practice===p.id&&o.target===target)&&canonical(atPointer(previous,target))===canonical(atPointer(d,target));
  if(applicable&&targets.length)status=unexcepted.length?(previous&&unexcepted.every(inherited)?'legacy-debt':'fail'):'proposed-exception';
  results.push({practice:p.id,revision:p.revision,applicable,status,checks:p.checks,targets,exceptions:exceptions.map(e=>e.id)});
 }
 return {contract:CONTRACT,catalog:CATALOG_VERSION,catalog_source:catalog.format,guide:GUIDE,
  valid:!results.some(r=>r.status==='fail'),behavior_verified:false,verification:'Structural results and runtime defaults are not behavioral or device test passes.',results};
}
export function requirePracticeChange(d,catalog,previous=null) {
 const report=assessPractices(d,catalog,previous);
 if(!report.valid) {const error=Error('Applet practice review required: '+report.results.filter(r=>r.status==='fail').map(r=>r.practice).join(', ')+'. '+GUIDE);error.report=report;throw error;}
 return report;
}
