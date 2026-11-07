// Turns each builder recipe into an installable applet package: a bridgit-template/1 definition with
// runtime.adapter android.package.v1, the format the phone installs from one file or Import link.
// Blanks, card pieces, tables and flows come from content/builder/applet-packages.json; the watch and schedule come
// from the recipe's start request. `set` replaces values in the start request for the package only (for example a
// rule's do that starts a package flow, which a plain watch can't name).
import { createHash } from 'node:crypto';

export const ADAPTER = 'android.package.v1';
export const sha256 = text => createHash('sha256').update(text, 'utf8').digest('hex');
const clone = v => JSON.parse(JSON.stringify(v));

// RFC 6901 pointer helpers over the recipe's start request.
const parts = p => p.split('/').slice(1).map(s => s.replace(/~1/g, '/').replace(/~0/g, '~'));
function setAt(obj, pointer, value) {
  const keys = parts(pointer); let o = obj;
  for (const k of keys.slice(0, -1)) { if (!(k in o)) throw Error(`No ${pointer}`); o = o[k]; }
  const last = keys.at(-1); if (!(last in o)) throw Error(`No ${pointer}`); o[last] = value;
}
function getAt(obj, pointer) { let o = obj; for (const k of parts(pointer)) { if (o == null || !(k in o)) throw Error(`No ${pointer}`); o = o[k]; } return o; }
function dropAt(obj, pointer) { const keys = parts(pointer); let o = obj; for (const k of keys.slice(0, -1)) o = o[k]; delete o[keys.at(-1)]; }

/** Every phone call a pieces card makes: its call sources and its API buttons (method + path). */
export function cardCalls(card) {
  const calls = Object.values(card?.sources ?? {}).filter(src => src.call).map(src => src.call);
  for (const piece of card?.pieces ?? []) if (piece.type === 'buttons') for (const b of piece.items) if (b.do?.method) calls.push(b.do);
  return calls;
}

// The scope each flow step type needs (the phone's FlowSteps table); inline-endpoint steps need the endpoint's.
const STEP_SCOPES = { photo: 'camera.snapshot', ask_model: 'decisions', appraise: 'decisions', notice: 'interactions', record: null };

/** Every permission the package's parts use: watch data, schedule, card, flows, and each phone call the card or rules make. */
function permissionsFor(watchBody, schedule, card, flows, openapi, routeOf) {
  const out = new Set(['interactions']);
  if (watchBody) { out.add('watches'); for (const d of watchBody.data ?? []) if (d === 'camera') out.add('camera'); else if (d === 'sound') out.add('sounds'); }
  if (schedule) out.add('schedule');
  const tag = (method, path) => { const route = routeOf(method, path); return route && openapi.paths[route]?.[method.toLowerCase()]?.tags?.[0]; };
  for (const c of cardCalls(card)) {
    out.add(tag(c.method, c.path));
    if (c.method === 'POST' && c.path === '/v1/ocr' && Object.hasOwn(c.body ?? {}, 'gallery_id')) out.add('gallery');
  }
  for (const rule of [...(watchBody?.rules ?? []), ...(watchBody?.tally?.rules ?? [])]) if (rule.do.method) out.add(tag(rule.do.method, rule.do.path));
  if (flows?.length) out.add('workflows');
  for (const f of flows ?? []) for (const step of f.steps) { const scope = STEP_SCOPES[step.type]; if (!Object.hasOwn(STEP_SCOPES,step.type)) throw Error(`flow ${f.id}: no scope known for step ${step.type}`); out.add(scope); }
  for (const a of schedule?.manifest?.actions ?? []) { const [m, p] = a.do.call.split(' '); out.add(tag(m, p)); }
  return [...out].filter(Boolean).sort();
}

/**
 * The package for one recipe, with a card made of pieces (docs/applet-cards.md). [spec] is the recipe's entry in
 * content/builder/applet-packages.json and [routeOf] maps a method and path to its OpenAPI route (or null).
 * Authored migration, budget and data policy travel with the edition. The registry validator supplies
 * the remaining envelope defaults when a recipe is published.
 */
export function buildPackage(recipe, spec, openapi, routeOf) {
  const start = recipe.examples.find(e => e.id === 'start' && e.request.method === 'POST' && e.request.path === '/v1/watches');
  const watchBody = start ? clone(start.request.body) : null;
  const scheduleBody = recipe.start_request?.path === '/v1/schedule' ? clone(recipe.start_request.body) : null;
  const target = watchBody ?? scheduleBody ?? {};
  for (const p of spec.drop ?? []) dropAt(target, p);
  for (const { path: p, value } of spec.set ?? []) setAt(target, p, clone(value));
  const properties = {}, defaults = {}, inputs = {}, required = [];
  for (const [name, b] of Object.entries(spec.blanks)) {
    properties[name] = b.schema; inputs[name] = { label: b.label, help: b.help };
    if (!b.optional) { required.push(name); defaults[name] = b.default; }
    for (const p of b.paths) {
      const was = getAt(target, p);
      if (typeof was !== typeof b.default) throw Error(`${recipe.id}: blank ${name} at ${p} changes type`);
      setAt(target, p, { $input: name });
    }
  }
  const card = { title: recipe.title, ...clone(spec.card) };
  return {
    format: 'bridgit-template/1', id: recipe.id, version: spec.version, kind: 'applet', name: recipe.title, summary: spec.summary ?? recipe.summary,
    runtime: { adapter: spec.adapter ?? ADAPTER, applet_key: recipe.id, supports_dynamic_execution: false },
    config_schema: { type: 'object', additionalProperties: false, required, properties },
    defaults,
    processing: {
      ...(watchBody ? { watches: [{ id: 'main', request: watchBody }] } : {}),
      ...(scheduleBody ? { schedule: scheduleBody } : {}),
      ...(spec.flows ? { flows: clone(spec.flows) } : {}),
      ...(spec.tables ? { tables: clone(spec.tables) } : {})
    },
    permissions: { required: permissionsFor(watchBody, scheduleBody, card, spec.flows, openapi, routeOf), optional: [], remote: clone(spec.remote ?? []), automatic_grants: false },
    ui: { inputs, card },
    ...(spec.migration ? { migration: clone(spec.migration) } : {}),
    ...(spec.budget ? { budget: clone(spec.budget) } : {}),
    ...(spec.data_policy ? { data_policy: clone(spec.data_policy) } : {})
  };
}

/** The Import link for a static package file: the phone checks the file's SHA-256 before showing the review. */
export const importLink = (path, text) => `https://bridgit.cc/install/?src=${encodeURIComponent(path)}&sha256=${sha256(text)}`;
export const appLink = (path, text) => `bridgit://install?src=${encodeURIComponent(path)}&sha256=${sha256(text)}`;

/** Mirrors the phone's AppletPackage.check for what a build can know: shape, blanks and $input references. */
export function checkPackage(d) {
  if (d.format !== 'bridgit-template/1' || d.kind !== 'applet' || ![ADAPTER, 'bridgit.package.v1', 'android.dog.v1'].includes(d.runtime?.adapter) || d.runtime.supports_dynamic_execution !== false) throw Error(`${d.id}: not an installable package`);
  if (d.permissions?.automatic_grants !== false || !Array.isArray(d.permissions.required)) throw Error(`${d.id}: permissions must be explicit`);
  const blanks = new Set(Object.keys(d.config_schema?.properties ?? {}));
  const walk = v => { if (Array.isArray(v)) v.forEach(walk); else if (v && typeof v === 'object') { if ('$input' in v) { if (!blanks.has(v.$input) || Object.keys(v).length !== 1) throw Error(`${d.id}: bad $input ${v.$input}`); return; } Object.values(v).forEach(walk); } };
  walk(d.processing); walk(d.ui?.card);
  for (const [name, a] of Object.entries(d.assets ?? {})) if (sha256(a.text) !== a.sha256) throw Error(`${d.id}: asset ${name} hash`);
  if (d.ui?.card) checkCard(d);
  if (Buffer.byteLength(JSON.stringify(d)) > 65536) throw Error(`${d.id}: package over 64 KB`);
  return d;
}

export const PIECES = ['header', 'status', 'text', 'note', 'metrics', 'gallery', 'video', 'table', 'timeline', 'people', 'devices', 'draft', 'buttons'];
const SOURCE_KINDS = ['watch', 'runs', 'table', 'photos', 'call', 'reply', 'batch'];
const COLUMN_TYPES = ['text', 'number', 'integer', 'boolean', 'time', 'date', 'choice', 'photo'];
const ID = /^[a-z][a-z0-9_]{0,31}$/;

/**
 * What a build can check of a card and its tables (docs/applet-cards.md): pieces xor asset, known piece and source
 * kinds, every $source / $draft naming a declared source or draft, record buttons naming a declared table and
 * column, and the 16 KB cap. The phone's own validator is the contract; this keeps the recipes honest.
 */
export function checkCard(d) {
  const c = d.ui.card, fail = m => { throw Error(`${d.id}: ${m}`); };
  if (!c.title) fail('ui.card.title is required');
  if (('asset' in c) === ('pieces' in c)) fail('ui.card needs exactly one of asset or pieces');
  const tables = new Map((d.processing?.tables ?? []).map(t => [t.id, t]));
  if (tables.size > 3) fail('at most 3 tables');
  for (const t of tables.values()) {
    if (!ID.test(t.id) || !t.label) fail(`table ${t.id}: id and label`);
    const cols = Object.entries(t.columns ?? {});
    if (cols.length < 1 || cols.length > 16) fail(`table ${t.id}: 1-16 columns`);
    for (const [k, col] of cols) if (!ID.test(k) || !COLUMN_TYPES.includes(col.type)) fail(`table ${t.id}: column ${k}`);
  }
  if ('asset' in c) return;
  if (Buffer.byteLength(JSON.stringify(c)) > 16384) fail('ui.card over 16 KB');
  if (!Array.isArray(c.pieces) || c.pieces.length < 1 || c.pieces.length > 24) fail('ui.card.pieces: 1-24 pieces');
  if ('data' in c) fail('ui.card.data belongs to asset cards');
  if (c.fresh_s !== undefined && !(Number.isInteger(c.fresh_s) && c.fresh_s >= 30 && c.fresh_s <= 86400)) fail('fresh_s: 30-86400');
  const sources = Object.entries(c.sources ?? {});
  if (sources.length > 8) fail('at most 8 sources');
  const watches = new Set((d.processing?.watches ?? []).map(w => w.id));
  const flows = new Set((d.processing?.flows ?? []).map(f => f.id));
  for (const [name, src] of sources) {
    const kinds = SOURCE_KINDS.filter(k => k in src);
    if (!ID.test(name) || kinds.length !== 1) fail(`source ${name}`);
    if (src.watch && (!watches.has(src.watch.$watch) || !['tally', 'status'].includes(src.read))) fail(`source ${name}: watch`);
    if (src.table && !tables.has(src.table)) fail(`source ${name}: unknown table ${src.table}`);
    if (src.call && src.call.method !== 'GET') fail(`source ${name}: calls are GET only`);
    if (src.every_s !== undefined && !(src.call && src.every_s >= 60 && src.every_s <= 3600)) fail(`source ${name}: every_s`);
  }
  const names = new Set(sources.map(([n]) => n)), drafts = new Set();
  for (const p of c.pieces) {
    if (!PIECES.includes(p.type)) fail(`unknown piece ${p.type}`);
    if (p.type === 'draft') drafts.add(p.id);
    if (p.type === 'buttons') {
      if (!(p.items?.length >= 1 && p.items.length <= 4)) fail('buttons: 1-4 items');
      for (const b of p.items) {
        if (!b.label || b.label.length > 24) fail(`button label ${b.label}`);
        if (b.into) names.add(b.into);
        const does = ['refresh', 'pause_applet', 'run_workflow', 'record', 'method', 'flow', 'step', 'camera'].filter(k => k in (b.do ?? {}));
        if (does.length !== 1) fail(`button ${b.label}: do needs exactly one action`);
        if (b.do.flow && !flows.has(b.do.flow.$flow)) fail(`button ${b.label}: ${JSON.stringify(b.do.flow)} names no package flow`);
        if (b.do.record) {
          const t = tables.get(b.do.record); if (!t) fail(`button ${b.label}: unknown table ${b.do.record}`);
          for (const k of Object.keys(b.do.row ?? {})) if (!(k in t.columns)) fail(`button ${b.label}: unknown column ${k}`);
        }
      }
    }
    if (p.type === 'metrics' && !(p.items?.length >= 1 && p.items.length <= 6)) fail('metrics: 1-6 items');
  }
  const refs = v => { if (Array.isArray(v)) v.forEach(refs); else if (v && typeof v === 'object') {
    if ('$source' in v && !names.has(String(v.$source).split('.')[0])) fail(`$source ${v.$source} names no source`);
    if ('$draft' in v && !drafts.has(v.$draft)) fail(`$draft ${v.$draft} names no draft`);
    Object.values(v).forEach(refs); } };
  refs(c.pieces);
}
