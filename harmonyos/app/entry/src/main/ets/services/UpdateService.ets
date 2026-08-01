import { http } from '@kit.NetworkKit';

export type UpdateChannel = 'stable' | 'preview';
export type ReleaseVariant = 'standard' | 'replay';

export const RELEASE_REPOSITORY = 'crazylei12/Pokemon-Champions-dmg_cal';
export const RELEASES_PAGE_URL = `https://github.com/${RELEASE_REPOSITORY}/releases`;
const RELEASES_API_URL = `https://api.github.com/repos/${RELEASE_REPOSITORY}/releases`;

interface GitHubAsset {
  name: string;
  content_type?: string;
  browser_download_url: string;
}

interface GitHubRelease {
  tag_name: string;
  name?: string;
  body?: string;
  html_url?: string;
  prerelease?: boolean;
  draft?: boolean;
  assets?: GitHubAsset[];
}

export interface ReleaseInfo {
  tagName: string;
  title: string;
  notes: string;
  pageUrl: string;
  standardPackageUrl?: string;
  replayPackageUrl?: string;
  prerelease: boolean;
}

export interface UpdateCheckResult {
  kind: 'available' | 'current' | 'none' | 'failure';
  message: string;
  release?: ReleaseInfo;
}

interface SemanticVersion {
  major: number;
  minor: number;
  patch: number;
  prerelease: string[];
}

function parseVersion(value: string): SemanticVersion | undefined {
  const match = value.trim().match(/^[vV]?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/);
  if (!match) return undefined;
  return {
    major: Number(match[1]),
    minor: Number(match[2]),
    patch: Number(match[3]),
    prerelease: match[4]?.split('.') ?? []
  };
}

function comparePrerelease(left: string, right: string): number {
  const leftNumber = /^\d+$/.test(left) ? Number(left) : undefined;
  const rightNumber = /^\d+$/.test(right) ? Number(right) : undefined;
  if (leftNumber !== undefined && rightNumber !== undefined) return leftNumber - rightNumber;
  if (leftNumber !== undefined) return -1;
  if (rightNumber !== undefined) return 1;
  return left.localeCompare(right);
}

export function compareVersions(left: string, right: string): number | undefined {
  const a = parseVersion(left);
  const b = parseVersion(right);
  if (!a || !b) return undefined;
  if (a.major !== b.major) return a.major - b.major;
  if (a.minor !== b.minor) return a.minor - b.minor;
  if (a.patch !== b.patch) return a.patch - b.patch;
  if (a.prerelease.length === 0 && b.prerelease.length === 0) return 0;
  if (a.prerelease.length === 0) return 1;
  if (b.prerelease.length === 0) return -1;
  const length = Math.max(a.prerelease.length, b.prerelease.length);
  for (let index = 0; index < length; index += 1) {
    if (index >= a.prerelease.length) return -1;
    if (index >= b.prerelease.length) return 1;
    const difference = comparePrerelease(a.prerelease[index], b.prerelease[index]);
    if (difference !== 0) return difference;
  }
  return 0;
}

function isReplayAsset(name: string): boolean {
  const normalized = name.toLowerCase();
  return normalized.includes('-replay-') || normalized.includes('-recording-');
}

function packagePriority(name: string): number {
  const normalized = name.toLowerCase();
  if (normalized.includes('arm64')) return 0;
  if (normalized.includes('universal')) return 1;
  return 2;
}

function parseRelease(release: GitHubRelease): ReleaseInfo {
  const packages = (release.assets ?? []).filter((asset: GitHubAsset) => {
    const name = asset.name.toLowerCase();
    return name.endsWith('.hap') || name.endsWith('.app') || name.endsWith('.hsp');
  });
  const select = (replay: boolean): string | undefined => packages
    .filter((asset: GitHubAsset) => isReplayAsset(asset.name) === replay)
    .sort((left: GitHubAsset, right: GitHubAsset) => packagePriority(left.name) - packagePriority(right.name) ||
      left.name.localeCompare(right.name))[0]?.browser_download_url;
  return {
    tagName: release.tag_name,
    title: release.name?.trim() || release.tag_name,
    notes: release.body ?? '',
    pageUrl: release.html_url?.trim() || RELEASES_PAGE_URL,
    standardPackageUrl: select(false),
    replayPackageUrl: select(true),
    prerelease: release.prerelease === true
  };
}

export function selectNewestRelease(releases: GitHubRelease[], channel: UpdateChannel): ReleaseInfo | undefined {
  return releases.filter((release: GitHubRelease) => release.draft !== true &&
    (channel === 'preview' || release.prerelease !== true) && parseVersion(release.tag_name) !== undefined)
    .map((release: GitHubRelease) => parseRelease(release))
    .sort((left: ReleaseInfo, right: ReleaseInfo) =>
      -(compareVersions(left.tagName, right.tagName) ?? 0))[0];
}

async function requestJson(url: string): Promise<string> {
  const request = http.createHttp();
  try {
    const response = await request.request(url, {
      method: http.RequestMethod.GET,
      header: {
        Accept: 'application/vnd.github+json',
        'User-Agent': 'Pokemon-Champions-Assistant-HarmonyOS'
      },
      connectTimeout: 10000,
      readTimeout: 15000
    });
    if (response.responseCode < 200 || response.responseCode > 299) {
      throw new Error(`GitHub HTTP ${response.responseCode}`);
    }
    return typeof response.result === 'string' ? response.result : JSON.stringify(response.result);
  } finally {
    request.destroy();
  }
}

export class UpdateService {
  async check(currentVersion: string, channel: UpdateChannel): Promise<UpdateCheckResult> {
    try {
      const url = channel === 'stable' ? `${RELEASES_API_URL}/latest` : `${RELEASES_API_URL}?per_page=20`;
      const parsed = JSON.parse(await requestJson(url)) as GitHubRelease | GitHubRelease[];
      const releases = Array.isArray(parsed) ? parsed : [parsed];
      const newest = selectNewestRelease(releases, channel);
      if (!newest) {
        return { kind: 'none', message: channel === 'stable' ? '稳定版频道还没有可用 Release。' :
          '预览版频道还没有可用 Release。' };
      }
      const comparison = compareVersions(newest.tagName, currentVersion);
      if (comparison !== undefined && comparison > 0) {
        return { kind: 'available', message: `发现新版本 ${newest.tagName}`, release: newest };
      }
      return { kind: 'current', message: `当前已经是此频道的最新版本（${newest.tagName}）。`, release: newest };
    } catch (error) {
      const message = String(error);
      if (message.includes('403') || message.includes('429')) {
        return { kind: 'failure', message: 'GitHub 更新接口访问频率受限，请稍后再试。' };
      }
      if (message.includes('404')) return { kind: 'none', message: '当前频道还没有可用 Release。' };
      return { kind: 'failure', message: '无法连接 GitHub，请检查网络后重试。' };
    }
  }
}
