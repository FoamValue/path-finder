import { useEffect, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Layout, Menu, Dropdown, Typography, message } from 'antd';
import {
  FolderOutlined,
  DeleteOutlined,
  TeamOutlined,
  ApartmentOutlined,
  FileTextOutlined,
  DashboardOutlined,
  LogoutOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { get, post, clearToken } from '../api/client';
import { APP_COPYRIGHT } from '../config';
import type { AuthUser } from '../api/types';

const { Sider, Header, Content } = Layout;

export default function MainLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const [me, setMe] = useState<AuthUser | null>(null);

  useEffect(() => {
    get<AuthUser>('/api/auth/me')
      .then((u) => setMe(u))
      .catch(() => navigate('/login'));
  }, [navigate]);

  const isAdmin = me?.roleCode === 'ADMIN';
  const isDeptAdmin = me?.roleCode === 'DEPT_ADMIN';

  const menuItems = [
    ...(isAdmin ? [{ key: '/storage', icon: <DashboardOutlined />, label: '系统存储' }] : []),
    { key: '/', icon: <FolderOutlined />, label: '文件管理' },
    { key: '/recycle', icon: <DeleteOutlined />, label: '回收站' },
    ...(isAdmin ? [{ key: '/dept', icon: <ApartmentOutlined />, label: '部门管理' }] : []),
    ...(isAdmin || isDeptAdmin
      ? [{ key: '/user', icon: <TeamOutlined />, label: isAdmin ? '用户管理' : '本部门成员' }]
      : []),
    ...(isAdmin ? [{ key: '/log', icon: <FileTextOutlined />, label: '审计日志' }] : []),
  ];

  const logout = async () => {
    try {
      await post('/api/logout');
    } catch {
      /* ignore */
    }
    clearToken();
    message.success('已退出登录');
    navigate('/login');
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider breakpoint="lg" collapsedWidth="0" style={{ background: '#001529' }}>
        <div style={{ height: 48, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Typography.Text strong style={{ color: '#fff', fontSize: 16 }}>
            PathFinder
          </Typography.Text>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: '#fff',
            padding: '0 24px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            boxShadow: '0 1px 4px rgba(0,0,0,.08)',
          }}
        >
          <Typography.Text strong>文件管理系统</Typography.Text>
          <Dropdown
            menu={{
              items: [
                { key: 'pwd', icon: <UserOutlined />, label: '修改密码', onClick: () => navigate('/changePassword') },
                { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: logout },
              ],
            }}
          >
            <Typography.Text style={{ cursor: 'pointer' }}>
              {me?.realName || me?.username}（{me?.roleCode}）
            </Typography.Text>
          </Dropdown>
        </Header>
        <Content style={{ margin: 16 }}>
          <Outlet />
        </Content>
        <Layout.Footer style={{ textAlign: 'center', color: '#999', padding: '12px 0' }}>
          Copyright © {new Date().getFullYear()} {APP_COPYRIGHT}
        </Layout.Footer>
      </Layout>
    </Layout>
  );
}
