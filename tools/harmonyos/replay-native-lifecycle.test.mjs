import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);
const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(toolDirectory, '..', '..');

test('compiled C++ replay cleanup policy covers success cancellation failures and repeated cleanup', async () => {
  const localConfig = JSON.parse(await readFile(path.join(repositoryRoot, 'config', 'harmonyos-local.json'), 'utf8'));
  const clang = path.join(localConfig.toolchainRoot, 'sdk', 'default', 'openharmony', 'native', 'llvm', 'bin',
    'clang++.exe');
  const includeDirectory = path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'main', 'cpp');
  const source = path.join(toolDirectory, 'native', 'replay_lifecycle_policy_test.cpp');
  const temporaryDirectory = await mkdtemp(path.join(os.tmpdir(), 'pc-replay-native-'));
  const executable = path.join(temporaryDirectory, 'replay-lifecycle-policy.exe');
  try {
    await execFileAsync(clang, [
      '--target=x86_64-pc-windows-msvc', '-std=c++17', '-O0', '-nostdlib', '-fuse-ld=lld',
      '-fno-exceptions', '-fno-rtti', '-fno-stack-protector', `-I${includeDirectory}`,
      '-Wl,/entry:mainCRTStartup,/subsystem:console,/nodefaultlib,/machine:x64', source, '-o', executable
    ], { windowsHide: true });
    const result = await execFileAsync(executable, [], { windowsHide: true });
    assert.equal(result.stderr, '', 'compiled C++ lifecycle test wrote an unexpected error');
  } finally {
    await rm(temporaryDirectory, { recursive: true, force: true });
  }
});
