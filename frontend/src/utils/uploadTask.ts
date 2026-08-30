import { post } from '../api/client';
import { chunkMd5 } from './file';
import { logger } from './logger';

export const CHUNK_SIZE = 5 * 1024 * 1024;
export const DEFAULT_POLL_INTERVAL_MS = 1000;
export const DEFAULT_MAX_POLL_RETRIES = 60;

export interface UploadApi {
  uploadTicket(p: {
    fileName: string;
    fileSize: number;
    spaceType: string;
    deptId: number | null;
  }): Promise<{ identifier: string; fileId: number }>;
  getProgress(identifier: string): Promise<{ uploadedChunks?: number[] }>;
  uploadChunk(form: FormData): Promise<void>;
  mergeAsync(identifier: string): Promise<unknown>;
  mergeStatus(identifier: string): Promise<{ state: string }>;
  confirm(fileId: number): Promise<unknown>;
}

const authHeaders = (): Record<string, string> => ({
  Authorization: `Bearer ${localStorage.getItem('pf_token')}`,
});

/**
 * 注意：与组件端点（/upload?action=progress|mergeAsync|mergeStatus）交互返回的是
 * 组件裸对象（无 {code,...} 包装），不能走 client.ts 的 ApiResponse 校验，需直接 fetch。
 */
export const defaultUploadApi: UploadApi = {
  uploadTicket: (p) =>
    post('/api/file/uploadTicket', {
      fileName: p.fileName,
      fileSize: p.fileSize,
      spaceType: p.spaceType,
      deptId: p.deptId,
    }),
  getProgress: async (identifier) => {
    const resp = await fetch(`/upload?action=progress&identifier=${identifier}`, { headers: authHeaders() });
    if (!resp.ok) {
      throw new Error(`查询进度失败（HTTP ${resp.status}）`);
    }
    return resp.json();
  },
  uploadChunk: async (form) => {
    const resp = await fetch('/upload', {
      method: 'POST',
      headers: authHeaders(),
      body: form,
    });
    if (!resp.ok) {
      const body = await resp.text();
      logger.error(`分片上传失败 HTTP ${resp.status}`, body.slice(0, 500));
      throw new Error(`分片上传失败（HTTP ${resp.status}）：${body.slice(0, 200)}`);
    }
  },
  mergeAsync: async (identifier) => {
    const resp = await fetch(`/upload?action=mergeAsync&identifier=${identifier}`, {
      method: 'POST',
      headers: authHeaders(),
    });
    if (!resp.ok) {
      throw new Error(`提交合并失败（HTTP ${resp.status}）`);
    }
    return resp.json();
  },
  mergeStatus: async (identifier) => {
    const resp = await fetch(`/upload?action=mergeStatus&identifier=${identifier}`, { headers: authHeaders() });
    if (!resp.ok) {
      throw new Error(`查询合并状态失败（HTTP ${resp.status}）`);
    }
    return resp.json();
  },
  confirm: (fileId) => post(`/api/file/${fileId}/confirm`),
};

export interface RunUploadOptions {
  api?: UploadApi;
  pollIntervalMs?: number;
  maxPollRetries?: number;
  sleep?: (ms: number) => Promise<void>;
  onProgress?: (percent: number) => void;
}

const defaultSleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/**
 * 分片上传核心流程：uploadTicket → 分片（断点续传跳过已传） → mergeAsync 轮询 → confirm。
 * 轮询超时或合并失败时抛错，调用方据此标记失败。
 */
export async function runUpload(
  file: File,
  spaceType: string,
  deptId: number | undefined,
  options: RunUploadOptions = {},
): Promise<void> {
  const api = options.api ?? defaultUploadApi;
  const pollIntervalMs = options.pollIntervalMs ?? DEFAULT_POLL_INTERVAL_MS;
  const maxRetries = options.maxPollRetries ?? DEFAULT_MAX_POLL_RETRIES;
  const sleep = options.sleep ?? defaultSleep;
  const onProgress = options.onProgress;

  logger.info(`[上传] 开始 file=${file.name} size=${file.size} space=${spaceType}`);
  const ticket = await api.uploadTicket({
    fileName: file.name,
    fileSize: file.size,
    spaceType,
    deptId: spaceType === 'DEPT' ? deptId ?? null : null,
  });
  const { identifier, fileId } = ticket;
  const chunkTotal = Math.max(1, Math.ceil(file.size / CHUNK_SIZE));
  logger.info(`[上传] uploadTicket 成功 identifier=${identifier} fileId=${fileId} chunkTotal=${chunkTotal}`);

  // 断点续传：查询已上传分片并跳过
  const progress = await api.getProgress(identifier);
  const uploaded = new Set(progress.uploadedChunks || []);
  logger.info(`[上传] 已上传分片=${[...uploaded].join(',') || '无'}`);

  for (let i = 0; i < chunkTotal; i++) {
    if (uploaded.has(i)) {
      continue;
    }
    const start = i * CHUNK_SIZE;
    const blob = file.slice(start, Math.min(start + CHUNK_SIZE, file.size));
    const form = new FormData();
    form.append('file', blob, `chunk-${i}`);
    form.append('identifier', identifier);
    form.append('fileName', file.name);
    form.append('fileSize', String(file.size));
    form.append('chunkSize', String(CHUNK_SIZE));
    form.append('chunkTotal', String(chunkTotal));
    form.append('chunkIndex', String(i));
    form.append('chunkMd5', await chunkMd5(blob));
    try {
      await api.uploadChunk(form);
      logger.info(`[上传] 分片 ${i + 1}/${chunkTotal} 成功`);
    } catch (e: any) {
      logger.error(`[上传] 分片 ${i + 1}/${chunkTotal} 失败`, e.message);
      throw e;
    }
    onProgress?.(Math.round(((i + 1) / chunkTotal) * 90));
  }

  onProgress?.(92);
  await api.mergeAsync(identifier);
  logger.info('[上传] 已提交异步合并 mergeAsync');

  // 轮询合并状态：SUCCEEDED 继续，FAILED 抛错，超时抛错
  let state = 'PENDING';
  let attempts = 0;
  while (attempts < maxRetries) {
    await sleep(pollIntervalMs);
    attempts++;
    state = (await api.mergeStatus(identifier)).state;
    logger.info(`[上传] mergeStatus 第 ${attempts} 次查询 state=${state}`);
    if (state === 'SUCCEEDED') {
      break;
    }
    if (state === 'FAILED') {
      throw new Error('合并失败，请重新上传');
    }
  }
  if (state !== 'SUCCEEDED') {
    logger.error(`[上传] 合并超时 state=${state} attempts=${attempts}`);
    throw new Error('合并超时，请稍后在列表中确认或重新上传');
  }

  onProgress?.(97);
  await api.confirm(fileId);
  logger.info(`[上传] confirm 成功 fileId=${fileId}`);
  onProgress?.(100);
}
