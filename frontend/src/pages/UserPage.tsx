import { useCallback, useEffect, useState } from 'react';
import { Button, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, message } from 'antd';
import { get, post, put, del } from '../api/client';
import type { DeptNode, UserVo } from '../api/types';

export default function UserPage() {
  const [data, setData] = useState<UserVo[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [deptTree, setDeptTree] = useState<DeptNode[]>([]);
  const [editing, setEditing] = useState<UserVo | null>(null);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();

  const fetchDepts = useCallback(() => {
    get<DeptNode[]>('/api/dept/tree').then(setDeptTree).catch(() => setDeptTree([]));
  }, []);

  const fetchList = useCallback(async () => {
    const d = await get<{ list: UserVo[]; total: number }>(
      `/api/user/page?pageNum=${pageNum}&pageSize=${pageSize}`,
    );
    setData(d.list);
    setTotal(d.total);
  }, [pageNum, pageSize]);

  useEffect(() => {
    fetchDepts();
    fetchList();
  }, [fetchDepts, fetchList]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setOpen(true);
  };

  const openEdit = (u: UserVo) => {
    setEditing(u);
    form.setFieldsValue({
      username: u.username,
      realName: u.realName,
      deptId: u.deptId,
      roleCode: u.roleCode,
    });
    setOpen(true);
  };

  const submit = async () => {
    const v = await form.validateFields();
    if (editing) {
      await put(`/api/user/${editing.id}`, v);
    } else {
      await post('/api/user', v);
    }
    message.success(editing ? '已更新' : '已创建');
    setOpen(false);
    fetchList();
  };

  const toggleStatus = async (u: UserVo) => {
    await put(`/api/user/${u.id}/status?status=${u.status === 1 ? 0 : 1}`);
    message.success('已更新状态');
    fetchList();
  };

  const resetPwd = async (u: UserVo) => {
    await put(`/api/user/${u.id}/resetPassword`);
    message.success('密码已重置为 Init@123');
    fetchList();
  };

  const remove = async (u: UserVo) => {
    await del(`/api/user/${u.id}`);
    message.success('已删除');
    fetchList();
  };

  const columns = [
    { title: '用户名', dataIndex: 'username', width: 120 },
    { title: '姓名', dataIndex: 'realName', width: 120 },
    { title: '部门', dataIndex: 'deptName', width: 120 },
    {
      title: '角色',
      dataIndex: 'roleCode',
      width: 110,
      render: (r: string) => {
        const map: Record<string, string> = { ADMIN: 'red', DEPT_ADMIN: 'orange', USER: 'blue', VIEWER: 'default' };
        return <Tag color={map[r] || 'default'}>{r}</Tag>;
      },
    },
    { title: '状态', dataIndex: 'status', width: 80, render: (s: number) => (s === 1 ? <Tag color="green">启用</Tag> : <Tag color="red">停用</Tag>) },
    { title: '创建时间', dataIndex: 'createdAt', render: (v: string) => v?.replace('T', ' ').slice(0, 19) },
    {
      title: '操作',
      width: 260,
      render: (_: unknown, row: UserVo) => (
        <Space size="small">
          <Button size="small" onClick={() => openEdit(row)}>
            编辑
          </Button>
          <Button size="small" onClick={() => toggleStatus(row)}>
            {row.status === 1 ? '停用' : '启用'}
          </Button>
          <Button size="small" onClick={() => resetPwd(row)}>
            重置密码
          </Button>
          <Popconfirm title="确认删除？有个人文件时将被阻止" onConfirm={() => remove(row)}>
            <Button size="small" danger>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <>
      <Space style={{ marginBottom: 12 }}>
        <Button type="primary" onClick={openCreate}>
          新增用户
        </Button>
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
      <Modal
        title={editing ? '编辑用户' : '新增用户'}
        open={open}
        onCancel={() => setOpen(false)}
        onOk={submit}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input disabled={!!editing} />
          </Form.Item>
          <Form.Item name="realName" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="deptId" label="部门" rules={[{ required: true, message: '请选择部门' }]}>
            <Select
              options={deptTree.map((d) => ({ value: d.id, label: d.name }))}
              placeholder="选择部门"
            />
          </Form.Item>
          <Form.Item name="roleCode" label="角色" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'ADMIN', label: '系统管理员' },
                { value: 'DEPT_ADMIN', label: '部门管理员' },
                { value: 'USER', label: '普通员工' },
                { value: 'VIEWER', label: '访客' },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
