export type UiErrorCategory = 'TIMEOUT' | 'PERMISSION' | 'NETWORK' | 'CANCELLED' | 'NOT_FOUND' |
  'INVALID_INPUT' | 'STORAGE' | 'UNKNOWN';

export function classifyUiError(error: Object): UiErrorCategory {
  const message = String(error).toLocaleLowerCase();
  if (/timeout|timed out|超时|超过\s*\d+\s*秒/.test(message)) return 'TIMEOUT';
  if (/permission|denied|not allowed|authorization|authorize|权限|授权|1300001/.test(message)) return 'PERMISSION';
  if (/network|socket|connect|dns|offline|网络|联网|econn/.test(message)) return 'NETWORK';
  if (/cancel|cancelled|canceled|取消/.test(message)) return 'CANCELLED';
  if (/not found|does not exist|missing|不存在|找不到|404/.test(message)) return 'NOT_FOUND';
  if (/eacces|eperm|enospc|read|write|storage|file|directory|文件|存储|目录/.test(message)) return 'STORAGE';
  if (/invalid|parse|schema|format|malformed|输入|格式|校验|json/.test(message)) return 'INVALID_INPUT';
  return 'UNKNOWN';
}

export function safeUiError(error: Object, fallback: string): string {
  const category = classifyUiError(error);
  if (category === 'TIMEOUT') return `${fallback}：操作超时，请重试。`;
  if (category === 'PERMISSION') return `${fallback}：权限或系统授权不可用，请检查设置后重试。`;
  if (category === 'NETWORK') return `${fallback}：网络不可用，请检查连接后重试。`;
  if (category === 'CANCELLED') return `${fallback}：操作已取消。`;
  if (category === 'NOT_FOUND') return `${fallback}：所需数据不存在或已失效。`;
  if (category === 'INVALID_INPUT') return `${fallback}：输入内容或文件格式无效。`;
  if (category === 'STORAGE') return `${fallback}：本地数据操作失败，请检查可用空间后重试。`;
  return `${fallback}，请重试。`;
}

export function safeUiErrorCode(error: Object): string {
  return `UI_ERROR_${classifyUiError(error)}`;
}
