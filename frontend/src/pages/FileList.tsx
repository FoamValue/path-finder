import { useCallback, useEffect, useState } from 'react';
import {
  Button,
  Input,
  Select,
  Space,
  Table,
  Modal,
  Form,
  message,
  Tag,
  Popconfirm,
  Typography,
} from 'antd';
import { UploadOutlined, DownloadOutlined, DeleteOutlined, EditOutlined, SwapOutlined } from '@ant-design/icons';
import { get, post, put, del } from '../api/client';
import { formatSize } from '../utils/file';
import { flatDepts } from '../utils/dept';
import UploadModal from '../components/UploadModal';
import type { AuthUser, DeptNode, FileInfo, UserVo } from '../api/types';

export default function FileList() {
  const [me, setMe] = useState<AuthUser | null>(null);
  const [data, setData] = useState<FileInfo[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [keyword, setKeyword] = useState('');
  const [spaceType, setSpaceType] = useState<string | undefined>();
  const [deptId, setDeptId] = useState<number | undefined>();
  const [deptTree, setDeptTree] = useState<DeptNode[]>([]);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [selectedKeys, setSelectedKeys] = useState<number[]>([]);

  // 归属变更弹窗状态
  const [ownerTarget, setOwnerTarget] = useState<FileInfo | null>(null);
  const [ownerSpace, setOwnerSpace] = useState('PERSONAL');
  const [ownerDept, setOwnerDept] = useState<number | undefined>();
  const [ownerUser, setOwnerUser] = useState<number | undefined>();
  const [users, setUsers] = useState<UserVo[]>([]);
  const [renameTarget, setRenameTarget] = useState<FileInfo | null>(null);
  const [renameForm] = Form.useForm();

  const fetchMe = useCallback(() => get<AuthUser>('/api/auth/me').then(setMe), []);

  const fetchDepts = useCallback(() => {
    get<DeptNode[]>('/api/dept/tree').then(setDeptTree).catch(() => setDeptTree([]));
  }, []);

  const fetchList = useCallback(async () => {
    const params = new URLSearchParams({
      pageNum: String(pageNum),
      pageSize: String(pageSize),
    });
    if (keyword) params.set('keyword', keyword);
    if (spaceType) params.set('spaceType', spaceType);
    if (deptId) params.set('deptId', String(deptId));
    const d = await get<{ list: FileInfo[]; total: number }>(`/api/file/page?${params}`);
    setData(d.list);
    setTotal(d.total);
  }, [pageNum, pageSize, keyword, spaceType, deptId]);

  useEffect(() => {
    fetchMe();
    fetchDepts();
  }, [fetchMe, fetchDepts]);
  useEffect(() => {
    fetchList();
  }, [fetchList]);

  const isAdmin = me?.roleCode === 'ADMIN';
  const canManage = me?.roleCode === 'ADMIN' || me?.roleCode === 'DEPT_ADMIN';

  const openOwnerModal = async (f: FileInfo) => {
    setOwnerTarget(f);
    setOwnerSpace(f.spaceType);
    setOwnerDept(f.deptId);
    setOwnerUser(undefined);
    if (canManage) {
      get<UserVo[]>('/api/user/page?pageSize=100').then((d: any) => setUsers(d.list || []));
    }
  };

  const confirmOwner = async () => {
    if (!ownerTarget) return;
    await put(`/api/file/${ownerTarget.id}/owner`, {
      spaceType: ownerSpace,
      deptId: ownerSpace === 'DEPT' ? ownerDept : null,
      ownerId: ownerUser,
    });
    message.success('归属变更成功');
    setOwnerTarget(null);
    fetchList();
  };

  const download = async (f: FileInfo) => {
    const d = await get<{ token: string }>(`/api/file/${f.id}/downloadToken`);
    const resp = await fetch(`/api/file/download/${d.token}`, {
      headers: { Authorization: `Bearer ${localStorage.getItem('pf_token')}` },
    });
    const blob = await resp.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = f.originalName;
    a.click();
    URL.revokeObjectURL(url);
  };

  const batchDownload = async () => {
    if (!selectedKeys.length) {
      message.warning('请先选择文件');
      return;
    }
    const d = await post<{ token: string }>('/api/file/batchDownload', { ids: selectedKeys });
    const resp = await fetch(`/api/file/download/${d.token}`, {
      headers: { Authorization: `Bearer ${localStorage.getItem('pf_token')}` },
    });
    const blob = await resp.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `batch-${Date.now()}.zip`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const spaceTag = (s: string) => {
    const map: Record<string, [string, string]> = {
      PERSONAL: ['个人', 'blue'],
      DEPT: ['部门', 'green'],
      PUBLIC: ['公共', 'orange'],
    };
    const [label, color] = map[s] || [s, 'default'];
    return <Tag color={color}>{label}</Tag>;
  };

  const columns = [
    { title: '文件名', dataIndex: 'originalName', ellipsis: true, width: 280 },
    { title: '类型', dataIndex: 'fileType', width: 80, render: (t: string) => (t ? <Tag>{t}</Tag> : '-') },
    { title: '大小', dataIndex: 'fileSize', width: 100, render: (v: number) => formatSize(v) },
    { title: '空间', dataIndex: 'spaceType', width: 80, render: (v: string) => spaceTag(v) },
    { title: '归属人', dataIndex: 'ownerName', width: 100 },
    { title: '上传人', dataIndex: 'creatorName', width: 100 },
    {
      title: '上传时间',
      dataIndex: 'createdAt',
      width: 170,
      render: (v: string) => (v ? v.replace('T', ' ').slice(0, 19) : '-'),
    },
    {
      title: '操作',
      width: 240,
      render: (_: unknown, row: FileInfo) => (
        <Space size="small">
          <Button size="small" icon={<DownloadOutlined />} onClick={() => download(row)}>
            下载
          </Button>
          <Button size="small" icon={<EditOutlined />} onClick={() => { setRenameTarget(row); renameForm.setFieldsValue({ newName: row.originalName }); }}>
            重命名
          </Button>
          <Button size="small" icon={<SwapOutlined />} onClick={() => openOwnerModal(row)}>
            归属
          </Button>
          <Popconfirm title="确认删除？文件将进入回收站" onConfirm={async () => { await del(`/api/file/${row.id}`); message.success('已删除'); fetchList(); }}>
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Space wrap>
        <Button type="primary" icon={<UploadOutlined />} onClick={() => setUploadOpen(true)}>
          上传文件
        </Button>
        <Button icon={<DownloadOutlined />} onClick={batchDownload} disabled={!selectedKeys.length}>
          批量下载
        </Button>
        <Input.Search
          placeholder="按文件名搜索"
          allowClear
          style={{ width: 240 }}
          onSearch={(v) => { setKeyword(v); setPageNum(1); }}
        />
        <Select
          allowClear
          placeholder="空间筛选"
          style={{ width: 130 }}
          value={spaceType}
          onChange={(v) => { setSpaceType(v); setPageNum(1); }}
          options={[
            { value: 'PERSONAL', label: '个人空间' },
            { value: 'DEPT', label: '部门空间' },
            { value: 'PUBLIC', label: '公共空间' },
          ]}
        />
        <Select
          allowClear
          showSearch
          placeholder="部门筛选"
          style={{ width: 180 }}
          value={deptId}
          onChange={(v) => { setDeptId(v); setPageNum(1); }}
          options={flatDepts(deptTree)}
          optionFilterProp="label"
        />
        <Typography.Text type="secondary">共 {total} 个文件</Typography.Text>
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
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => { setPageNum(p); setPageSize(s); },
        }}
        size="middle"
      />
      <UploadModal
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        onSuccess={fetchList}
        deptTree={deptTree}
      />
      <Modal
        title={`修改归属 - ${ownerTarget?.originalName || ''}`}
        open={!!ownerTarget}
        onCancel={() => setOwnerTarget(null)}
        onOk={confirmOwner}
      >
        <Space direction="vertical" style={{ width: '100%' }}>
          <Select
            value={ownerSpace}
            onChange={(v) => setOwnerSpace(v)}
            style={{ width: '100%' }}
            options={[
              { value: 'PERSONAL', label: '个人空间' },
              { value: 'DEPT', label: '部门空间' },
              { value: 'PUBLIC', label: '公共空间' },
            ]}
          />
          {ownerSpace === 'DEPT' && (
            <Select
              showSearch
              placeholder="选择目标部门"
              style={{ width: '100%' }}
              value={ownerDept}
              onChange={setOwnerDept}
              options={flatDepts(deptTree)}
              optionFilterProp="label"
            />
          )}
          {canManage && (
            <Select
              allowClear
              placeholder="移交归属人（可选）"
              style={{ width: '100%' }}
              value={ownerUser}
              onChange={setOwnerUser}
              options={users.map((u) => ({ value: u.id, label: `${u.realName}(${u.username})` }))}
              optionFilterProp="label"
            />
          )}
        </Space>
      </Modal>
      <Modal
        title="重命名"
        open={!!renameTarget}
        onCancel={() => setRenameTarget(null)}
        onOk={async () => {
          const v = await renameForm.validateFields();
          await put(`/api/file/${renameTarget!.id}/rename`, v);
          message.success('重命名成功');
          setRenameTarget(null);
          fetchList();
        }}
      >
        <Form form={renameForm}>
          <Form.Item name="newName" rules={[{ required: true, message: '请输入新文件名' }]}>
            <Input />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
