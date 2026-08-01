import { http } from '@kit.NetworkKit';

export type UpdateChannel = 'stable' | 'preview';
export type ReleaseVariant = 'standard' | 'replay';

export interface UpdateDownloadPolicy {
  userInitiated: boolean;
  handler: 'SYSTEM_BROWSER';
  progressAndCancel: 'SYSTEM_DOWNLOAD_MANAGER';
  retryAndLowStorage: 'SYSTEM_DOWNLOAD_MANAGER';
  integrityGate: 'GITHUB_ASSET_SIZE_AND_SHA256_METADATA';
}

export const UPDATE_DOWNLOAD_POLICY: UpdateDownloadPolicy = {
  userInitiated: true,
  handler: 'SYSTEM_BROWSER',
  progressAndCancel: 'SYSTEM_DOWNLOAD_MANAGER',
  retryAndLowStorage: 'SYSTEM_DOWNLOAD_MANAGER',
  integrityGate: 'GITHUB_ASSET_SIZE_AND_SHA256_METADATA'
};

export const UPDATE_DOWNLOAD_POLICY_TEXT =
  '只有点击具体版本下载按钮才会交给系统浏览器；下载进度、取消、失败重试和空间不足由系统下载管理器显示。' +
  '缺少大小或 SHA-256 元数据、元数据不合法或链接不受信任时，App 不提供直接下载按钮。';

export const RELEASE_REPOSITORY = 'crazylei12/Pokemon-Champions-dmg_cal';
export const RELEASES_PAGE_URL = `https://github.com/${RELEASE_REPOSITORY}/releases`;
const RELEASES_API_URL = `https://api.github.com/repos/${RELEASE_REPOSITORY}/releases`;
const MAX_RELEASE_RESPONSE_BYTES = 512 * 1024;
const MIN_PACKAGE_BYTES = 1024 * 1024;
const MAX_PACKAGE_BYTES = 200 * 1024 * 1024;

interface GitHubAsset {
  name: string;
  content_type?: string;
  browser_download_url: string;
  state?: string;
  size?: number;
  digest?: string;
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

export interface ReleasePackageInfo {
  variant: ReleaseVariant;
  name: string;
  url: string;
  bytes: number;
  sha256: string;
}

export interface ReleaseInfo {
  tagName: string;
  title: string;
  notes: string;
  pageUrl: string;
  standardPackageUrl?: string;
  standardPackageBytes?: number;
  standardPackageSha256?: string;
  replayPackageUrl?: string;
  replayPackageBytes?: number;
  replayPackageSha256?: string;
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

function releasePageForTag(tag: string): string | undefined {
  if (!/^[0-9A-Za-z._+-]+$/.test(tag)) return undefined;
  return `${RELEASES_PAGE_URL}/tag/${tag}`;
}

export function isTrustedReleaseUrl(value: string): boolean {
  if (value === RELEASES_PAGE_URL) return true;
  const escapedRepository = RELEASE_REPOSITORY.replace('/', '\\/');
  const pagePattern = new RegExp(`^https://github\\.com/${escapedRepository}/releases/tag/[0-9A-Za-z._+-]+$`);
  const assetPattern = new RegExp(`^https://github\\.com/${escapedRepository}/releases/download/` +
    `[0-9A-Za-z._+-]+/pokemon-champions-(standard|replay)-release-signed-universal\\.hap$`, 'i');
  return pagePattern.test(value) || assetPattern.test(value);
}

function packageFor(release: GitHubRelease, variant: ReleaseVariant): ReleasePackageInfo | undefined {
  const expectedName = `pokemon-champions-${variant}-release-signed-universal.hap`;
  const expectedPage = releasePageForTag(release.tag_name);
  if (!expectedPage) return undefined;
  const expectedUrl = `${expectedPage.replace('/tag/', '/download/')}/${expectedName}`;
  const asset = (release.assets ?? []).find((candidate: GitHubAsset) =>
    candidate.name.toLowerCase() === expectedName && candidate.browser_download_url === expectedUrl);
  if (!asset || asset.state !== 'uploaded' || !Number.isSafeInteger(asset.size) ||
    (asset.size as number) < MIN_PACKAGE_BYTES || (asset.size as number) > MAX_PACKAGE_BYTES ||
    !/^sha256:[0-9a-f]{64}$/i.test(asset.digest ?? '') ||
    (asset.content_type !== undefined && asset.content_type !== 'application/octet-stream' &&
      asset.content_type !== 'application/x-hap') || !isTrustedReleaseUrl(asset.browser_download_url)) return undefined;
  return {
    variant,
    name: expectedName,
    url: asset.browser_download_url,
    bytes: asset.size as number,
    sha256: (asset.digest as string).substring('sha256:'.length).toLowerCase()
  };
}

function parseRelease(release: GitHubRelease): ReleaseInfo {
  const standard = packageFor(release, 'standard');
  const replay = packageFor(release, 'replay');
  const trustedPage = releasePageForTag(release.tag_name);
  const pageUrl = release.html_url?.trim();
  const result: ReleaseInfo = {
    tagName: release.tag_name,
    title: release.name?.trim() || release.tag_name,
    notes: release.body ?? '',
    pageUrl: pageUrl && isTrustedReleaseUrl(pageUrl) ? pageUrl : trustedPage ?? RELEASES_PAGE_URL,
    prerelease: release.prerelease === true
  };
  if (standard) {
    result.standardPackageUrl = standard.url;
    result.standardPackageBytes = standard.bytes;
    result.standardPackageSha256 = standard.sha256;
  }
  if (replay) {
    result.replayPackageUrl = replay.url;
    result.replayPackageBytes = replay.bytes;
    result.replayPackageSha256 = replay.sha256;
  }
  return result;
}

export function selectNewestRelease(releases: GitHubRelease[], channel: UpdateChannel): ReleaseInfo | undefined {
  return releases.filter((release: GitHubRelease) => release.draft !== true &&
    (channel === 'preview' || release.prerelease !== true) && parseVersion(release.tag_name) !== undefined)
    .map((release: GitHubRelease) => parseRelease(release))
    .sort((left: ReleaseInfo, right: ReleaseInfo) =>
      -(compareVersions(left.tagName, right.tagName) ?? 0))[0];
}

function isTrustedApiUrl(url: string): boolean {
  return url === `${RELEASES_API_URL}/latest` || url === `${RELEASES_API_URL}?per_page=20`;
}

async function requestJson(url: string): Promise<string> {
  if (!isTrustedApiUrl(url)) throw new Error('Untrusted update API URL');
  const request = http.createHttp();
  try {
    const response = await request.request(url, {
      method: http.RequestMethod.GET,
      header: {
        Accept: 'application/vnd.github+json',
        'User-Agent': 'Pokemon-Champions-Assistant-HarmonyOS',
        'X-GitHub-Api-Version': '2022-11-28'
      },
      connectTimeout: 10000,
      readTimeout: 15000,
      maxLimit: MAX_RELEASE_RESPONSE_BYTES,
      maxRedirects: 0
    });
    if (response.responseCode < 200 || response.responseCode > 299) {
      throw new Error(`GitHub HTTP ${response.responseCode}`);
    }
    const body = typeof response.result === 'string' ? response.result : JSON.stringify(response.result);
    if (body.length === 0 || body.length > MAX_RELEASE_RESPONSE_BYTES) throw new Error('Invalid update response size');
    return body;
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
        if (!newest.standardPackageUrl && !newest.replayPackageUrl) {
          return { kind: 'none', message: `发现 ${newest.tagName}，但没有通过签名命名、通用 ABI、大小和 SHA-256 元数据校验的 HAP。`,
            release: newest };
        }
        return { kind: 'available', message: `发现新版本 ${newest.tagName}`, release: newest };
      }
      return { kind: 'current', message: `当前已经是此频道的最新版本（${newest.tagName}）。`, release: newest };
    } catch (error) {
      const message = String(error);
      if (message.includes('403') || message.includes('429')) {
        return { kind: 'failure', message: 'GitHub 更新接口访问频率受限，请稍后再试。' };
      }
      if (message.includes('404')) return { kind: 'none', message: '当前频道还没有可用 Release。' };
      return { kind: 'failure', message: '无法安全读取 GitHub 更新信息，请检查网络后重试。' };
    }
  }
}
