import {generatePracticeRuntime} from './generate-practice-runtime.mjs';
// Composition metadata only. Operation schemas remain Endpoints/OpenAPI/ToolSchemas.
import {practiceCatalog} from './practices.mjs';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildPackage, checkPackage, cardCalls, importLink, appLink } from './applet-packages.mjs';
const expectedIds = ['pet-monitor','receipt-scanner','voice-notebook','room-change-watcher','daily-briefing-panel','external-device-dashboard','situation-counter'];
const escape = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const json = value => JSON.stringify(value, null, 2) + '\n';
export function renderCard(template, recipe, config = {}) {
  const data = { ...recipe.card, id: recipe.id, title: recipe.title, summary: recipe.card.owner_intro, boundary: recipe.card.owner_note, resource_id: null, ...config };
  if (data.resource_id !== null && !/^w[a-zA-Z0-9_-]{1,80}$/.test(data.resource_id)) throw Error('invalid watch resource_id');
  return template.replace(/\/\*RECIPE\*\/[\s\S]*?\/\*END RECIPE\*\//, () => `/*RECIPE*/${JSON.stringify(data).replace(/</g, '\\u003c')}/*END RECIPE*/`);
}
function routeOf(openapi, method, p) {
  for (const [route, methods] of Object.entries(openapi.paths)) {
    const pattern = '^' + route.replace(/[.*+?^$()|[\]\\]/g,'\\$&').replace(/\{[^}]+\}/g,'[^/]+') + '$';
    if (new RegExp(pattern).test(p) && methods[method.toLowerCase()]) return route;
  }
  return null;
}
function operation(openapi, request) {
  for (const [route, methods] of Object.entries(openapi.paths)) {
    const pattern = '^' + route.replace(/[.*+?^$()|[\]\\]/g,'\\$&').replace(/\{[^}]+\}/g,'[^/]+') + '$';
    if (new RegExp(pattern).test(request.path) && methods[request.method.toLowerCase()]) return {route, spec: methods[request.method.toLowerCase()]};
  }
  throw Error(`Unknown operation ${request.method} ${request.path}`);
}
// Same naming as CapabilityCatalog.operationUrl, so recipes link the small per-operation file, not the bulk OpenAPI.
export const operationUrl=(method,route)=>'https://bridgit.cc/learn/operations/'+(method.toLowerCase()+'-'+route).replace(/[^a-zA-Z0-9]+/g,'-').replace(/^-+|-+$/g,'')+'.json';
export function validateRequest(openapi, request) {
  const {route,spec} = operation(openapi, request);
  const schema=spec.requestBody?.content?.['application/json']?.schema;
  if (schema) {
    for (const name of schema.required ?? []) if (!(name in (request.body??{}))) throw Error(`${route}: missing ${name}`);
    for (const [name,value] of Object.entries(request.body??{})) {
      const p=schema.properties[name]; if (!p) throw Error(`${route}: unknown ${name}`);
      const types=Array.isArray(p.type)?p.type:[p.type];
      const matches=t=>t==='integer'?Number.isInteger(value):t==='array'?Array.isArray(value):t==='object'?value!==null&&typeof value==='object'&&!Array.isArray(value):typeof value===t;
      if (!types.some(matches) || p.enum && !p.enum.includes(value)) throw Error(`${route}: invalid ${name}`);
    }
  }
  for (const name of Object.keys(request.query??{})) if (!spec.parameters?.some(p=>p.in==='query'&&p.name===name)) throw Error(`${route}: unknown query ${name}`);
  return {method:request.method,path:route,documentation:operationUrl(request.method,route)};
}
export function validateRecipes(data, openapi) {
  if (data.documentation_revision!==1 || !Number.isInteger(data.api_revision)) throw Error('Invalid recipe revision');
  if (JSON.stringify(data.recipes.map(r=>r.id))!==JSON.stringify(expectedIds)) throw Error('Expected seven stable recipe ids');
  for (const r of data.recipes) {
    if (!r.capability_ids.every(id=>/^v[12]\.[a-z0-9_.]+$/.test(id))) throw Error(`Invalid capability id: ${r.id}`);
    for (const phase of ['start','update','pause','stop']) if (!r.lifecycle[phase]) throw Error(`Missing ${phase}: ${r.id}`);
    for (const e of r.examples) {
      validateRequest(openapi,e.request);
      if (!Number.isInteger(e.response.status)||!e.response.body||!e.evidence.startsWith('Synthetic')) throw Error(`Invalid example: ${r.id}`);
    }
    if (r.start_request) {
      validateRequest(openapi,r.start_request);
      for (const a of r.start_request.body.manifest.actions) {const [method,p]=a.do.call.split(' ');validateRequest(openapi,{method,path:p,body:a.do.body});}
    }
    if (r.card.mode==='watch') {
      const b=r.examples.find(e=>e.id==='start').request.body;
      // A situation fills in a tally on the phone (WatchRules: a camera tally may check every 10 s with the change check on) and defaults to Clef.
      const tally=b.tally||b.situation, fast=tally&&(b.local?.change_check??true), paid=b.model==='clef'||b.model==='jev'||!b.model&&b.situation;
      if (b.interval_s<(fast?10:30)||b.interval_s>3600||b.expires_in_s>(tally?7776000:86400)||b.local?.max_reuse_s>3600||paid&&!(b.max_paid_per_hour>=1&&b.max_paid_per_hour<=240)) throw Error(`Unbounded watch ${r.id}`);
      if(b.tally && (!b.questions[b.tally.track]||b.tally.labels.some(l=>!Object.hasOwn(b.questions[b.tally.track].criteria,l)))) throw Error('Tally labels differ from questions');
    }
  }
}
// A static look-alike of the card the phone draws from ui.card pieces (docs/applet-cards.md). It is a preview for
// recipe pages, not the contract: sample values stand in for every $source, and buttons are inert.
const SAMPLE = { text: 'Sample', number: '12', minutes: '1 h 20 min', duration_s: '45 s', time: '5 Nov, 14:05', ago: '3 min ago', date: '5 Nov 2026', percent: '40%', celsius: '12 °C', money: '12.50', yes_no: 'Yes' };
const isRef = v => v !== null && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === 1 && ['$source', '$input', '$draft', '$watch'].includes(Object.keys(v)[0]);
function sample(value, format, words) {
  if (value === undefined || value === null) return '—';
  if (isRef(value)) return format === 'words' && words ? Object.values(words)[0] : SAMPLE[format ?? 'text'] ?? 'Sample';
  return String(value);
}
const words = v => Array.isArray(v) ? v.map(p => typeof p === 'string' ? p : '…').join('') : isRef(v) ? 'Sample text' : v ?? '';
const unit = (v, u) => u ? `${v} ${u}` : v;
function piecePreview(p, card) {
  const e = escape;
  switch (p.type) {
    case 'header': return `<header class="cp-head">${p.eyebrow ? `<p class="cp-eyebrow">${e(words(p.eyebrow))}</p>` : ''}<h3>${e(words(p.title ?? card.title))}</h3>${p.text ? `<p class="cp-muted">${e(words(p.text))}</p>` : ''}</header>`;
    case 'status': return `<p class="cp-status"><span class="cp-pill cp-${e(p.tone ?? 'info')}">${e(p.tone ?? 'info')}</span> ${e(words(p.text))}</p>`;
    case 'text': return `<p>${e(words(p.text))}</p>`;
    case 'note': return `<p class="cp-note">${e(words(p.text))}</p>`;
    case 'metrics': return `<div class="cp-tiles">${p.items.map(i => `<div class="cp-tile"><strong>${e(unit(sample(i.value, i.format), i.unit))}</strong><small>${e(words(i.label))}</small></div>`).join('')}</div>`;
    case 'table': return `<table class="cp-table"><thead><tr>${p.columns.map(c => `<th>${e(words(c.label ?? c.key))}</th>`).join('')}</tr></thead><tbody>${[1, 2].map(n => `<tr>${p.columns.map(c => `<td>${e(c.key === '$key' ? `Sample ${n}` : SAMPLE[c.format ?? 'text'] ?? 'Sample')}</td>`).join('')}</tr>`).join('')}</tbody></table>${p.owner_delete ? '<p class="cp-note">Long press a row to delete it.</p>' : ''}`;
    case 'timeline': return `<ul class="cp-list">${[1, 2].map(() => `<li><span>${e(p.label_words ? Object.values(p.label_words)[0] : 'Sample')}${p.detail ? ` <small>· ${e(SAMPLE.number)}</small>` : ''}</span><span class="cp-muted">${e(SAMPLE[p.time_format ?? 'ago'])}</span></li>`).join('')}</ul>`;
    case 'people': return `<ul class="cp-list"><li><span><b class="cp-badge">S</b> Sample person</span><span class="cp-muted">${p.status ? 'Sample status' : ''}</span></li></ul>`;
    case 'devices': return `<ul class="cp-list"><li><span><b class="cp-dot"></b> Sample device <small>· sample kind</small></span><span class="cp-muted">Not checked</span></li></ul>`;
    case 'gallery': return `<div class="cp-gallery">${[1, 2, 3].map(() => '<span></span>').join('')}</div>`;
    case 'video': return '<div class="cp-video">▶</div>';
    case 'draft': return `<label class="cp-draft">${e(words(p.label ?? 'Draft'))}<span class="cp-box">${e(isRef(p.value) ? 'Text from the phone appears here for you to check and edit.' : words(p.value))}</span></label>`;
    case 'buttons': return `<div class="cp-buttons">${p.items.map(b => `<span class="cp-button${b.style === 'primary' ? ' cp-primary' : b.style === 'danger' ? ' cp-danger' : ''}" aria-disabled="true">${e(b.label)}</span>`).join('')}</div>`;
    default: return '';
  }
}
export function cardPreview(card) {
  if (!card.pieces) return '<p class="cp-note">This applet uses a custom HTML card.</p>';
  return `<div class="cp-card" role="figure" aria-label="Preview of the ${escape(card.title)} card"><span class="cp-label">Preview</span>${card.pieces.map(p => piecePreview(p, card)).join('')}</div>`;
}
const PREVIEW_CSS = '.cp-card{position:relative;max-width:420px;border:1px solid #e6dbe9;border-radius:20px;background:#fff;padding:18px;margin:14px 0;box-shadow:0 8px 24px #2b183408;color:#2b1834;font:15px/1.5 system-ui,sans-serif}.cp-card>*{margin:0 0 12px}.cp-card>*:last-child{margin-bottom:0}.cp-label{position:absolute;top:12px;right:14px;font-size:11px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:#6f5f75;background:#fff8ed;border:1px dashed #e6dbe9;border-radius:99px;padding:2px 8px}.cp-head h3{font:600 22px/1.2 Georgia,serif;margin:4px 0 6px}.cp-eyebrow{font-size:11px;letter-spacing:.12em;text-transform:uppercase;font-weight:700;color:#704394;margin:0}.cp-muted{color:#6f5f75}.cp-head .cp-muted{font-size:14px;margin:0}.cp-note{color:#6f5f75;font-size:12px}.cp-pill{display:inline-block;border-radius:99px;padding:3px 10px;background:#efe4f7;color:#704394;font-size:12px;font-weight:700}.cp-ok{background:#e2f3e8;color:#235c36}.cp-warn{background:#fff0c7;color:#785513}.cp-bad{background:#fde9df;color:#a23520}.cp-tiles{display:grid;grid-template-columns:1fr 1fr;gap:10px}.cp-tile{background:#efe4f7;border-radius:12px;padding:12px}.cp-tile strong,.cp-tile small{display:block}.cp-tile strong{font-size:18px}.cp-tile small{font-size:11px;color:#6f5f75;margin-top:4px}.cp-table{width:100%;border-collapse:collapse;font-size:13px}.cp-table th{text-align:left;color:#6f5f75;font-weight:600;border-bottom:1px solid #e6dbe9;padding:6px 4px}.cp-table td{border-bottom:1px solid #e6dbe9;padding:8px 4px}.cp-list{list-style:none;padding:0}.cp-list li{display:flex;justify-content:space-between;gap:10px;border-bottom:1px solid #e6dbe9;padding:9px 0;font-size:14px}.cp-badge{display:inline-grid;place-items:center;width:22px;height:22px;border-radius:50%;background:#efe4f7;color:#704394;font-size:12px}.cp-dot{display:inline-block;width:9px;height:9px;border-radius:50%;background:#b9adbf}.cp-gallery{display:grid;grid-template-columns:repeat(3,1fr);gap:6px}.cp-gallery span{aspect-ratio:1;border-radius:10px;background:#efe4f7}.cp-video{display:grid;place-items:center;aspect-ratio:16/9;border-radius:12px;background:#2b1834;color:#fff;font-size:28px}.cp-draft{display:block;font-size:13px;color:#6f5f75}.cp-box{display:block;margin-top:6px;min-height:72px;border:1px solid #e6dbe9;border-radius:12px;background:#fff8ed;padding:10px;color:#6f5f75;font-style:italic}.cp-buttons{display:grid;grid-template-columns:1fr 1fr;gap:9px}.cp-button{display:block;text-align:center;border:1px solid #e6dbe9;border-radius:12px;background:#fff;font:600 14px system-ui;padding:12px 8px;cursor:default}.cp-primary{grid-column:1/-1;background:#2b1834;color:#fff}.cp-danger{color:#a23520}';
/** A stand-in request for checking a card call against OpenAPI: each $input becomes a sample of its type. */
function sampleRequest(call) {
  const fill = v => Array.isArray(v) ? v.map(fill) : v && typeof v === 'object' ? ('$input' in v ? 1 : Object.fromEntries(Object.entries(v).map(([k, x]) => [k, fill(x)]))) : v;
  return { method: call.method, path: call.path, ...(call.query ? { query: call.query } : {}), ...(call.body ? { body: fill(call.body) } : {}) };
}
function bullets(items) { return `<ul>${items.map(i=>`<li>${escape(i)}</li>`).join('')}</ul>`; }
function page(title,body,css='') {
  return `<!doctype html>\n<html lang="en"><head><meta charset="utf-8"><meta name="robots" content="noindex,nofollow"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="description" content="Build small Bridgit applets from verified public contracts"><title>${escape(title)} · Bridgit</title><link rel="stylesheet" href="/assets/style.css"><link rel="stylesheet" href="/assets/brand.css"><style>main{max-width:940px;margin:50px auto;padding:0 22px}h1{font:600 42px Georgia,serif}h2{margin-top:34px}p,li{line-height:1.65}a{color:#704394}pre{background:#fff8ed;border:1px solid #e6dbe9;border-radius:14px;padding:18px;overflow:auto;max-height:540px}article,section.card{border:1px solid #e6dbe9;border-radius:18px;padding:22px;margin:18px 0;background:#fff8ed}.tag{color:#704394;font-size:13px}.install-cta{display:flex;gap:14px;align-items:center;flex-wrap:wrap}.install-button{background:#704394;color:#fff!important;text-decoration:none;font-weight:600;padding:11px 20px;border-radius:999px}nav{display:flex;gap:20px}${css}</style></head><body><div class="wrap"><nav aria-label="Main navigation">{{SITE_BRAND}}</nav><main><nav><a href="/recipes/">Recipes</a><a href="/learn/builder/recipes.md">Builder guide</a><a href="/learn/index.json">API reference</a></nav><h1>${escape(title)}</h1>${body}</main></div></body></html>\n`;
}
export async function buildRecipeFiles(root) {
  const [data,template,guide,openapi]=await Promise.all([
    readFile(path.join(root,'content/builder/recipes.json'),'utf8').then(JSON.parse),
    readFile(path.join(root,'content/builder/recipe-card.html'),'utf8'),
    readFile(path.join(root,'content/builder/guide.md'),'utf8'),
    readFile(path.join(root,'public/learn/openapi.json'),'utf8').then(JSON.parse)
  ]);
  const packages=JSON.parse(await readFile(path.join(root,'content/builder/applet-packages.json'),'utf8')).packages;
  if (JSON.stringify(Object.keys(packages))!==JSON.stringify(expectedIds)) throw Error('Every recipe needs an applet package entry');
  validateRecipes(data,openapi);
  const files={};
  const appletGuide=await readFile(path.join(root,'content/builder/applet-guide.html'),'utf8');
  files['recipes/build-an-applet/index.html']=page('Build an applet',appletGuide);
  for (const r of data.recipes) {
    for (const file of r.evidence) await readFile(path.join(process.env.BRIDGIT_PUBLIC_DIR||path.join(root,'..'),file));
    r.url=`https://bridgit.cc/recipes/${r.id}/`;
    r.card_request={method:'POST',path:'/v1/interactions',body:{kind:'html',title:r.title,html:renderCard(template,r),no_takeover:true,expires_in_seconds:86400}};
    validateRequest(openapi,r.card_request);
    r.operations=[...new Map([...r.examples.map(e=>e.request),...(r.start_request?[r.start_request]:[]),r.card_request,...(r.card.mode==='watch'?[{method:'GET',path:'/v1/watches/w0123456789ab'+r.card.read_suffix},{method:'POST',path:'/v1/watches/w0123456789ab/pause',body:{paused:true}}]:r.card.mode==='weather'?[{method:'GET',path:'/v1/weather',query:{hours:3}}]:[])].map(req=>{const op=validateRequest(openapi,req);return [op.method+' '+op.path,op]})).values()];
    const pkg=checkPackage(buildPackage(r,packages[r.id],openapi,(m,p)=>routeOf(openapi,m,p)));
    // Card calls are checked against OpenAPI like every other request; a $input value stands in as a sample.
    for (const c of cardCalls(pkg.ui.card)) validateRequest(openapi,sampleRequest(c));
    const pkgText=json(pkg), pkgPath=`/recipes/${r.id}/applet.json`;
    files[`recipes/${r.id}/applet.json`]=pkgText;
    r.package={url:'https://bridgit.cc'+pkgPath,version:pkg.version,sha256:importLink(pkgPath,pkgText).split('sha256=')[1],import_url:importLink(pkgPath,pkgText),app_link:appLink(pkgPath,pkgText),install_request:{method:'POST',path:'/v1/applets/install',body:{url:'https://bridgit.cc'+pkgPath}}};
    files[`recipes/${r.id}/request.json`]=json(r.card_request.body);
    files[`recipes/${r.id}/recipe.json`]=json(r);
    const evidence=r.evidence.map(f=>`<li><a href="https://github.com/sponicgarden/bridgit-client/blob/main/${escape(f)}">${escape(f)}</a></li>`).join('');
    files[`recipes/${r.id}/index.html`]=page(r.title,`<p>${escape(r.summary)}</p><p class="tag">Documentation revision ${data.documentation_revision} · Contract reference API ${data.api_revision} · Installed readiness unknown</p><p class="install-cta"><a class="install-button" href="${escape(r.package.import_url)}">Import to my phone</a> <span class="tag">One file, one approval on the phone: it sets up ${escape([r.examples.some(e=>e.id==='start'&&e.request.path==='/v1/watches')?'the watch':'',r.start_request?'the schedule':'','the card'].filter(Boolean).join(', '))} and keeps running with no agent. <a href="applet.json">Applet file</a></span></p><h2>The card</h2><p class="tag">A preview of the card the phone draws from this applet's <code>ui.card</code> pieces, with sample values. The buttons do nothing here.</p>${cardPreview(pkg.ui.card)}<p><a href="applet.json">Applet file</a> · <a href="/simulator/?card=recipe-${r.id}">Older HTML card in the card simulator</a> · <a href="request.json">Complete HTML interaction body</a> · <a href="recipe.json">Machine-readable recipe</a></p><h2>What runs where</h2>${bullets(r.execution.phone)}<h3>Controller integration</h3>${bullets(r.execution.controller)}<p>${escape(r.execution.server)}</p><h2>Boundaries</h2>${bullets(r.boundaries)}<h2>Lifecycle</h2>${Object.entries(r.lifecycle).map(([k,v])=>`<h3>${escape(k)}</h3><p>${escape(v)}</p>`).join('')}<h2>Failures and costs</h2>${bullets([...r.failure,...r.cost])}<h2>Complete synthetic contract examples</h2><p>These fixtures demonstrate contracts. They are not observations from a phone. Replace image/resource IDs with returned authorized IDs. Load <a href="request.json">request.json</a> as the complete body of POST /v1/interactions; it contains the full offline HTML.</p>${r.start_request?`<h3>Start request (wait for owner approval)</h3><pre>${escape(json(r.start_request))}</pre>`:''}${r.examples.map(e=>`<h3>${escape(e.id)}</h3><pre>${escape(json(e))}</pre>`).join('')}<h2>Extensions</h2>${bullets(r.extensions)}<h2>Source evidence</h2><ul>${evidence}</ul>`,PREVIEW_CSS);
  }
  files['learn/builder/applet-practices.md']=await readFile(path.join(root,'content/builder/applet-practices.md'),'utf8');
  files['learn/builder/applet-practices.mjs']=await readFile(path.join(root,'scripts/applet-practices.mjs'),'utf8');
  files['learn/builder/practice-review.md']=await readFile(path.join(root,'content/builder/practice-review.md'),'utf8');
  files['learn/builder/practices.json']=json(await practiceCatalog(root));
  files['learn/builder/recipes.json']=json(data);
  files['learn/builder/recipes.md']=guide;
  files['learn/builder/quickstart.md']=await readFile(path.join(root,'content/builder/quickstart.md'),'utf8');
  files['learn/builder/applet-packages.md']=await readFile(path.join(root,'content/builder/applet-packages.md'),'utf8');
  files['learn/builder/library-management.md']=await readFile(path.join(root,'content/builder/library-management.md'),'utf8');
  files['learn/builder/applet-interaction-standard.md']=await readFile(path.join(root,'content/builder/applet-interaction-standard.md'),'utf8');
  files['learn/builder/modifying-applets.md']=await readFile(path.join(root,'content/builder/modifying-applets.md'),'utf8');
  files['recipes/index.html']=page('Build with Bridgit',`<p>Seven small applets, each one file with a card the phone draws itself, plus API examples. Start from a goal; check the installed device’s guide and grants before running it. Nothing on this page grants access or starts a device action: Import opens a review on your phone, and nothing runs until you approve it there.</p><p>Documentation revision ${data.documentation_revision}; API contract reference ${data.api_revision}. Source, published, installed and ready are separate states. See the <a href="/learn/builder/recipes.md">native-versus-composable matrix and build guide</a>.</p>${data.recipes.map(r=>`<article><span class="tag">${escape(r.capability_ids.join(' · '))}</span><h2><a href="${r.url}">${escape(r.title)}</a></h2><p>${escape(r.summary)}</p><p>${escape(r.boundaries[0])}</p><p><a class="install-button" href="${escape(r.package.import_url)}">Import to my phone</a></p></article>`).join('')}`);
  return files;
}
// One authored HTML fragment powers the compact site page and the app's API reference.
export async function appletGuideKotlin(root) {
  const html=await readFile(path.join(root,'content/builder/applet-guide.html'),'utf8');
  if (html.includes('"""')) throw Error('Applet guide must not contain a Kotlin raw string delimiter');
  const escaped=html.replace(/\$/g, () => "${'$'}");
  return '// Generated by site-content/scripts/builder-recipes.mjs from content/builder/applet-guide.html.\npackage cc.bridgit.app.api\n\ninternal object AppletBuildGuide {\n    const val HTML = """' + escaped + '"""\n}\n';
}
export async function buildBuilderRecipes(root,out) {
  const files=await buildRecipeFiles(root);
  for (const [name,text] of Object.entries(files)) {await mkdir(path.dirname(path.join(out,name)),{recursive:true});await writeFile(path.join(out,name),text);}
  return {recipes:expectedIds.length,files:Object.keys(files).length};
}
if(process.argv[1]===fileURLToPath(import.meta.url)) {
  const root=fileURLToPath(new URL('../',import.meta.url));
  console.log(await buildBuilderRecipes(root,path.join(root,'public')));
  await writeFile(path.join(root,'../app/src/main/java/cc/bridgit/app/api/AppletBuildGuide.kt'),await appletGuideKotlin(root));
  await writeFile(path.join(root,'../app/src/main/assets/applet-ui.js'),await readFile(path.join(root,'public/shared/applet-ui.js'),'utf8'));
  await generatePracticeRuntime(root);
  const catalog=await practiceCatalog(root);
  await writeFile(path.join(root,'../app/src/main/java/cc/bridgit/app/applets/AppletPracticeCatalog.kt'), '// Generated by builder-recipes.mjs. Do not edit.\npackage cc.bridgit.app.applets\nimport org.json.JSONObject\nobject AppletPracticeCatalog { fun get() = JSONObject(DATA)\nprivate val DATA = \"\"\"'+JSON.stringify(catalog).replaceAll('$',"${'$'}")+'\"\"\"\n}\n');
}
