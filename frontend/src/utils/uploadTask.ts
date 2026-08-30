import { get, post } from '../api/client';
import { chunkMd5 } from './file';

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

export const defaultUploadApi: UploadApi = {
  uploadTicket: (p) =>
    post('/api/file/uploadTicket', {
      fileName: p.fileName,
      fileSize: p.fileSize,
      spaceType: p.spaceType,
      deptId: p.deptId,
    }),
  getProgress: (identifier) => get(`/upload?action=progress&identifier=${identifier}`),
  uploadChunk: async (form) => {
    const resp = await fetch('/upload', {
      method: 'POST',
      headers: { Authorization: `Bearer ${localStorage.getItem('pf_token')}` },
      body: form,
    });
    if (!resp.ok) {
      throw new Error(`分片上传失败（HTTP ${resp.status}）`);
    }
  },
  mergeAsync: (identifier) => post(`/upload?action=mergeAsync&identifier=${identifier}`),
  mergeStatus: (identifier) => get(`/upload?action=mergeStatus&identifier=${identifier}`),
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

  const ticket = await api.uploadTicket({
    fileName: file.name,
    fileSize: file.size,
    spaceType,
    deptId: spaceType === 'DEPT' ? deptId ?? null : null,
  });
  const { identifier, fileId } = ticket;
  const chunkTotal = Math.max(1, Math.ceil(file.size / CHUNK_SIZE));

  // 断点续传：查询已上传分片并跳过
  const progress = await api.getProgress(identifier);
  const uploaded = new Set(progress.uploadedChunks || []);

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
    await api.uploadChunk(form);
    onProgress?.(Math.round(((i + 1) / chunkTotal) * 90));
  }

  onProgress?.(92);
  await api.mergeAsync(identifier);

  // 轮询合并状态：SUCCEEDED 继续，FAILED 抛错，超时抛错
  let state = 'PENDING';
  let attempts = 0;
  while (attempts < maxRetries) {
    await sleep(pollIntervalMs);
    attempts++;
    state = (await api.mergeStatus(identifier)).state;
    if (state === 'SUCCEEDED') {
      break;
    }
    if (state === 'FAILED') {
      throw new Error('合并失败，请重新上传');
    }
  }
  if (state !== 'SUCCEEDED') {
    throw new Error('合并超时，请稍后在列表中确认或重新上传');
  }

  onProgress?.(97);
  await api.confirm(fileId);
  onProgress?.(100);
}
