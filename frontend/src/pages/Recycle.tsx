import { useCallback, useEffect, useState } from 'react';
import { Button, Space, Table, Popconfirm, Tag, message } from 'antd';
import { del, get, post } from '../api/client';
import { formatSize } from '../utils/file';
import type { AuthUser, RecycleItem } from '../api/types';

interface BatchResult {
  success: number;
  failed: number;
  message: string;
}

export default function Recycle() {
  const [me, setMe] = useState<AuthUser | null>(null);
  const [data, setData] = useState<RecycleItem[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [selectedKeys, setSelectedKeys] = useState<number[]>([]);

  const fetchList = useCallback(async () => {
    const d = await get<{ list: RecycleItem[]; total: number }>(
      `/api/recycle/page?pageNum=${pageNum}&pageSize=${pageSize}`,
    );
    setData(d.list);
    setTotal(d.total);
  }, [pageNum, pageSize]);

  useEffect(() => {
    get<AuthUser>('/api/auth/me').then(setMe).catch(() => setMe(null));
  }, []);

  useEffect(() => {
    fetchList();
  }, [fetchList]);

  const isAdmin = me?.roleCode === 'ADMIN';

  const restore = async (id: number) => {
    await post(`/api/recycle/${id}/restore`);
    message.success('已恢复');
    fetchList();
  };

  const purge = async (id: number) => {
    await del(`/api/recycle/${id}/purge`);
    message.success('已物理清除');
    fetchList();
  };

  const batchRestore = async () => {
    if (!selectedKeys.length) {
      message.warning('请先勾选文件');
      return;
    }
    try {
      const r = await post<BatchResult>('/api/recycle/batchRestore', { fileIds: selectedKeys });
      message.success(r.message);
      setSelectedKeys([]);
      fetchList();
    } catch (e: any) {
      message.error(e.message || '批量恢复失败');
    }
  };

  const batchPurge = async () => {
    if (!selectedKeys.length) {
      message.warning('请先勾选文件');
      return;
    }
    try {
      const r = await post<BatchResult>('/api/recycle/batchPurge', { fileIds: selectedKeys });
      message.success(r.message);
      setSelectedKeys([]);
      fetchList();
    } catch (e: any) {
      message.error(e.message || '批量清除失败');
    }
  };

  const columns = [
    { title: '文件名', dataIndex: 'originalName', ellipsis: true, width: 280, render: (v: string) => v || '-' },
    { title: '类型', dataIndex: 'fileType', width: 100, render: (t: string) => (t ? <Tag>{t}</Tag> : '-') },
    { title: '大小', dataIndex: 'fileSize', width: 100, render: (v: number) => formatSize(v || 0) },
    { title: '删除时间', dataIndex: 'deletedAt', width: 170, render: (v: string) => v?.replace('T', ' ').slice(0, 19) },
    { title: '到期时间', dataIndex: 'expireAt', width: 170, render: (v: string) => v?.replace('T', ' ').slice(0, 19) },
    {
      title: '操作',
      width: 200,
      render: (_: unknown, row: RecycleItem) => (
        <Space>
          <Button size="small" type="primary" onClick={() => restore(row.fileId)}>
            恢复
          </Button>
          {isAdmin && (
            <Popconfirm title="将物理删除该文件，不可恢复" onConfirm={() => purge(row.fileId)}>
              <Button size="small" danger>
                物理清除
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <>
      <Space style={{ marginBottom: 12 }} wrap>
        <Button type="primary" onClick={batchRestore} disabled={!selectedKeys.length}>
          批量恢复
        </Button>
        {isAdmin && (
          <Popconfirm title={`确认物理清除选中的 ${selectedKeys.length} 个文件？不可恢复`} onConfirm={batchPurge}>
            <Button danger disabled={!selectedKeys.length}>
              批量物理清除
            </Button>
          </Popconfirm>
        )}
      </Space>
      <Table
        rowKey="id"
        columns={columns}
        dataSource={data}
        rowSelection={{ selectedRowKeys: selectedKeys, onChange: (keys) => setSelectedKeys(keys as number[]) }}
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => {
            setPageNum(p);
            setPageSize(s);
          },
        }}
      />
    </>
  );
}
