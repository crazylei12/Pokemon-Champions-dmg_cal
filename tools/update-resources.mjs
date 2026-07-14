#!/usr/bin/env node

import {spawn} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {createInterface} from 'node:readline/promises';
import {fileURLToPath} from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SMOGON_DIR = path.join(ROOT, 'external', 'smogon-damage-calc');
const SMOGON_CALC_DIR = path.join(SMOGON_DIR, 'calc');
const ICON_ASSETS_DIR = path.join(ROOT, 'src', 'data', 'pokemon-icons', 'assets');

const COMMON_TASKS = ['localization', 'iconsCatalog', 'iconsDownload', 'visionDataset', 'visionEvaluate'];

const FLAG_TASKS = new Map([
  ['--smogon', 'smogon'],
  ['--localization', 'localization'],
  ['--icons-catalog', 'iconsCatalog'],
  ['--icons-download', 'iconsDownload'],
  ['--vision-dataset', 'visionDataset'],
  ['--vision-evaluate', 'visionEvaluate'],
  ['--status', 'status'],
]);

const MENU_CHOICES = new Map([
  ['1', ['smogon']],
  ['2', ['localization']],
  ['3', ['iconsCatalog']],
  ['4', ['iconsDownload']],
  ['5', ['visionDataset']],
  ['6', ['visionEvaluate']],
  ['A', COMMON_TASKS],
  ['S', ['status']],
]);

const TASKS = {
  smogon: {
    title: '更新 Smogon 伤害计算器',
    run: runSmogonUpdate,
  },
  localization: {
    title: '刷新中文本地化数据',
    run: async options => {
      await runCommand(process.execPath, ['tools/localization/sync-zh-hans.mjs', '--refresh-sources'], options);
      console.log(summarizeLocalizationCoverage(readJson('src/data/localization/coverage.zh-Hans.json')));
    },
  },
  iconsCatalog: {
    title: '更新宝可梦图标 catalog/coverage',
    run: async options => {
      await runCommand(process.execPath, ['tools/pokemon-icons/sync-pokemon-icons.mjs'], options);
      console.log(summarizeIconCoverage(readJson('src/data/pokemon-icons/coverage.pokeapi-composite.json')));
    },
  },
  iconsDownload: {
    title: '下载或刷新宝可梦图标 PNG 缓存',
    run: async options => {
      await runCommand(process.execPath, ['tools/pokemon-icons/sync-pokemon-icons.mjs', '--download'], options);
      console.log(`图标缓存目录: ${relativePath(ICON_ASSETS_DIR)} (已被 .gitignore 忽略，不建议提交 PNG 缓存)`);
    },
  },
  visionDataset: {
    title: '重建 references 和 vision dataset',
    run: async options => {
      await runCommand(npmCommand(), ['run', 'recognition:vision:build-dataset', '--', '--clear-output'], options);
      console.log(
        summarizeDatasetManifest(
          readJson('dataset/manifest.json'),
          readJson('references/_manifest.json'),
        ),
      );
    },
  },
  visionEvaluate: {
    title: '跑队伍预览识别评估并刷新模板缓存',
    run: async options => {
      await runCommand(npmCommand(), [
        'run',
        'recognition:vision:evaluate:labeled',
        '--',
        '--refresh-template-cache',
        '--timing-output',
        '.tmp/pokemon-vision-resource-update-timing.json',
      ], options);
      console.log('评估输出: .tmp/pokemon-vision-eval');
      console.log('计时输出: .tmp/pokemon-vision-resource-update-timing.json');
      console.log('模板缓存目录: src/data/recognition/template-cache');
    },
  },
  status: {
    title: '显示当前资源状态摘要',
    run: async () => {
      await runStatusSummary();
    },
  },
};

export function parseArgs(argv) {
  const tasks = [];
  let dryRun = false;
  let help = false;

  for (const arg of argv) {
    if (arg === '--help' || arg === '-h') {
      help = true;
      continue;
    }
    if (arg === '--dry-run') {
      dryRun = true;
      continue;
    }
    if (arg === '--all') {
      appendUnique(tasks, COMMON_TASKS);
      continue;
    }
    const task = FLAG_TASKS.get(arg);
    if (task) {
      appendUnique(tasks, [task]);
      continue;
    }
    throw new Error(`未知参数: ${arg}`);
  }

  if (help) return {mode: 'help', tasks: [], dryRun};
  if (!tasks.length) return {mode: 'interactive', tasks: [], dryRun};
  return {mode: 'batch', tasks, dryRun};
}

export function summarizeLocalizationCoverage(coverage) {
  const missing = coverage?.missing || {};
  const parts = Object.entries(missing)
    .map(([name, values]) => [name, Array.isArray(values) ? values.length : 0])
    .filter(([, count]) => count > 0);
  const total = parts.reduce((sum, [, count]) => sum + count, 0);
  const detail = parts.length ? ` (${parts.map(([name, count]) => `${name}=${count}`).join(', ')})` : '';
  return `中文本地化覆盖率: 缺失映射 ${total}${detail}`;
}

export function summarizeIconCoverage(coverage) {
  const counts = coverage?.counts || {};
  const fallbackUsed = Array.isArray(coverage?.fallbackUsed) ? coverage.fallbackUsed.length : 0;
  return [
    '图标覆盖率:',
    `unmapped=${numberValue(counts.unmapped)}`,
    `templateRequired=${numberValue(counts.userTemplateRequired)}`,
    `shinyMissing=${numberValue(counts.shinyTemplateMissing)}`,
    `fallbackUsed=${fallbackUsed}`,
  ].join(' ');
}

export function summarizeDatasetManifest(manifest, referencesManifest) {
  const references = manifest?.references?.count ?? 0;
  const manifestReferences = Array.isArray(referencesManifest?.references)
    ? referencesManifest.references.length
    : 0;
  const trainImages = manifest?.realTrain?.imageCount ?? 0;
  const labels = manifest?.realTrain?.labelCount ?? 0;
  const realTestImages = manifest?.realTest?.imageCount ?? 0;
  return [
    'Vision dataset:',
    `references=${references}`,
    `manifestReferences=${manifestReferences}`,
    `trainImages=${trainImages}`,
    `labels=${labels}`,
    `realTestImages=${realTestImages}`,
  ].join(' ');
}

export async function main(argv = process.argv.slice(2)) {
  let parsed;
  try {
    parsed = parseArgs(argv);
  } catch (error) {
    console.error(formatError(error));
    printHelp();
    return 1;
  }

  if (parsed.mode === 'help') {
    printHelp();
    return 0;
  }

  if (parsed.mode === 'interactive') {
    return runInteractive(parsed);
  }

  return runTaskList(parsed.tasks, {dryRun: parsed.dryRun});
}

async function runInteractive(options) {
  printHeader();
  const rl = createInterface({input: process.stdin, output: process.stdout});

  try {
    for (;;) {
      printMenu();
      const answer = (await rl.question('请选择更新项: ')).trim().toUpperCase();
      if (!answer) continue;
      if (answer === 'Q' || answer === 'QUIT' || answer === 'EXIT') {
        console.log('已退出资源更新器。');
        return 0;
      }

      const taskIds = menuTasks(answer);
      if (!taskIds.length) {
        console.log(`无法识别选项: ${answer}`);
        continue;
      }

      await runTaskList(taskIds, {dryRun: options.dryRun, interactive: true});
    }
  } finally {
    rl.close();
  }
}

function menuTasks(answer) {
  const tokens = answer.split(/[,\s]+/).filter(Boolean);
  const tasks = [];
  for (const token of tokens) {
    const ids = MENU_CHOICES.get(token);
    if (!ids) return [];
    appendUnique(tasks, ids);
  }
  return tasks;
}

async function runTaskList(taskIds, options = {}) {
  let failed = 0;
  for (const taskId of taskIds) {
    const task = TASKS[taskId];
    if (!task) {
      console.error(`未知任务: ${taskId}`);
      failed++;
      continue;
    }

    console.log('');
    console.log(`==> ${task.title}`);
    if (options.dryRun) {
      console.log(`[dry-run] 将执行任务: ${taskId}`);
      continue;
    }

    try {
      await task.run(options);
      console.log(`完成: ${task.title}`);
    } catch (error) {
      failed++;
      console.error(`失败: ${task.title}`);
      console.error(formatError(error));
      if (!options.interactive) continue;
      console.log('该项已跳过，你可以处理问题后重新选择。');
    }
  }

  if (!options.dryRun) {
    printCommitScopeHint();
  }
  return failed ? 1 : 0;
}

async function runSmogonUpdate(options) {
  if (!fs.existsSync(path.join(SMOGON_DIR, '.git'))) {
    throw new Error(`找不到 nested git 仓库: ${relativePath(SMOGON_DIR)}`);
  }

  const dirty = await runCommandCapture('git', ['status', '--short'], {...options, cwd: SMOGON_DIR});
  if (dirty.trim()) {
    throw new Error([
      'Smogon 仓库有本地改动，已拒绝 pull，避免覆盖 Champions 适配或手工修改。',
      dirty.trim(),
    ].join('\n'));
  }

  await runCommand('git', ['fetch', 'origin'], {...options, cwd: SMOGON_DIR});
  await runCommand('git', ['pull', '--ff-only'], {...options, cwd: SMOGON_DIR});

  const tscPath = path.join(SMOGON_CALC_DIR, 'node_modules', '.bin', process.platform === 'win32' ? 'tsc.cmd' : 'tsc');
  if (!fs.existsSync(tscPath)) {
    console.log('未找到 Smogon calc TypeScript 编译器，跳过轻量校验。需要时请先在 external/smogon-damage-calc 安装依赖。');
    return;
  }
  await runCommand(tscPath, ['-p', 'tsconfig.json', '--noEmit'], {...options, cwd: SMOGON_CALC_DIR});
}

async function runStatusSummary() {
  console.log(summarizeLocalizationCoverage(readJsonIfExists('src/data/localization/coverage.zh-Hans.json')));
  console.log(summarizeIconCoverage(readJsonIfExists('src/data/pokemon-icons/coverage.pokeapi-composite.json')));
  console.log(
    summarizeDatasetManifest(
      readJsonIfExists('dataset/manifest.json'),
      readJsonIfExists('references/_manifest.json'),
    ),
  );
  const smogonHead = await tryRunCommandCapture('git', ['rev-parse', '--short', 'HEAD'], {cwd: SMOGON_DIR});
  const smogonStatus = await tryRunCommandCapture('git', ['status', '--short'], {cwd: SMOGON_DIR});
  console.log(`Smogon: HEAD=${smogonHead.trim() || 'unknown'} dirty=${smogonStatus.trim() ? 'yes' : 'no'}`);
  console.log(`图标 PNG 缓存: ${fs.existsSync(ICON_ASSETS_DIR) ? relativePath(ICON_ASSETS_DIR) : '未下载'}`);
}

function runCommand(command, args, options = {}) {
  console.log(`$ ${commandLine(command, args)}`);
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: options.cwd || ROOT,
      env: process.env,
      shell: false,
      stdio: 'inherit',
    });
    child.on('error', reject);
    child.on('exit', code => {
      if (code === 0) resolve();
      else reject(new Error(`命令退出码 ${code}: ${commandLine(command, args)}`));
    });
  });
}

function runCommandCapture(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: options.cwd || ROOT,
      env: process.env,
      shell: false,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', chunk => {
      stdout += chunk;
    });
    child.stderr.on('data', chunk => {
      stderr += chunk;
    });
    child.on('error', reject);
    child.on('exit', code => {
      if (code === 0) resolve(stdout);
      else reject(new Error(`${commandLine(command, args)} failed with ${code}\n${stderr.trim()}`));
    });
  });
}

async function tryRunCommandCapture(command, args, options = {}) {
  try {
    return await runCommandCapture(command, args, options);
  } catch {
    return '';
  }
}

function readJson(relativeFile) {
  return JSON.parse(fs.readFileSync(path.join(ROOT, relativeFile), 'utf8'));
}

function readJsonIfExists(relativeFile) {
  const filePath = path.join(ROOT, relativeFile);
  if (!fs.existsSync(filePath)) return {};
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'));
  } catch (error) {
    console.log(`无法读取 ${relativeFile}: ${formatError(error)}`);
    return {};
  }
}

function appendUnique(target, values) {
  for (const value of values) {
    if (!target.includes(value)) target.push(value);
  }
}

function numberValue(value) {
  return Number.isFinite(Number(value)) ? Number(value) : 0;
}

function npmCommand() {
  return process.platform === 'win32' ? 'npm.cmd' : 'npm';
}

function commandLine(command, args) {
  return [command, ...args].map(quoteArg).join(' ');
}

function quoteArg(value) {
  const text = String(value);
  return /[\s"]/u.test(text) ? `"${text.replace(/"/g, '\\"')}"` : text;
}

function relativePath(filePath) {
  return path.relative(ROOT, filePath).replaceAll(path.sep, '/');
}

function formatError(error) {
  return error instanceof Error ? error.message : String(error);
}

function printHeader() {
  console.log('Pokemon Champions 资源更新器');
  console.log('默认不会自动提交，也不会把 PNG 缓存或 .tmp 作为提交物。');
}

function printMenu() {
  console.log('');
  console.log('1) 更新 Smogon 伤害计算器');
  console.log('2) 刷新中文本地化数据');
  console.log('3) 更新宝可梦图标 catalog/coverage');
  console.log('4) 下载或刷新宝可梦图标 PNG 缓存');
  console.log('5) 重建 references 和 vision dataset');
  console.log('6) 跑队伍预览识别评估并刷新模板缓存');
  console.log('A) 常用全流程: 2 -> 3 -> 4 -> 5 -> 6');
  console.log('S) 显示当前资源状态摘要');
  console.log('Q) 退出');
}

function printHelp() {
  printHeader();
  console.log('');
  console.log('用法:');
  console.log('  npm run update:resources');
  console.log('  npm run update:resources -- --smogon --icons-catalog');
  console.log('  npm run update:resources -- --all');
  console.log('');
  console.log('参数:');
  console.log('  --smogon           更新 external/smogon-damage-calc');
  console.log('  --localization     刷新中文本地化来源并生成 zh-Hans.json');
  console.log('  --icons-catalog    更新宝可梦图标 catalog/coverage');
  console.log('  --icons-download   下载或刷新图标 PNG 本地缓存');
  console.log('  --vision-dataset   重建 references/ 和 dataset/');
  console.log('  --vision-evaluate  跑 labeled 识别评估并刷新模板缓存');
  console.log('  --status           显示当前资源状态摘要');
  console.log('  --all              执行常用全流程: localization, icons, dataset, evaluate');
  console.log('  --dry-run          只打印将执行的任务，不运行命令');
  console.log('  --help             显示帮助');
}

function printCommitScopeHint() {
  console.log('');
  console.log('提交范围提示:');
  console.log('- 可检查并提交: src/data/pokemon-icons/*.json, src/data/localization/*.json');
  console.log('- 公开仓库不要提交: references/, dataset/, src/data/pokemon-icons/assets/, src/data/recognition/template-cache/*.pkl, .tmp/');
}

const isDirectRun = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isDirectRun) {
  main().then(code => {
    process.exitCode = code;
  }).catch(error => {
    console.error(formatError(error));
    process.exitCode = 1;
  });
}
