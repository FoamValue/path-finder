import { useCallback, useEffect, useState } from 'react';
import {
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Space,
  Table,
  Tag,
  message,
  Tooltip,
} from 'antd';
import { ApartmentOutlined, PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { del, get, post, put } from '../api/client';
import type { DeptNode } from '../api/types';

export default function DeptPage() {
  const [tree, setTree] = useState<DeptNode[]>([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [parentId, setParentId] = useState<number>(0);
  const [parentName, setParentName] = useState('');
  const [editing, setEditing] = useState<DeptNode | null>(null);
  const [form] = Form.useForm();

  const fetchTree = useCallback(async () => {
    setLoading(true);
    try {
      setTree(await get<DeptNode[]>('/api/dept/tree'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchTree();
  }, [fetchTree]);

  const createChild = (parent: DeptNode | null) => {
    setEditing(null);
    setParentId(parent ? parent.id : 0);
    setParentName(parent ? parent.name : '根组织');
    form.resetFields();
    setOpen(true);
  };

  const editDept = (node: DeptNode) => {
    setEditing(node);
    setParentId(node.parentId);
    setParentName(node.parentId === 0 ? '根组织' : findName(tree, node.parentId) || '');
    form.setFieldsValue({ name: node.name, sortOrder: node.sortOrder });
    setOpen(true);
  };

  const findName = (nodes: DeptNode[], id: number): string | undefined => {
    for (const n of nodes) {
      if (n.id === id) return n.name;
      if (n.children?.length) {
        const r = findName(n.children, id);
        if (r) return r;
      }
    }
    return undefined;
  };

  const submit = async () => {
    const v = await form.validateFields();
    if (editing) {
      await put(`/api/dept/${editing.id}`, v);
      message.success('部门已更新');
    } else {
      await post('/api/dept', { ...v, parentId });
      message.success('部门已创建');
    }
    setOpen(false);
    fetchTree();
  };

  const remove = async (id: number) => {
    await del(`/api/dept/${id}`);
    message.success('部门已删除');
    fetchTree();
  };

  const columns = [
    {
      title: '部门名称',
      dataIndex: 'name',
      render: (name: string) => (
        <Space>
          <ApartmentOutlined style={{ color: '#1677ff' }} />
          <span>{name}</span>
        </Space>
      ),
    },
    {
      title: '排序',
      dataIndex: 'sortOrder',
      width: 80,
      render: (v: number) => <Tag>{v ?? 0}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (s: number) =>
        s === 1 ? <Tag color="green">启用</Tag> : <Tag color="red">停用</Tag>,
    },
    {
      title: '操作',
      width: 260,
      render: (_: unknown, row: DeptNode) => (
        <Space size="small">
          <Tooltip title={`在「${row.name}」下新增子部门`}>
            <Button size="small" icon={<PlusOutlined />} onClick={() => createChild(row)}>
              新增子部门
            </Button>
          </Tooltip>
          <Button size="small" icon={<EditOutlined />} onClick={() => editDept(row)}>
            编辑
          </Button>
          <Popconfirm
            title="确认删除该部门？"
            description="存在子部门或部门空间下存在文件时将被阻止"
            onConfirm={() => remove(row.id)}
          >
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Button type="primary" icon={<PlusOutlined />} onClick={() => createChild(null)}>
        新增根部门
      </Button>
      <Table
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={tree}
        defaultExpandAllRows
        pagination={false}
        size="middle"
      />
      <Modal
        title={editing ? '编辑部门' : `新增部门（上级：${parentName}）`}
        open={open}
        onCancel={() => setOpen(false)}
        onOk={submit}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="name"
            label="部门名称"
            rules={[{ required: true, message: '请输入部门名称' }]}
          >
            <Input placeholder="如：研发部" />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序（数字越小越靠前）" initialValue={0}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
