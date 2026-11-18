import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {practiceCatalog} from '../scripts/practices.mjs';
import {assessPractices,requirePracticeChange,checkPracticeMetadata,canonical} from '../scripts/applet-practices.mjs';
import {fileURLToPath} from 'node:url';
const catalog=await practiceCatalog(fileURLToPath(new URL('../',import.meta.url)));
const fixture=JSON.parse(await readFile(new URL('../content/builder/practice-fixtures.json',import.meta.url)));
for(const row of fixture.cases) test(row.name,()=>{
 if(row.error){assert.throws(()=>requirePracticeChange(row.candidate,catalog,row.previous),new RegExp(row.error));return;}
 const report=assessPractices(row.candidate,catalog,row.previous);
 assert.equal(report.valid,row.valid);
 for(const [id,status] of Object.entries(row.statuses??{}))assert.equal(report.results.find(r=>r.practice===id).status,status);
});
test('catalog preserves observed IDs and separates policy from device evidence',()=>{
 assert.equal(catalog.version,'1.0.0');assert.ok(catalog.practices.some(r=>r.id==='visible-review'));
 for(const p of catalog.practices){assert.ok(p.acceptance.length);assert.ok(p.checks.length);assert.ok(p.rationale);}
 assert.equal(catalog.practices.find(p=>p.id==='TABLE-001').device_verified,false);
});
test('unknown and oversized exception declarations cannot grant an opt-out',()=>{
 const d=structuredClone(fixture.cases[1].candidate);
 d.ui.practices.exceptions=[{id:'pretend',practice:'DATA-003',target:'/processing',reason:'x',alternative:'x',acceptance:['x'],review_on:'next'}];
 assert.throws(()=>checkPracticeMetadata(d,catalog),/non-waivable/);
 d.ui.practices={contract:'bridgit-practices/999'};assert.throws(()=>checkPracticeMetadata(d,catalog),/Unsupported/);
});
test('canonical digests do not depend on JSON object key order',()=>assert.equal(canonical({b:2,a:[1]}),canonical({a:[1],b:2})));
test('UI-003: a pill group holds one type, checked by review and never reported as passed (A0405)',()=>{
 const p=catalog.practices.find(r=>r.id==='UI-003');
 assert.ok(p);assert.equal(p.enforcement,'review');assert.equal(p.applicability,'ui');assert.deepEqual(p.checks,['pill-group-type']);
 assert.ok(catalog.by_task.ui.includes('UI-003'));
 const ui=fixture.cases.find(c=>c.candidate?.ui&&!c.error);
 const r=assessPractices(ui.candidate,catalog,ui.previous).results.find(x=>x.practice==='UI-003');
 assert.ok(r);assert.notEqual(r.status,'pass');
});
