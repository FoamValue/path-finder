import { describe, expect, it, vi } from 'vitest';
import { runUpload, type UploadApi } from './uploadTask';

function mockApi(overrides: Partial<UploadApi> = {}): UploadApi & {
  calls: { chunks: number[]; merged: boolean; confirmed: boolean; tickets: number };
} {
  const calls = { chunks: [] as number[], merged: false, confirmed: false, tickets: 0 };
  const api: UploadApi = {
    uploadTicket: vi.fn(async () => {
      calls.tickets++;
      return { identifier: 'id-1', fileId: 1 };
    }),
    getProgress: vi.fn(async () => ({ uploadedChunks: [] })),
    uploadChunk: vi.fn(async (_form: FormData) => undefined),
    mergeAsync: vi.fn(async () => {
      calls.merged = true;
      return undefined;
    }),
    mergeStatus: vi.fn(async () => ({ state: 'SUCCEEDED' })),
    confirm: vi.fn(async () => {
      calls.confirmed = true;
      return undefined;
    }),
    ...overrides,
  };
  return { ...api, calls };
}

/** 追踪 uploadChunk 的 FormData 中 chunkIndex */
function trackChunks(api: UploadApi): { chunks: number[] } {
  const state = { chunks: [] as number[] };
  const original = api.uploadChunk;
  (api as { uploadChunk: (f: FormData) => Promise<void> }).uploadChunk = async (form) => {
    state.chunks.push(Number(form.get('chunkIndex')));
    return original(form);
  };
  return state;
}

function makeFile(name: string, size: number): File {
  const buf = new Uint8Array(size);
  return new File([buf], name);
}

describe('runUpload（分片上传核心流程）', () => {
  it('单分片上传成功：mergeAsync → SUCCEEDED → confirm', async () => {
    const api = mockApi();
    const progress: number[] = [];
    await runUpload(makeFile('a.txt', 10), 'PUBLIC', undefined, {
      api,
      pollIntervalMs: 1,
      onProgress: (p) => progress.push(p),
    });
    expect(api.calls.tickets).toBe(1);
    expect(api.calls.merged).toBe(true);
    expect(api.calls.confirmed).toBe(true);
    expect(progress[progress.length - 1]).toBe(100);
  });

  it('多分片按序上传', async () => {
    const api = mockApi();
    const { chunks } = trackChunks(api);
    // 8MB → 2 分片（CHUNK_SIZE=5MB）
    const file = makeFile('big.bin', 8 * 1024 * 1024);
    await runUpload(file, 'PUBLIC', undefined, { api, pollIntervalMs: 1 });
    expect(chunks).toEqual([0, 1]);
  });

  it('断点续传：已上传分片被跳过', async () => {
    const api = mockApi({
      getProgress: vi.fn(async () => ({ uploadedChunks: [0] })),
    });
    const { chunks } = trackChunks(api);
    await runUpload(makeFile('resume.bin', 8 * 1024 * 1024), 'PUBLIC', undefined, {
      api,
      pollIntervalMs: 1,
    });
    expect(chunks).toEqual([1]);
  });

  it('合并 FAILED 时抛错且不调用 confirm', async () => {
    const api = mockApi({ mergeStatus: vi.fn(async () => ({ state: 'FAILED' })) });
    await expect(
      runUpload(makeFile('a.txt', 10), 'PUBLIC', undefined, { api, pollIntervalMs: 1 }),
    ).rejects.toThrow('合并失败');
    expect(api.calls.confirmed).toBe(false);
  });

  it('合并超时（长时间 RUNNING）时抛错且不 confirm', async () => {
    const api = mockApi({ mergeStatus: vi.fn(async () => ({ state: 'RUNNING' })) });
    await expect(
      runUpload(makeFile('a.txt', 10), 'PUBLIC', undefined, {
        api,
        pollIntervalMs: 1,
        maxPollRetries: 3,
      }),
    ).rejects.toThrow('合并超时');
    expect(api.calls.confirmed).toBe(false);
  });
});
