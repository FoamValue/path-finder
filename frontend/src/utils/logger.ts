/**
 * 前端统一日志：前缀 [PathFinder]，便于在浏览器 Console 中按关键字过滤。
 * 用于辅助排查「请求失败」等问题。
 */
const PREFIX = '[PathFinder]';

function ts(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

export const logger = {
  info: (msg: string, ...args: unknown[]): void => {
    console.log(`${ts()} ${PREFIX} [INFO] ${msg}`, ...args);
  },
  warn: (msg: string, ...args: unknown[]): void => {
    console.warn(`${ts()} ${PREFIX} [WARN] ${msg}`, ...args);
  },
  error: (msg: string, ...args: unknown[]): void => {
    console.error(`${ts()} ${PREFIX} [ERROR] ${msg}`, ...args);
  },
};
