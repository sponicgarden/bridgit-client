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

test('A0027: styled HTML cards are the default for tests, yes/no, choices, notices and forms', async () => {
  const file = JSON.parse(await read('../public/samples/samples.json'));
  const ids = file.samples.map(s => s.id);
  const defaults = ['html-yes-no', 'html-notice', 'html-choice', 'html-form', 'html-connection-test'];
  for (const id of defaults) assert.ok(ids.includes(id), id);
  // The html samples come first; native questions and notices are listed after them as the fallback.
  const native = s => /^(question-|notice$)/.test(s.id);
  const firstNative = file.samples.findIndex(native);
  for (const id of defaults) assert.ok(ids.indexOf(id) < firstNative, `${id} before the native samples`);
  for (const s of file.samples.filter(native)) assert.match(s.summary, /Native fallback/, s.id);
  assert.match(file.conventions.default_card, /Default for every owner-facing interaction/);
  assert.match(file.conventions.user_visible_tests, /Never send a bare native question as a test/);
  const form = file.samples.find(s => s.id === 'html-form');
  for (const type of ['choice', 'multi', 'text', 'scale', 'textarea']) assert.ok(form.request.html.includes(`"type": "${type}"`), `html-form has a ${type} field`);
});

test('A0027: the form template is accessible and validates before it answers', async () => {
  const page = await read('../public/samples/form-card.html');
  for (const needle of ['fieldset', 'legend', 'aria-live="polite"', 'role', ':focus-visible', 'min-height: 52px', 'prefers-reduced-motion', 'Please answer this one.', "'bridgit:answered'", 'respond(JSON.stringify(']) {
    assert.ok(page.includes(needle), `form-card.html has ${needle}`);
  }
  assert.doesNotMatch(page, /<form|<iframe|https?:\/\//);
});

test('A0027: guide, playbook and pairing prompts make styled HTML the explicit default', async () => {
  const guide = await read('../public/learn/guide.md');
  const playbook = await read('../public/learn/playbook.md');
  const pairing = await read('../public/learn/pairing-direct.txt');
  for (const text of [guide, playbook, pairing]) {
    assert.match(text, /html-yes-no/);
    assert.doesNotMatch(text, /native questions? for (a )?simple choices?/i);
    assert.doesNotMatch(text, /An HTML page for a yes\/no/);
  }
  assert.match(guide, /even a simple connection check/);
  assert.match(playbook, /A bare native question, even for a connection test/);
});
