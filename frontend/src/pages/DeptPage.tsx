import { useCallback, useEffect, useState } from 'react';
import { Button, Form, Input, InputNumber, Modal, Popconfirm, Space, Tree, message } from 'antd';
import type { DataNode } from 'antd/es/tree';
import { del, get, post, put } from '../api/client';
import type { DeptNode } from '../api/types';

export default function DeptPage() {
  const [tree, setTree] = useState<DeptNode[]>([]);
  const [open, setOpen] = useState(false);
  const [parentId, setParentId] = useState<number>(0);
  const [editing, setEditing] = useState<DeptNode | null>(null);
  const [form] = Form.useForm();

  const fetchTree = useCallback(async () => {
    setTree(await get<DeptNode[]>('/api/dept/tree'));
  }, []);

  useEffect(() => {
    fetchTree();
  }, [fetchTree]);

  const createChild = (parent: number) => {
    setEditing(null);
    setParentId(parent);
    form.resetFields();
    setOpen(true);
  };

  const editDept = (node: DeptNode) => {
    setEditing(node);
    setParentId(node.parentId);
    form.setFieldsValue({ name: node.name, sortOrder: node.sortOrder });
    setOpen(true);
  };

  const submit = async () => {
    const v = await form.validateFields();
    if (editing) {
      await put(`/api/dept/${editing.id}`, v);
      message.success('已更新');
    } else {
      await post('/api/dept', { ...v, parentId });
      message.success('已创建');
    }
    setOpen(false);
    fetchTree();
  };

  const remove = async (id: number) => {
    await del(`/api/dept/${id}`);
    message.success('已删除');
    fetchTree();
  };

  const renderTree = (nodes: DeptNode[]): DataNode[] =>
    nodes.map((n) => ({
      key: String(n.id),
      title: (
        <Space>
          <span>{n.name}</span>
          <Button size="small" type="link" onClick={() => createChild(n.id)}>
            新增子部门
          </Button>
          <Button size="small" type="link" onClick={() => editDept(n)}>
            编辑
          </Button>
          <Popconfirm title="删除部门？有子部门/文件时将被阻止" onConfirm={() => remove(n.id)}>
            <Button size="small" type="link" danger>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
      children: n.children?.length ? renderTree(n.children) : undefined,
    }));

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Button type="primary" onClick={() => createChild(0)}>
        新增根部门
      </Button>
      <Tree treeData={renderTree(tree)} defaultExpandAll />
      <Modal title={editing ? '编辑部门' : '新增部门'} open={open} onCancel={() => setOpen(false)} onOk={submit}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="部门名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序" initialValue={0}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
