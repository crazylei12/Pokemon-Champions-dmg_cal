import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(toolDirectory, '..', '..');
const sourceRoot = path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'main');

async function loadModule(entryPoint, plugins = []) {
  const output = await build({
    entryPoints: [entryPoint],
    bundle: true,
    format: 'esm',
    platform: 'neutral',
    target: 'es2021',
    write: false,
    logLevel: 'silent',
    loader: { '.ets': 'ts' },
    plugins
  });
  return import(`data:text/javascript;base64,${Buffer.from(output.outputFiles[0].text).toString('base64')}`);
}

const uiModelsPromise = loadModule(path.join(sourceRoot, 'ets', 'ui', 'AppUiModels.ts'));
const privacySafeErrorPromise = loadModule(path.join(sourceRoot, 'ets', 'ui', 'PrivacySafeError.ts'));
const updateServicePromise = loadModule(path.join(sourceRoot, 'ets', 'services', 'UpdateService.ets'), [{
  name: 'stub-network-kit',
  setup(builder) {
    builder.onResolve({ filter: /^@kit\.NetworkKit$/ }, () => ({ path: 'network-kit', namespace: 'stub' }));
    builder.onLoad({ filter: /.*/, namespace: 'stub' }, () => ({
      contents: `
        export const http = {
          RequestMethod: { GET: 0 },
          createHttp() {
            return {
              async request(url, options) {
                globalThis.__harmonyHttpRequests ??= [];
                globalThis.__harmonyHttpRequests.push({ url, options });
                const response = globalThis.__harmonyHttpResponses?.shift();
                if (!response) throw new Error('No queued HarmonyOS HTTP response');
                if (response.error) throw new Error(response.error);
                return { responseCode: response.responseCode, result: response.result };
              },
              destroy() {
                globalThis.__harmonyHttpDestroyCount = (globalThis.__harmonyHttpDestroyCount ?? 0) + 1;
              }
            };
          }
        };
      `,
      loader: 'js'
    }));
  }
}]);
const documentTransferPromise = loadModule(
  path.join(sourceRoot, 'ets', 'services', 'DocumentTransferService.ets'),
  [{
    name: 'stub-document-kits',
    setup(builder) {
      builder.onResolve({ filter: /^@kit\.(AbilityKit|ArkTS|CoreFileKit)$/ }, (args) => ({
        path: args.path,
        namespace: 'document-stub'
      }));
      builder.onLoad({ filter: /.*/, namespace: 'document-stub' }, (args) => {
        if (args.path === '@kit.ArkTS') {
          return {
            contents: `
              export const util = {
                TextDecoder: {
                  create(encoding, options) {
                    const decoder = new globalThis.TextDecoder(encoding, options);
                    return { decodeToString(bytes) { return decoder.decode(bytes); } };
                  }
                }
              };
            `,
            loader: 'js'
          };
        }
        if (args.path === '@kit.CoreFileKit') {
          return {
            contents: `
              export const fileIo = {
                OpenMode: { READ_ONLY: 1, WRITE_ONLY: 2, TRUNC: 4, SYNC: 8 },
                openSync(uri, mode) {
                  globalThis.__harmonyDocumentOpenCalls.push({ uri, mode });
                  globalThis.__harmonyDocumentOffset = 0;
                  return { fd: 71 };
                },
                statSync(target) {
                  globalThis.__harmonyDocumentStatCalls.push(target);
                  return { size: globalThis.__harmonyDocumentStatSize };
                },
                readSync(fd, buffer, options) {
                  globalThis.__harmonyDocumentReadCalls.push({ fd, length: options?.length });
                  const source = globalThis.__harmonyDocumentBytes;
                  const offset = globalThis.__harmonyDocumentOffset;
                  const requested = Math.min(options?.length ?? buffer.byteLength, buffer.byteLength);
                  const planned = globalThis.__harmonyDocumentReadPlan.shift();
                  if (planned === 0 || offset >= source.length) return 0;
                  const count = Math.min(requested, planned ?? requested, source.length - offset);
                  new Uint8Array(buffer).set(source.subarray(offset, offset + count));
                  globalThis.__harmonyDocumentOffset += count;
                  return count;
                },
                readTextSync() {
                  globalThis.__harmonyDocumentReadTextCalls += 1;
                  throw new Error('URI must not be passed to readTextSync');
                },
                closeSync(file) { globalThis.__harmonyDocumentCloseCalls.push(file.fd); },
                writeSync() {},
                fsyncSync() {}
              };
              export const picker = {
                DocumentSelectOptions: class {},
                DocumentSaveOptions: class {},
                DocumentViewPicker: class {
                  async select(options) {
                    globalThis.__harmonyDocumentSelectOptions = options;
                    return globalThis.__harmonyDocumentUris;
                  }
                  async save() { return []; }
                }
              };
            `,
            loader: 'js'
          };
        }
        return { contents: 'export const common = {};', loader: 'js' };
      });
    }
  }]
);

function queueHttpResponses(...responses) {
  globalThis.__harmonyHttpResponses = responses.map((response) => structuredClone(response));
  globalThis.__harmonyHttpRequests = [];
  globalThis.__harmonyHttpDestroyCount = 0;
}

function queueDocument(bytes, readPlan = [], statSize = bytes.length, uris = ['file://document/app-backup.json']) {
  globalThis.__harmonyDocumentBytes = bytes;
  globalThis.__harmonyDocumentReadPlan = [...readPlan];
  globalThis.__harmonyDocumentStatSize = statSize;
  globalThis.__harmonyDocumentUris = [...uris];
  globalThis.__harmonyDocumentOffset = 0;
  globalThis.__harmonyDocumentOpenCalls = [];
  globalThis.__harmonyDocumentStatCalls = [];
  globalThis.__harmonyDocumentReadCalls = [];
  globalThis.__harmonyDocumentReadTextCalls = 0;
  globalThis.__harmonyDocumentCloseCalls = [];
  globalThis.__harmonyDocumentSelectOptions = undefined;
}

function entity(entityType, showdownId, displayName = showdownId) {
  return { entityType, canonicalId: `${entityType}.${showdownId.toLowerCase()}`, showdownId, displayName };
}

function pokemon(speciesId, displayName, moveId) {
  return {
    species: entity('species', speciesId, displayName),
    level: 50,
    actualStats: { hp: 150, atk: 110, def: 100, spa: 120, spd: 100, spe: 105 },
    statPoints: { spa: 32, spe: 32 },
    ability: entity('ability', 'Static', '静电'),
    moves: [{ entity: entity('move', moveId, moveId), basePower: 90, type: 'Electric', source: 'MANUAL_OVERRIDE' }]
  };
}

test('formal HarmonyOS shell exposes all four product tabs and no debug vocabulary', async () => {
  const source = await readFile(path.join(sourceRoot, 'ets', 'pages', 'Index.ets'), 'utf8');
  for (const id of ['nav-home', 'nav-calculator', 'nav-battle', 'nav-settings', 'calculator-submit',
    'battle-page', 'settings-page', 'settings-check-update', 'settings-export-backup']) {
    assert.match(source, new RegExp(`['\"]${id}['\"]`));
  }
  for (const label of ['首页', '自由伤害计算', '对局助手', '设置与诊断']) assert.match(source, new RegExp(label));
  assert.doesNotMatch(source, /Photos|Top-3|\bROI\b|测试图片|调试页|占位页|stage5Verification/i);
});

test('formal UI and coordinator failures expose only categorized privacy-safe messages', async () => {
  const documents = await documentTransferPromise;
  const fixtureText = await readFile(path.join(repositoryRoot, 'test', 'fixtures', 'harmonyos-port', 'phase0',
    'app-backup.json'), 'utf8');
  const fixtureBytes = new TextEncoder().encode(fixtureText);
  queueDocument(fixtureBytes, [3, 11, 29]);
  const transfer = new documents.DocumentTransferService({});
  const imported = await transfer.importJson(16 * 1024 * 1024);
  assert.equal(imported, fixtureText);
  assert.equal(JSON.parse(imported).schemaVersion, 1);
  assert.deepEqual(globalThis.__harmonyDocumentOpenCalls,
    [{ uri: 'file://document/app-backup.json', mode: 1 }]);
  assert.deepEqual(globalThis.__harmonyDocumentStatCalls, [71]);
  assert.ok(globalThis.__harmonyDocumentReadCalls.length >= 4);
  assert.equal(globalThis.__harmonyDocumentReadTextCalls, 0);
  assert.deepEqual(globalThis.__harmonyDocumentCloseCalls, [71]);

  queueDocument(fixtureBytes);
  await assert.rejects(() => transfer.importJson(fixtureBytes.length - 1), /JSON/);
  assert.equal(globalThis.__harmonyDocumentReadCalls.length, 0);
  assert.deepEqual(globalThis.__harmonyDocumentCloseCalls, [71]);

  queueDocument(fixtureBytes, [0]);
  await assert.rejects(() => transfer.importJson(16 * 1024 * 1024), /读取不完整/);
  assert.deepEqual(globalThis.__harmonyDocumentCloseCalls, [71]);

  const privacy = await privacySafeErrorPromise;
  const malicious = new Error('EACCES C:\\Users\\private\\team.json token=TOKEN_secret team=Pikachu,Ditto');
  const display = privacy.safeUiError(malicious, '保存失败');
  const code = privacy.safeUiErrorCode(malicious);
  assert.match(display, /保存失败.*本地数据操作失败/);
  assert.equal(code, 'UI_ERROR_STORAGE');
  for (const value of [display, code]) {
    assert.doesNotMatch(value, /TOKEN_secret|Pikachu|Ditto|Users|team\.json|[A-Z]:[\\/]/i);
  }
  assert.match(privacy.safeUiError(new Error('request timed out at /private/cache'), '计算失败'), /操作超时/);

  const files = [
    ['entryability', 'EntryAbility.ets'], ['pages', 'Index.ets'], ['pages', 'FloatAssistant.ets'],
    ['pages', 'BattleOverlay.ets'], ['pages', 'BattleHudElement.ets'],
    ['services', 'BattleOverlayCoordinator.ts']
  ];
  for (const parts of files) {
    const source = await readFile(path.join(sourceRoot, 'ets', ...parts), 'utf8');
    assert.doesNotMatch(source, /String\(error\)|JSON\.stringify\(error\)/);
    assert.match(source, /safeUiError(?:Code)?/);
  }
});

test('formal UI smoke asserts interaction, scrolling, enabled, disabled and visible hierarchy semantics', async () => {
  const [source, index] = await Promise.all([
    readFile(path.join(repositoryRoot, 'tools', 'harmonyos', 'verify-formal-ui-smoke.ps1'), 'utf8'),
    readFile(path.join(sourceRoot, 'ets', 'pages', 'Index.ets'), 'utf8')
  ]);
  for (const marker of ['Assert-UiNodeAttributes', 'clickable', 'scrollable', 'enabled', 'visible',
    'battle-start-assistant', 'battle-stop-assistant', "enabled = 'false'", "clickable = 'false'"]) {
    assert.match(source, new RegExp(marker));
  }
  assert.match(index, /\.id\('home-page'\)/);
});

test('manual calculator request preserves exact builds, direction and battle conditions', async () => {
  const models = await uiModelsPromise;
  const own = pokemon('Pikachu', '皮卡丘', 'Thunderbolt');
  const opponent = pokemon('Armarouge', '红莲铠骑', 'ArmorCannon');
  const base = {
    direction: 'OWN_TO_OPPONENT', selectedMoveId: 'Thunderbolt', battleType: 'DOUBLE', weather: 'Rain',
    terrain: 'Electric', ownReflect: false, ownLightScreen: false, opponentReflect: true,
    opponentLightScreen: true, critical: true, spread: true
  };
  const outgoing = JSON.parse(models.buildManualDamageRequest(own, opponent, base));
  assert.equal(outgoing.calculationDirection, 'OWN_TO_OPPONENT');
  assert.equal(outgoing.attacker.species.showdownId, 'Pikachu');
  assert.equal(outgoing.defenderProfileSet.profiles[0].actualStats.hp, 150);
  assert.equal(outgoing.moveSelection.moveId, 'Thunderbolt');
  assert.deepEqual(outgoing.battle.defenderSideConditions, { reflect: true, lightScreen: true });
  assert.equal(outgoing.battle.isSpreadMove, true);

  const incoming = JSON.parse(models.buildManualDamageRequest(own, opponent,
    { ...base, direction: 'OPPONENT_TO_OWN', selectedMoveId: 'ArmorCannon' }));
  assert.equal(incoming.calculationDirection, 'OPPONENT_TO_OWN');
  assert.equal(incoming.attackerProfileSet.profiles[0].moves[0].move.showdownId, 'ArmorCannon');
  assert.equal(incoming.defender.species.showdownId, 'Pikachu');
});

test('calculation output is localized into stable display fields', async () => {
  const models = await uiModelsPromise;
  const parsed = models.parseCalculationResult(JSON.stringify({
    ok: true,
    result: {
      calculationDirection: 'OWN_TO_OPPONENT',
      attackerSummary: { speciesName: '皮卡丘' },
      defenderIdentity: { species: { displayName: '红莲铠骑' } },
      moveResults: [{ moveName: '十万伏特', selectedProfileRange: {
        minPercent: 41.2, maxPercent: 48.8, minDamage: 62, maxDamage: 73
      }, koSummary: { text: 'guaranteed 3HKO' }, assumptions: ['雨天'] }],
      warnings: [{ code: 'EXAMPLE', message: '仅用于验证' }]
    }
  }));
  assert.equal(parsed.attacker, '皮卡丘');
  assert.equal(parsed.moves[0].koText, '必定 3 次击倒');
  assert.deepEqual(parsed.warnings, ['仅用于验证']);

  const localized = models.parseCalculationResult(JSON.stringify({ ok: true, result: {
    calculationDirection: 'OWN_TO_OPPONENT', attackerSummary: { speciesName: '皮卡丘' },
    defenderIdentity: { species: { displayName: '沼王' } }, moveResults: [{ moveName: '十万伏特',
      selectedProfileRange: { minPercent: 0, maxPercent: 0, minDamage: 0, maxDamage: 0 },
      koSummary: { text: 'No direct damage.' }, assumptions: ['Defender ability is unspecified.',
        'Defender profile: 物盾'] }], warnings: [{ code: 'LEGAL_MOVE_POOL_MISSING', message: 'English detail' }] }
  }));
  assert.equal(localized.moves[0].koText, '无直接伤害（属性免疫或变化招式）');
  assert.deepEqual(localized.moves[0].assumptions, ['防守方特性未指定。', '防守方配置：物盾']);
  assert.deepEqual(localized.warnings, ['未提供对手的合法招式池。']);
});

test('team and preset helpers preserve edit validation and bilingual search', async () => {
  const models = await uiModelsPromise;
  const storedTeam = {
    savedTeamId: 'team-1', teamName: '原名', pokemon: Array.from({ length: 6 }, () => ({
      species: entity('species', 'Pikachu', '皮卡丘'), level: 50,
      actualStats: { hp: 150, atk: 110, def: 100, spa: 120, spd: 100, spe: 105 },
      ability: entity('ability', 'Static', '静电'), moves: [{ move: entity('move', 'Thunderbolt', '十万伏特') }]
    }))
  };
  assert.equal(models.teamWithName(storedTeam, '  新队伍  ').teamSlotName, '新队伍');
  assert.equal(models.toTeamDisplay(storedTeam).damageReady, true);
  assert.throws(() => models.teamWithName(storedTeam, '   '), /不能为空/);
  const displayedTeam = models.toTeamDisplay({
    ...storedTeam,
    pokemon: [
      { ...storedTeam.pokemon[0], species: entity('species', 'Ditto', '百变怪') },
      { ...storedTeam.pokemon[0], species: entity('species', 'Mawile', '大嘴娃') }
    ]
  });
  const secondSlot = models.teamEditSelection(displayedTeam, 1);
  assert.equal(secondSlot.slot, 1);
  assert.equal(secondSlot.pokemon.species.showdownId, 'Mawile');
  secondSlot.pokemon.species.displayName = 'changed draft';
  assert.equal(displayedTeam.pokemon[1].species.displayName, '大嘴娃');
  assert.equal(models.teamEditSelection(displayedTeam, 2), undefined);
  const preset = { speciesId: 'charizard', preset: { profileId: 'user.fast', profileName: '高速输出' } };
  const species = entity('species', 'Charizard', '喷火龙');
  assert.equal(models.presetSearchMatches(preset, species, '喷火'), true);
  assert.equal(models.presetSearchMatches(preset, species, 'char'), true);
  assert.equal(models.presetSearchMatches(preset, species, '高速'), true);
  assert.equal(models.presetSourceLabel('USER_SAVED'), '用户保存');
  assert.equal(models.teamSourceLabel({ importSource: 'HARMONYOS_ALBUM_SCREEN_CAPTURE' }), '相册窗口识别并人工核对');
});

test('window bounds clamp and edge snap respect system safe-area insets', async () => {
  const models = await uiModelsPromise;
  const insets = { left: 20, top: 40, right: 30, bottom: 50 };
  assert.deepEqual(models.clampWindowBounds({ x: -10, y: 900, width: 500, height: 400 },
    1000, 800, insets, 320, 220), { x: 20, y: 350, width: 500, height: 400 });
  assert.deepEqual(models.snapWindowBoundsToEdge({ x: 700, y: 100, width: 200, height: 200 },
    1000, 800, insets, 160, 160), { x: 770, y: 100, width: 200, height: 200 });
});

test('shared request LRU ignores requestId, refreshes hits and evicts the least-recent key', async () => {
  const models = await uiModelsPromise;
  assert.equal(models.damageRequestCacheKey(JSON.stringify({ requestId: 'one', value: 7 })),
    models.damageRequestCacheKey(JSON.stringify({ requestId: 'two', value: 7 })));
  const cache = new models.StringLruCache(2);
  cache.set('a', 'A');
  cache.set('b', 'B');
  assert.equal(cache.get('a'), 'A');
  cache.set('c', 'C');
  assert.equal(cache.get('b'), undefined);
  assert.equal(cache.get('a'), 'A');
  assert.equal(cache.size(), 2);
});

test('panel navigation restores a collapsed subpage and resets for a newly recognized team', async () => {
  const models = await uiModelsPromise;
  const navigation = new models.BattlePanelNavigation();
  navigation.show('SPEED');
  navigation.collapse();
  assert.equal(navigation.isVisible(), false);
  assert.equal(navigation.reopen(), 'SPEED');
  navigation.resetForTeamRecognition();
  assert.equal(navigation.page(), 'DAMAGE');
  assert.equal(navigation.isVisible(), false);
});

test('middle fold crease is treated as an unavailable region', async () => {
  const models = await uiModelsPromise;
  const result = models.avoidWindowOcclusions({ x: 420, y: 100, width: 300, height: 300 }, 1000, 800,
    { left: 20, top: 40, right: 20, bottom: 40 }, 160, 160,
    [{ left: 490, top: 0, width: 20, height: 800 }]);
  assert.ok(result.x + result.width <= 490 || result.x >= 510);
});

test('formal subpages provide retry, guarded mutations, complete preset fields and deterministic back recovery', async () => {
  const source = await readFile(path.join(sourceRoot, 'ets', 'pages', 'Index.ets'), 'utf8');
  for (const marker of ['onBackPress', 'requestSubpageBack', 'app-load-retry', 'beginDataMutation', '复制',
    '实际能力值预览', '添加合法招式', '搜索特性', 'canonical ID', '队伍来源']) {
    assert.match(source, new RegExp(marker));
  }
  assert.match(source, /move\.entity\.canonicalId\.toLocaleLowerCase\(\)\.includes\(query\)/);
  assert.match(source, /ForEach\(\[`\$\{this\.teamEditSlot\}-\$\{this\.teamEditRevision\}`\],[\s\S]*team-edit-slot-/,
    'switching slots and editing fields must recreate the editor subtree with the new Pokemon model');
  assert.equal((source.match(/this\.teamEditPokemon\s*=/g) ?? []).length, 1,
    'team editor updates must flow through the revision-tracked setter');
});

test('manual update selection follows semantic version and release channels', async () => {
  const updates = await updateServicePromise;
  assert.deepEqual(updates.UPDATE_DOWNLOAD_POLICY, {
    userInitiated: true,
    handler: 'SYSTEM_BROWSER',
    progressAndCancel: 'SYSTEM_DOWNLOAD_MANAGER',
    retryAndLowStorage: 'SYSTEM_DOWNLOAD_MANAGER',
    integrityGate: 'GITHUB_ASSET_SIZE_AND_SHA256_METADATA'
  });
  assert.match(updates.UPDATE_DOWNLOAD_POLICY_TEXT, /进度、取消、失败重试和空间不足/);
  assert.ok(updates.compareVersions('v1.2.0', '1.1.9') > 0);
  assert.ok(updates.compareVersions('1.2.0-beta.2', '1.2.0-beta.1') > 0);
  assert.ok(updates.compareVersions('1.2.0', '1.2.0-beta.9') > 0);
  const releases = [
    { tag_name: 'v1.2.0-beta.1', prerelease: true, assets: [] },
    { tag_name: 'v1.1.5', html_url: 'https://github.com/crazylei12/Pokemon-Champions-dmg_cal/releases/tag/v1.1.5',
      prerelease: false, assets: [
      { name: 'pokemon-champions-standard-release-signed-universal.hap', state: 'uploaded',
        size: 20 * 1024 * 1024, digest: `sha256:${'a'.repeat(64)}`, content_type: 'application/octet-stream',
        browser_download_url: 'https://github.com/crazylei12/Pokemon-Champions-dmg_cal/releases/download/v1.1.5/pokemon-champions-standard-release-signed-universal.hap' },
      { name: 'pokemon-champions-replay-release-signed-universal.hap', state: 'uploaded',
        size: 21 * 1024 * 1024, digest: `sha256:${'b'.repeat(64)}`, content_type: 'application/x-hap',
        browser_download_url: 'https://github.com/crazylei12/Pokemon-Champions-dmg_cal/releases/download/v1.1.5/pokemon-champions-replay-release-signed-universal.hap' }
    ] }
  ];
  assert.equal(updates.selectNewestRelease(releases, 'stable').tagName, 'v1.1.5');
  assert.equal(updates.selectNewestRelease(releases, 'preview').tagName, 'v1.2.0-beta.1');
  assert.match(updates.selectNewestRelease(releases, 'stable').replayPackageUrl, /replay-release-signed-universal\.hap$/);
  assert.equal(updates.selectNewestRelease(releases, 'stable').standardPackageSha256, 'a'.repeat(64));
  assert.equal(updates.isTrustedReleaseUrl('https://evil.example/update.hap'), false);

  queueHttpResponses({ responseCode: 200, result: JSON.stringify(releases[1]) });
  const check = await new updates.UpdateService().check('1.1.4', 'stable');
  assert.equal(check.kind, 'available');
  assert.equal(check.release.tagName, 'v1.1.5');
  assert.equal(globalThis.__harmonyHttpRequests.length, 1);
  const request = globalThis.__harmonyHttpRequests[0];
  assert.equal(request.url,
    'https://api.github.com/repos/crazylei12/Pokemon-Champions-dmg_cal/releases/latest');
  assert.deepEqual(request.options, {
    method: 0,
    header: {
      Accept: 'application/vnd.github+json',
      'User-Agent': 'Pokemon-Champions-Assistant-HarmonyOS',
      'X-GitHub-Api-Version': '2022-11-28'
    },
    connectTimeout: 10000,
    readTimeout: 15000,
    maxLimit: 512 * 1024,
    maxRedirects: 0
  });
  assert.equal(globalThis.__harmonyHttpDestroyCount, 1);
});

test('update assets reject APK, debug, unsigned, wrong-host, bad-size and bad-digest candidates', async () => {
  const updates = await updateServicePromise;
  const names = ['pokemon-champions-standard-release.apk', 'pokemon-champions-standard-x86_64-debug.hap',
    'pokemon-champions-standard-release-universal.hap', 'pokemon-champions-standard-release-signed-universal.app'];
  const release = { tag_name: 'v2.0.0', assets: names.map((name) => ({ name, state: 'uploaded',
    size: 10 * 1024 * 1024, digest: `sha256:${'c'.repeat(64)}`, browser_download_url: `https://evil.example/${name}` })) };
  release.assets.push({ name: 'pokemon-champions-standard-release-signed-universal.hap', state: 'uploaded',
    size: 12, digest: 'sha256:bad',
    browser_download_url: 'https://github.com/crazylei12/Pokemon-Champions-dmg_cal/releases/download/v2.0.0/pokemon-champions-standard-release-signed-universal.hap' });
  const selected = updates.selectNewestRelease([release], 'stable');
  assert.equal(selected.standardPackageUrl, undefined);
  assert.equal(selected.replayPackageUrl, undefined);

  const newerWithoutAssets = JSON.stringify({ tag_name: 'v2.0.0', prerelease: false, assets: [] });
  const cases = [
    { response: { responseCode: 200, result: newerWithoutAssets }, kind: 'none', message: /没有通过.*HAP/ },
    { response: { responseCode: 404, result: '{}' }, kind: 'none', message: /还没有可用 Release/ },
    { response: { responseCode: 403, result: '{}' }, kind: 'failure', message: /访问频率受限/ },
    { response: { responseCode: 429, result: '{}' }, kind: 'failure', message: /访问频率受限/ },
    { response: { responseCode: 500, result: '{}' }, kind: 'failure', message: /检查网络后重试/ },
    { response: { responseCode: 200, result: '' }, kind: 'failure', message: /检查网络后重试/ },
    { response: { responseCode: 200, result: 'x'.repeat(512 * 1024 + 1) }, kind: 'failure',
      message: /检查网络后重试/ },
    { response: { responseCode: 200, result: '{bad json' }, kind: 'failure', message: /检查网络后重试/ },
    { response: { error: 'network offline' }, kind: 'failure', message: /检查网络后重试/ },
    { response: { error: 'request timeout' }, kind: 'failure', message: /检查网络后重试/ }
  ];
  for (const entry of cases) {
    queueHttpResponses(entry.response);
    const result = await new updates.UpdateService().check('1.1.4', 'stable');
    assert.equal(result.kind, entry.kind);
    assert.match(result.message, entry.message);
    assert.equal(globalThis.__harmonyHttpResponses.length, 0);
    assert.equal(globalThis.__harmonyHttpDestroyCount, 1);
  }
});

test('UI helpers preserve move metadata, gate stale async results and share user presets across compatible forms', async () => {
  const models = await uiModelsPromise;
  const gate = new models.AsyncRequestGate();
  const first = gate.begin();
  assert.equal(gate.isCurrent(first), true);
  const second = gate.begin();
  assert.equal(gate.isCurrent(first), false);
  assert.equal(gate.isCurrent(second), true);
  gate.dispose();
  assert.equal(gate.isCurrent(second), false);

  const charizard = entity('species', 'Charizard', '喷火龙');
  const megaX = entity('species', 'Charizard-Mega-X', '超级喷火龙X');
  const blaze = entity('ability', 'Blaze', '猛火');
  const toughClaws = entity('ability', 'ToughClaws', '硬爪');
  const quickAttack = { entity: entity('move', 'QuickAttack', '电光一闪'), basePower: 40,
    type: 'Normal', priority: 1, source: 'OPPONENT_LEGAL_MOVE_POOL' };
  const protect = { entity: entity('move', 'Protect', '守住'), priority: 4,
    source: 'OPPONENT_LEGAL_MOVE_POOL' };
  const forms = [
    { familyId: 'charizard', configurationShareGroupId: 'charizard-base-mega', species: charizard,
      baseStats: { hp: 78, atk: 84, def: 78, spa: 109, spd: 85, spe: 100 }, defaultAbility: blaze,
      abilities: [blaze], learnableMoves: [quickAttack, protect] },
    { familyId: 'charizard', configurationShareGroupId: 'charizard-base-mega', species: megaX,
      baseStats: { hp: 78, atk: 130, def: 111, spa: 130, spd: 85, spe: 100 }, defaultAbility: toughClaws,
      abilities: [toughClaws], learnableMoves: [quickAttack, protect] }
  ];
  const repository = {
    formFor(id) { return forms.find((form) => form.species.showdownId.toLowerCase() === id.toLowerCase()); },
    formsFor() { return forms; },
    abilitiesFor(id) { return this.formFor(id)?.abilities ?? []; },
    legalMovesFor(id) { return this.formFor(id)?.learnableMoves ?? []; },
    actualStatsFor() { return { hp: 153, atk: 100, def: 98, spa: 129, spd: 105, spe: 120 }; }
  };
  const root = { presets: [{ speciesId: 'Charizard', preset: { profileId: 'user.charizard', profileName: '共享配置',
    level: 50, statPoints: { spe: 20 }, ability: blaze, moves: [] } }] };
  const shared = models.userProfilesForSpecies(root, megaX, repository);
  assert.equal(shared.length, 1);
  assert.equal(shared[0].ability.showdownId, 'ToughClaws');
  const metadata = models.legalMoveWithMetadata(repository, megaX, quickAttack.entity);
  assert.equal(metadata.priority, 1);
  assert.equal(metadata.basePower, 40);
  assert.deepEqual(models.priorityMovesForSpecies(repository, megaX).map((move) => move.entity.showdownId),
    ['QuickAttack']);
});

test('formal product permissions remain limited to Internet plus the floating assistant', async () => {
  const profile = JSON.parse(await readFile(path.join(sourceRoot, 'module.json5'), 'utf8'));
  assert.deepEqual(profile.module.requestPermissions, [
    { name: 'ohos.permission.INTERNET' },
    { name: 'ohos.permission.SYSTEM_FLOAT_WINDOW' }
  ]);
});
