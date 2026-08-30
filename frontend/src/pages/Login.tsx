import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Form, Input, Typography, message, Card } from 'antd';
import { UserOutlined, LockOutlined, SafetyOutlined } from '@ant-design/icons';
import { getToken, setToken, get, post } from '../api/client';
import { encryptPassword } from '../utils/crypto';
import type { AuthUser, Captcha } from '../api/types';

export default function Login() {
  const navigate = useNavigate();
  const [captcha, setCaptcha] = useState<Captcha | null>(null);
  const [loading, setLoading] = useState(false);

  const refreshCaptcha = async () => {
    try {
      const c = await get<Captcha>('/api/captcha');
      setCaptcha(c);
    } catch {
      /* ignore */
    }
  };

  useEffect(() => {
    refreshCaptcha();
  }, []);

  useEffect(() => {
    if (getToken()) {
      get<AuthUser>('/api/auth/me')
        .then((u) => navigate(u.mustChangePassword === 1 ? '/changePassword' : '/'))
        .catch(() => {
          /* token 失效留在登录页 */
        });
    }
  }, [navigate]);

  const onFinish = async (values: { username: string; password: string; captcha: string }) => {
    setLoading(true);
    try {
      const encrypted = await encryptPassword(values.password);
      const data = await post<{ token: string }>('/api/login', {
        username: values.username,
        encryptedPassword: encrypted,
        captchaUuid: captcha?.uuid,
        captchaCode: values.captcha,
      });
      setToken(data.token);
      const me = await get<AuthUser>('/api/auth/me');
      message.success('登录成功');
      navigate(me.mustChangePassword === 1 ? '/changePassword' : '/');
    } catch (e: any) {
      message.error(e.message || '登录失败');
      refreshCaptcha();
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
      <Card style={{ width: 380, borderRadius: 12, boxShadow: '0 8px 24px rgba(0,0,0,.15)' }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <Typography.Title level={3} style={{ marginBottom: 4 }}>
            PathFinder
          </Typography.Title>
          <Typography.Text type="secondary">文件管理系统 · 寻找系统路径</Typography.Text>
        </div>
        <Form layout="vertical" onFinish={onFinish} requiredMark={false}>
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="用户名" autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" autoComplete="current-password" />
          </Form.Item>
          <Form.Item name="captcha" rules={[{ required: true, message: '请输入验证码' }]}>
            <div style={{ display: 'flex', gap: 8 }}>
              <Input prefix={<SafetyOutlined />} placeholder="验证码" />
              {captcha && (
                <img
                  src={`data:image/png;base64,${captcha.image}`}
                  alt="验证码"
                  style={{ height: 32, cursor: 'pointer', borderRadius: 4 }}
                  onClick={refreshCaptcha}
                />
              )}
            </div>
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>
            登 录
          </Button>
        </Form>
      </Card>
    </div>
  );
}
