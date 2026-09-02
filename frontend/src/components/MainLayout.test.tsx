import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import MainLayout from '../components/MainLayout';

/** 按角色下发 /api/auth/me 数据（TC-UI-003 权限渲染） */
function stubMe(roleCode: string, realName = '张三') {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          code: 0,
          message: 'success',
          data: { id: 1, username: 'zhangsan', realName, roleCode, deptId: 1, mustChangePassword: 0 },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    ),
  );
}

function renderLayout() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={<MainLayout />}>
          <Route index element={<div>文件管理页</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe('MainLayout 按角色渲染菜单（TC-UI-003）', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it('ADMIN 可见全部管理菜单', async () => {
    stubMe('ADMIN', '系统管理员');
    renderLayout();
    expect(await screen.findByText('系统管理员（ADMIN）')).toBeTruthy();

    expect(screen.getByText('文件管理')).toBeTruthy();
    expect(screen.getByText('回收站')).toBeTruthy();
    expect(screen.getByText('部门管理')).toBeTruthy();
    expect(screen.getByText('用户管理')).toBeTruthy();
    expect(screen.getByText('审计日志')).toBeTruthy();
    expect(screen.getByText('系统存储')).toBeTruthy();
  });

  it('USER 无管理菜单（用户管理/部门管理/审计/存储）', async () => {
    stubMe('USER');
    renderLayout();
    expect(await screen.findByText('张三（USER）')).toBeTruthy();

    expect(screen.getByText('文件管理')).toBeTruthy();
    expect(screen.getByText('回收站')).toBeTruthy();
    expect(screen.queryByText('用户管理')).toBeNull();
    expect(screen.queryByText('部门管理')).toBeNull();
    expect(screen.queryByText('审计日志')).toBeNull();
    expect(screen.queryByText('系统存储')).toBeNull();
  });

  it('DEPT_ADMIN 可见本部门成员入口，不可见系统级菜单', async () => {
    stubMe('DEPT_ADMIN', '王五');
    renderLayout();
    expect(await screen.findByText('王五（DEPT_ADMIN）')).toBeTruthy();

    expect(screen.getByText('本部门成员')).toBeTruthy();
    expect(screen.queryByText('用户管理')).toBeNull();
    expect(screen.queryByText('系统存储')).toBeNull();
    expect(screen.queryByText('审计日志')).toBeNull();
    expect(screen.queryByText('部门管理')).toBeNull();
  });

  it('获取当前用户失败时跳转登录页', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('boom', { status: 500, headers: { 'Content-Type': 'text/plain' } })),
    );
    render(
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<MainLayout />}>
            <Route index element={<div>文件管理页</div>} />
          </Route>
          <Route path="/login" element={<div>登录页占位</div>} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText('登录页占位')).toBeTruthy();
    expect(screen.queryByText('文件管理页')).toBeNull();
  });
});
