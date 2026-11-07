import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const requiredInputs = [
  'app/build.gradle.kts',
  'app/src/main/java/cc/bridgit/app/api/SelfDescription.kt',
  'app/src/main/java/cc/bridgit/app/api/Learn.kt',
  'app/src/main/java/cc/bridgit/app/api/Playbook.kt',
  'app/src/main/java/cc/bridgit/app/adb/AdbSetupHelp.kt',
  'tools/bridgit',
  'app/src/test/java/cc/bridgit/app/api/LearnTest.kt',
];
const requiredOutputs = ['learn/pairing-direct.txt', 'learn/pairing-userspace.txt', 'learn/introduction.txt', 'learn/evaluate.md'];

// The manifest is written by LearnTest after rendering fixed fixtures with SetupPrompt.build.
// The private website's build runs this too, against a public checkout ([repo] is always the public repo root).
export async function verifyPromptPublication(repo) {
  const refresh = 'Run tools/refresh-learn.sh and commit the generated prompt files.';
  let manifest;
  try {
    manifest = JSON.parse(await readFile(path.join(repo, 'site-content/public/learn/prompt-manifest.json'), 'utf8'));
  } catch {
    throw new Error(`Missing or invalid pairing-prompt manifest. ${refresh}`);
  }
  for (const [group, required, base] of [
    ['inputs', requiredInputs, repo],
    ['outputs', requiredOutputs, path.join(repo, 'site-content/public')],
  ]) {
    if (JSON.stringify(Object.keys(manifest[group] || {}).sort()) !== JSON.stringify([...required].sort())) {
      throw new Error(`Incomplete pairing-prompt ${group}. ${refresh}`);
    }
    for (const file of required) {
      let actual;
      try {
        actual = createHash('sha256').update(await readFile(path.join(base, file))).digest('hex');
      } catch {
        throw new Error(`Missing pairing-prompt ${group}: ${file}. ${refresh}`);
      }
      if (actual !== manifest[group][file]) throw new Error(`Pairing prompt has drifted: ${file}. ${refresh}`);
    }
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await verifyPromptPublication(process.env.BRIDGIT_PUBLIC_DIR || fileURLToPath(new URL('../../', import.meta.url)));
  console.log('Pairing prompt sources and published examples match.');
}
