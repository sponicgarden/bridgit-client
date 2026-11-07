import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { buildSamplesFile, fillCard } from '../scripts/samples.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const read = path => readFile(new URL(path, import.meta.url), 'utf8');

test('public/samples/samples.json is up to date (run node scripts/samples.mjs)', async () => {
  assert.equal(await read('../public/samples/samples.json'), await buildSamplesFile(root));
});

test('every kind has a sample, and html samples carry a full page', async () => {
  const { samples } = JSON.parse(await read('../public/samples/samples.json'));
  assert.deepEqual(new Set(samples.map(s => s.kind)), new Set(['notice', 'question', 'html', 'text', 'pieces']));
  for (const s of samples.filter(s => s.kind === 'html')) {
    const html = s.request.html;
    assert.match(html, /^<!doctype html>/, s.id);
    assert.match(html, /respond\(JSON\.stringify\(/, `${s.id} answers with a JSON string`);
    assert.match(html, /document\.addEventListener\('bridgit:answered'/, `${s.id} shows the confirmation`);
    assert.doesNotMatch(html, /\{\{|segment|progress-?bar/i, s.id);
    assert.ok(s.page.startsWith('https://bridgit.cc/samples/'), s.id);
  }
});

test('fillCard swaps the CARD object and keeps </script> out of it', () => {
  const page = '<script>var CARD = /*CARD*/{"a":1}/*END CARD*/;</script>';
  const out = fillCard(page, { title: '</script><b>' });
  assert.equal(out, '<script>var CARD = /*CARD*/{\n  "title": "\\u003c/script>\\u003cb>"\n}/*END CARD*/;</script>');
  assert.throws(() => fillCard('<p>no block</p>', {}));
});

test('the demo script carries the same card pages as the samples', async () => {
  const skill = await read('../public/demo/SKILL.md');
  for (const page of ['action-card.html', 'photo-card.html', 'rps-card.html']) {
    const html = (await read(`../public/samples/${page}`)).trim();
    assert.ok(skill.includes(html), `SKILL.md has ${page} verbatim`);
  }
});
