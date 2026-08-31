import { useRef, useState } from 'react';
import { Modal, Upload, Select, Space, Progress, message, Button, Typography } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';
import { formatSize } from '../utils/file';
import { runUpload } from '../utils/uploadTask';
import { logger } from '../utils/logger';
import { flatDepts } from '../utils/dept';
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

export default function UploadModal({ open, onClose, onSuccess, deptTree }: Props) {
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [spaceType, setSpaceType] = useState('PERSONAL');
  const [deptId, setDeptId] = useState<number | undefined>();
  const [items, setItems] = useState<UploadItem[]>([]);
  const [uploading, setUploading] = useState(false);
  const abortRef = useRef(false);
  const queueRef = useRef<UploadFile[]>([]);
  const processingRef = useRef(false);
  const handledUidsRef = useRef(new Set<string>());

  const resetFiles = () => {
    setFileList([]);
    setItems([]);
    setUploading(false);
    abortRef.current = false;
    queueRef.current = [];
    processingRef.current = false;
    handledUidsRef.current.clear();
  };

  const reset = () => {
    resetFiles();
    setSpaceType('PERSONAL');
    setDeptId(undefined);
  };

  const close = () => {
    if (uploading) return;
    reset();
    onClose();
  };

  const updateItem = (uid: string, patch: Partial<UploadItem>) => {
    setItems((prev) => prev.map((it) => (it.uid === uid ? { ...it, ...patch } : it)));
  };

  const processQueue = async () => {
    let errorCount = 0;
    let total = 0;
    while (queueRef.current.length && !abortRef.current) {
      const f = queueRef.current.shift()!;
      total++;
      const file = f.originFileObj as File;
      logger.info(`[UploadModal] 自动上传 uid=${f.uid} name=${file.name} size=${file.size}`);
      try {
        await runUpload(file, spaceType, deptId, {
          onProgress: (p) => updateItem(f.uid, { progress: p }),
        });
        updateItem(f.uid, { progress: 100, status: 'done' });
        logger.info(`[UploadModal] 上传完成 ${file.name}`);
      } catch (e: any) {
        errorCount++;
        logger.error(`[UploadModal] 上传失败 ${file.name}`, e.message, e);
        updateItem(f.uid, { status: 'error', error: e.message || '上传失败' });
      }
    }
    const aborted = abortRef.current;
    abortRef.current = false;
    processingRef.current = false;
    setUploading(false);
    if (aborted) {
      queueRef.current.forEach((f) => updateItem(f.uid, { status: 'pending', progress: 0 }));
      queueRef.current = [];
      message.warning('已停止上传');
      return;
    }
    if (total === 0) return;
    if (errorCount === 0) {
      message.success('上传完成');
      onSuccess();
      resetFiles();
    } else if (errorCount === total) {
      message.error('上传失败，请检查后重试');
    } else {
      message.warning(`${total - errorCount} 个成功，${errorCount} 个失败`);
    }
  };

  const enqueue = (files: UploadFile[]) => {
    queueRef.current.push(...files);
    if (!processingRef.current) {
      processingRef.current = true;
      setUploading(true);
      void processQueue();
    }
  };

  const handleChange = ({ fileList: fl }: { fileList: UploadFile[] }) => {
    setFileList(fl);
    const newFiles = fl.filter((f) => !handledUidsRef.current.has(f.uid));
    if (!newFiles.length) return;
    if (spaceType === 'DEPT' && !deptId) {
      message.warning('部门空间必须选择部门，请先选择部门再上传');
      setFileList(fl.filter((f) => !newFiles.includes(f)));
      return;
    }
    newFiles.forEach((f) => {
      handledUidsRef.current.add(f.uid);
      setItems((prev) => [...prev, { uid: f.uid, name: f.name, progress: 0, status: 'uploading' }]);
    });
    enqueue(newFiles);
  };

  const stopUpload = () => {
    abortRef.current = true;
  };

  return (
    <Modal
      title="上传文件"
      open={open}
      onCancel={close}
      footer={
        <Space>
          <Button onClick={close} disabled={uploading}>
            关闭
          </Button>
          {uploading && (
            <Button danger onClick={stopUpload}>
              停止
            </Button>
          )}
        </Space>
      }
    >
      <Space direction="vertical" style={{ width: '100%' }}>
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
        <Upload.Dragger
          multiple
          beforeUpload={() => false}
          fileList={fileList}
          onChange={handleChange}
          disabled={uploading}
        >
          <p className="ant-upload-drag-icon">
            <InboxOutlined />
          </p>
          <p className="ant-upload-text">点击或拖拽文件到此区域上传</p>
          <p className="ant-upload-hint">选择文件后自动上传，支持多文件批量上传、大文件分片与断点续传</p>
        </Upload.Dragger>
        <Typography.Text type="secondary">
          {uploading ? '正在自动上传，请勿关闭窗口…' : '选择文件后将自动开始上传'}
        </Typography.Text>
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
