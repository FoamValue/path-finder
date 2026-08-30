import { useCallback, useRef, useState } from 'react';
import { Modal, Upload, Select, Space, Progress, message, Button } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';
import { get, post } from '../api/client';
import { chunkMd5, formatSize } from '../utils/file';
import type { DeptNode, UploadTicket } from '../api/types';

const CHUNK_SIZE = 5 * 1024 * 1024;

interface UploadItem {
  file: File;
  progress: number;
  status: 'pending' | 'uploading' | 'merging' | 'done' | 'error';
  error?: string;
}

interface Props {
  open: boolean;
  onClose: () => void;
  onSuccess: () => void;
  deptTree: DeptNode[];
}

function flatDepts(nodes: DeptNode[], depth = 0): { id: number; label: string }[] {
  const out: { id: number; label: string }[] = [];
  for (const n of nodes) {
    out.push({ id: n.id, label: `${'　'.repeat(depth)}${n.name}` });
    out.push(...flatDepts(n.children || [], depth + 1));
  }
  return out;
}

export default function UploadModal({ open, onClose, onSuccess, deptTree }: Props) {
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [spaceType, setSpaceType] = useState('PERSONAL');
  const [deptId, setDeptId] = useState<number | undefined>();
  const [items, setItems] = useState<UploadItem[]>([]);
  const [uploading, setUploading] = useState(false);
  const abortRef = useRef(false);

  const reset = useCallback(() => {
    setFileList([]);
    setItems([]);
    setSpaceType('PERSONAL');
    setDeptId(undefined);
    setUploading(false);
    abortRef.current = false;
  }, []);

  const close = () => {
    if (uploading) return;
    reset();
    onClose();
  };

  const updateItem = (name: string, patch: Partial<UploadItem>) => {
    setItems((prev) => prev.map((it) => (it.file.name === name ? { ...it, ...patch } : it)));
  };

  const uploadOne = async (file: File) => {
    const ticket = await post<UploadTicket>('/api/file/uploadTicket', {
      fileName: file.name,
      fileSize: file.size,
      spaceType,
      deptId: spaceType === 'DEPT' ? deptId : null,
    });
    const { identifier, fileId } = ticket;
    const chunkTotal = Math.max(1, Math.ceil(file.size / CHUNK_SIZE));

    // 断点续传：查询已上传分片
    const progress = await get<{ uploadedChunks?: number[] }>(
      `/upload?action=progress&identifier=${identifier}`,
    );
    const uploaded = new Set(progress.uploadedChunks || []);

    for (let i = 0; i < chunkTotal; i++) {
      if (abortRef.current) throw new Error('已取消');
      if (uploaded.has(i)) continue;
      const start = i * CHUNK_SIZE;
      const blob = file.slice(start, Math.min(start + CHUNK_SIZE, file.size));
      const md5 = await chunkMd5(blob);
      const form = new FormData();
      form.append('file', blob, `chunk-${i}`);
      form.append('identifier', identifier);
      form.append('fileName', file.name);
      form.append('fileSize', String(file.size));
      form.append('chunkSize', String(CHUNK_SIZE));
      form.append('chunkTotal', String(chunkTotal));
      form.append('chunkIndex', String(i));
      form.append('chunkMd5', md5);
      const resp = await fetch('/upload', {
        method: 'POST',
        headers: { Authorization: `Bearer ${localStorage.getItem('pf_token')}` },
        body: form,
      });
      if (!resp.ok) {
        const t = await resp.text();
        throw new Error(`分片 ${i} 上传失败：${t}`);
      }
      updateItem(file.name, { progress: Math.round(((i + 1) / chunkTotal) * 90) });
    }

    updateItem(file.name, { progress: 92, status: 'merging' });
    await post(`/upload?action=mergeAsync&identifier=${identifier}`);
    for (let retry = 0; retry < 60; retry++) {
      if (abortRef.current) throw new Error('已取消');
      await new Promise((r) => setTimeout(r, 1000));
      const st = await get<{ state: string }>(
        `/upload?action=mergeStatus&identifier=${identifier}`,
      );
      if (st.state === 'SUCCEEDED') break;
      if (st.state === 'FAILED') throw new Error('合并失败');
    }
    updateItem(file.name, { progress: 97 });
    await post(`/api/file/${fileId}/confirm`);
    updateItem(file.name, { progress: 100, status: 'done' });
  };

  const startUpload = async () => {
    if (!fileList.length) return;
    if (spaceType === 'DEPT' && !deptId) {
      message.warning('部门空间必须选择部门');
      return;
    }
    setUploading(true);
    setItems(fileList.map((f) => ({ file: f.originFileObj as File, progress: 0, status: 'uploading' })));
    abortRef.current = false;
    for (const f of fileList) {
      const file = f.originFileObj as File;
      try {
        await uploadOne(file);
      } catch (e: any) {
        updateItem(file.name, { status: 'error', error: e.message || '上传失败' });
      }
    }
    const failed = items.some((it) => it.status === 'error');
    setUploading(false);
    if (!failed) {
      message.success('上传完成');
      onSuccess();
      reset();
    }
  };

  const doneCount = items.filter((it) => it.status === 'done').length;

  return (
    <Modal
      title="上传文件"
      open={open}
      onCancel={close}
      footer={
        <Space>
          <Button onClick={close} disabled={uploading}>
            取消
          </Button>
          <Button
            type="primary"
            loading={uploading}
            onClick={uploading ? () => (abortRef.current = true) : startUpload}
          >
            {uploading ? '停止' : '开始上传'}
          </Button>
        </Space>
      }
    >
      <Space direction="vertical" style={{ width: '100%' }}>
        <Upload.Dragger
          multiple
          beforeUpload={() => false}
          fileList={fileList}
          onChange={({ fileList: fl }) => setFileList(fl)}
          disabled={uploading}
        >
          <p className="ant-upload-drag-icon">
            <InboxOutlined />
          </p>
          <p className="ant-upload-text">点击或拖拽文件到此区域上传</p>
          <p className="ant-upload-hint">支持大文件分片上传与断点续传</p>
        </Upload.Dragger>
        <Space>
          <Select
            value={spaceType}
            onChange={(v) => setSpaceType(v)}
            style={{ width: 140 }}
            options={[
              { value: 'PERSONAL', label: '个人空间' },
              { value: 'DEPT', label: '部门空间' },
              { value: 'PUBLIC', label: '公共空间' },
            ]}
          />
          {spaceType === 'DEPT' && (
            <Select
              showSearch
              placeholder="选择部门"
              style={{ width: 180 }}
              value={deptId}
              onChange={setDeptId}
              options={flatDepts(deptTree)}
              optionFilterProp="label"
            />
          )}
        </Space>
        {items.map((it) => (
          <div key={it.file.name}>
            <Space style={{ justifyContent: 'space-between', width: '100%' }}>
              <span style={{ fontSize: 12, maxWidth: 220, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {it.file.name}
              </span>
              <span style={{ fontSize: 12, color: '#999' }}>{formatSize(it.file.size)}</span>
            </Space>
            <Progress
              percent={it.progress}
              status={it.status === 'error' ? 'exception' : it.status === 'done' ? 'success' : 'active'}
              size="small"
            />
            {it.error && <div style={{ color: '#ff4d4f', fontSize: 12 }}>{it.error}</div>}
          </div>
        ))}
      </Space>
    </Modal>
  );
}
