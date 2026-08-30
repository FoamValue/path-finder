import { useCallback, useEffect, useState } from 'react';
import { Button, Space, Table, Popconfirm, message } from 'antd';
import { del, get, post } from '../api/client';
import type { RecycleItem } from '../api/types';

export default function Recycle() {
  const [data, setData] = useState<RecycleItem[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);

  const fetchList = useCallback(async () => {
    const d = await get<{ list: RecycleItem[]; total: number }>(
      `/api/recycle/page?pageNum=${pageNum}&pageSize=${pageSize}`,
    );
    setData(d.list);
    setTotal(d.total);
  }, [pageNum, pageSize]);

  useEffect(() => {
    fetchList();
  }, [fetchList]);

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

  const columns = [
    { title: '文件 ID', dataIndex: 'fileId', width: 100 },
    { title: '删除时间', dataIndex: 'deletedAt', width: 180, render: (v: string) => v?.replace('T', ' ').slice(0, 19) },
    { title: '到期时间', dataIndex: 'expireAt', width: 180, render: (v: string) => v?.replace('T', ' ').slice(0, 19) },
    {
      title: '操作',
      width: 200,
      render: (_: unknown, row: RecycleItem) => (
        <Space>
          <Button size="small" type="primary" onClick={() => restore(row.fileId)}>
            恢复
          </Button>
          <Popconfirm title="将物理删除该文件，不可恢复" onConfirm={() => purge(row.fileId)}>
            <Button size="small" danger>
              物理清除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Table
      rowKey="id"
      columns={columns}
      dataSource={data}
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
  );
}
