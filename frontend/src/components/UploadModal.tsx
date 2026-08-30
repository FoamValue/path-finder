import { useRef, useState } from 'react';
import { Modal, Upload, Select, Space, Progress, message, Button } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';
import { formatSize } from '../utils/file';
import { runUpload } from '../utils/uploadTask';
import type { DeptNode } from '../api/types';

interface UploadItem {
  uid: string;
  name: string;
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

  const reset = () => {
    setFileList([]);
    setItems([]);
    setSpaceType('PERSONAL');
    setDeptId(undefined);
    setUploading(false);
    abortRef.current = false;
  };

  const close = () => {
    if (uploading) return;
    reset();
    onClose();
  };

  const updateItem = (uid: string, patch: Partial<UploadItem>) => {
    setItems((prev) => prev.map((it) => (it.uid === uid ? { ...it, ...patch } : it)));
  };

  const startUpload = async () => {
    if (!fileList.length) return;
    if (spaceType === 'DEPT' && !deptId) {
      message.warning('部门空间必须选择部门');
      return;
    }
    setUploading(true);
    setItems(
      fileList.map((f) => ({
        uid: f.uid,
        name: f.name,
        progress: 0,
        status: 'uploading' as const,
      })),
    );
    abortRef.current = false;

    // 用局部变量跟踪失败，避免读取过期 state
    let errorCount = 0;
    for (const f of fileList) {
      const file = f.originFileObj as File;
      try {
        await runUpload(file, spaceType, deptId, {
          onProgress: (p) => updateItem(f.uid, { progress: p }),
        });
        updateItem(f.uid, { progress: 100, status: 'done' });
      } catch (e: any) {
        errorCount++;
        updateItem(f.uid, { status: 'error', error: e.message || '上传失败' });
      }
    }
    setUploading(false);
    if (errorCount === 0) {
      message.success('上传完成');
      onSuccess();
      reset();
    } else if (errorCount === fileList.length) {
      message.error('上传失败，请检查后重试');
    } else {
      message.warning(`${fileList.length - errorCount} 个成功，${errorCount} 个失败`);
    }
  };

  const stopUpload = () => {
    abortRef.current = true;
    setUploading(false);
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
          {uploading ? (
            <Button danger onClick={stopUpload}>
              停止
            </Button>
          ) : (
            <Button type="primary" onClick={startUpload}>
              开始上传
            </Button>
          )}
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
          <div key={it.uid}>
            <Space style={{ justifyContent: 'space-between', width: '100%' }}>
              <span
                style={{
                  fontSize: 12,
                  maxWidth: 220,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {it.name}
              </span>
              <span style={{ fontSize: 12, color: '#999' }}>
                {fileList.find((f) => f.uid === it.uid)?.size != null
                  ? formatSize(fileList.find((f) => f.uid === it.uid)!.size!)
                  : ''}
              </span>
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
