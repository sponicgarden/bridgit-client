// Interaction samples: site-content/content/samples.json (the catalogue) plus the card pages in public/samples/
// become public/samples/samples.json, a ready-to-POST file. The app copies it into the APK and serves it at
// GET /v1/samples; the website serves it at /samples/samples.json. Run `node scripts/samples.mjs` after editing
// either source; tests/samples.test.mjs fails while the built file is out of date.
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const SITE = 'https://bridgit.cc';
const CARD_BLOCK = /\/\*CARD\*\/[\s\S]*?\/\*END CARD\*\//;

// Puts [card] into the page's CARD object, between the /*CARD*/ and /*END CARD*/ markers.
export function fillCard(page, card) {
  if (!CARD_BLOCK.test(page)) throw new Error('card page has no /*CARD*/ ... /*END CARD*/ block');
  // Escape "<" so no value can close the <script> element.
  const json = JSON.stringify(card, null, 2).replace(/</g, '\\u003c');
  return page.replace(CARD_BLOCK, () => `/*CARD*/${json}/*END CARD*/`);
}

// Replaces "{{file}}" placeholders in every string with the base64 of that file in public/samples/.
function inline(value, files) {
  if (typeof value === 'string') return value.replace(/\{\{([\w.-]+\.(?:jpg|png))\}\}/g, (_, name) => {
    if (!(name in files)) throw new Error(`unknown sample file ${name}`);
    return files[name];
  });
  if (Array.isArray(value)) return value.map(v => inline(v, files));
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, inline(v, files)]));
  return value;
}

/** [catalogue] is content/samples.json; [pages] maps page names to HTML; [files] maps image names to base64. */
export function buildCatalogue(catalogue, pages, files) {
  const samples = catalogue.samples.map(sample => {
    const { html_file: page, card, ...rest } = sample;
    const out = inline(rest, files);
    if (page) {
      if (!(page in pages)) throw new Error(`${sample.id}: missing page ${page}`);
      const html = card ? fillCard(pages[page], inline(card, files)) : pages[page];
      out.request = { ...out.request, html };
      out.page = `${SITE}/samples/${page}`;
    }
    return out;
  });
  return { about: catalogue.about, api_revision: catalogue.api_revision, conventions: catalogue.conventions, samples };
}

export async function buildSamplesFile(root) {
  const dir = path.join(root, 'public/samples');
  const catalogue = JSON.parse(await readFile(path.join(root, 'content/samples.json'), 'utf8'));
  const pages = {};
  for (const s of catalogue.samples) if (s.html_file) pages[s.html_file] = await readFile(path.join(dir, s.html_file), 'utf8');
  const files = { 'sample-photo.jpg': (await readFile(path.join(dir, 'sample-photo.jpg'))).toString('base64') };
  return JSON.stringify(buildCatalogue(catalogue, pages, files), null, 1) + '\n';
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const root = fileURLToPath(new URL('../', import.meta.url));
  const text = await buildSamplesFile(root);
  await writeFile(path.join(root, 'public/samples/samples.json'), text);
  console.log(`public/samples/samples.json: ${JSON.parse(text).samples.length} samples, ${text.length} bytes.`);
}
