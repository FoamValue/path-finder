import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Card, Form, Input, Typography, message } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import { post, clearToken } from '../api/client';
import { encryptPassword } from '../utils/crypto';

/** 修改密码（含首次强制改密） */
export default function ChangePassword() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);

  const onFinish = async (values: { oldPassword: string; newPassword: string }) => {
    setLoading(true);
    try {
      const oldPassword = await encryptPassword(values.oldPassword);
      const newPassword = await encryptPassword(values.newPassword);
      await post<void>('/api/changePassword', { oldPassword, newPassword });
      message.success('密码修改成功，请重新登录');
      clearToken();
      navigate('/login');
    } catch (e: any) {
      message.error(e.message || '修改失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'linear-gradient(135deg, #1677ff 0%, #0a3d8f 100%)',
      }}
    >
      <Card style={{ width: 400, borderRadius: 12 }}>
        <Typography.Title level={4} style={{ textAlign: 'center' }}>
          修改密码
        </Typography.Title>
        <Typography.Paragraph type="secondary" style={{ textAlign: 'center' }}>
          首次登录或密码被重置后，需先修改初始密码
        </Typography.Paragraph>
        <Form layout="vertical" onFinish={onFinish}>
          <Form.Item name="oldPassword" label="原密码" rules={[{ required: true, message: '请输入原密码' }]}>
            <Input.Password prefix={<LockOutlined />} />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label="新密码"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 8, message: '新密码至少 8 位' },
            ]}
          >
            <Input.Password prefix={<LockOutlined />} />
          </Form.Item>
          <Form.Item
            name="confirm"
            label="确认新密码"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '请确认新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('newPassword') === value) {
                    return Promise.resolve();
                  }
                  return Promise.reject(new Error('两次输入的密码不一致'));
                },
              }),
            ]}
          >
            <Input.Password prefix={<LockOutlined />} />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>
            确认修改
          </Button>
        </Form>
      </Card>
    </div>
  );
}
