import { useCallback, useEffect, useState } from 'react';
import { Input, Select, Space, Table, Tag } from 'antd';
import { get } from '../api/client';
import type { OperationLog } from '../api/types';

const TYPE_COLORS: Record<string, string> = {
  LOGIN: 'blue',
  LOGOUT: 'default',
  UPLOAD: 'green',
  DOWNLOAD: 'cyan',
  DELETE: 'red',
  RESTORE: 'green',
  PURGE: 'volcano',
  RENAME: 'gold',
  OWNER_CHANGE: 'purple',
  PASSWORD: 'geekblue',
  USER_CREATE: 'blue',
  STORAGE_ALERT: 'red',
};

export default function LogPage() {
  const [data, setData] = useState<OperationLog[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [operatorName, setOperatorName] = useState('');
  const [operationType, setOperationType] = useState<string | undefined>();

  const fetchList = useCallback(async () => {
    const params = new URLSearchParams({ pageNum: String(pageNum), pageSize: String(pageSize) });
    if (operatorName) params.set('operatorName', operatorName);
    if (operationType) params.set('operationType', operationType);
    const d = await get<{ list: OperationLog[]; total: number }>(`/api/log/page?${params}`);
    setData(d.list);
    setTotal(d.total);
  }, [pageNum, pageSize, operatorName, operationType]);

  useEffect(() => {
    fetchList();
  }, [fetchList]);

  const columns = [
    { title: '操作人', dataIndex: 'operatorName', width: 110 },
    { title: '类型', dataIndex: 'operationType', width: 130, render: (t: string) => <Tag color={TYPE_COLORS[t] || 'default'}>{t}</Tag> },
    { title: '目标', dataIndex: 'targetName', ellipsis: true },
    { title: '详情', dataIndex: 'detail', ellipsis: true },
    { title: '结果', dataIndex: 'success', width: 70, render: (s: number) => (s === 1 ? <Tag color="green">成功</Tag> : <Tag color="red">失败</Tag>) },
    { title: '时间', dataIndex: 'createdAt', width: 170, render: (v: string) => v?.replace('T', ' ').slice(0, 19) },
  ];

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Space>
        <Input.Search placeholder="操作人" allowClear style={{ width: 180 }} onSearch={(v) => { setOperatorName(v); setPageNum(1); }} />
        <Select
          allowClear
          placeholder="操作类型"
          style={{ width: 180 }}
          value={operationType}
          onChange={(v) => { setOperationType(v); setPageNum(1); }}
          options={Object.keys(TYPE_COLORS).map((t) => ({ value: t, label: t }))}
        />
      </Space>
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
    </Space>
  );
}
