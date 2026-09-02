import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import UploadModal from '../components/UploadModal';
import type { DeptNode } from '../api/types';

const mocks = vi.hoisted(() => ({
  runUpload: vi.fn(async () => undefined),
  messageSuccess: vi.fn(),
  messageWarning: vi.fn(),
}));
vi.mock('../utils/uploadTask', () => ({ runUpload: mocks.runUpload }));
vi.mock('antd', async (importOriginal) => {
  const antd = await importOriginal<typeof import('antd')>();
  return {
    ...antd,
    message: {
      ...antd.message,
      success: mocks.messageSuccess,
      warning: mocks.messageWarning,
    },
  };
});

const deptTree: DeptNode[] = [
  { id: 1, parentId: 0, name: '研发部', sortOrder: 0, status: 1, children: [] },
];

function renderModal() {
  const onClose = vi.fn();
  const onSuccess = vi.fn();
  render(<UploadModal open onClose={onClose} onSuccess={onSuccess} deptTree={deptTree} />);
  const fileInput = () =>
    document.querySelector('input[type="file"]') as HTMLInputElement | null;
  const upload = (name = 'a.txt') => {
    const file = new File(['content-hello'], name, { type: 'text/plain' });
    fireEvent.change(fileInput()!, { target: { files: [file] } });
  };
  return { onClose, onSuccess, upload };
}

describe('上传弹窗（TC-UI-004）', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    mocks.runUpload.mockClear();
    mocks.messageSuccess.mockClear();
    mocks.messageWarning.mockClear();
    document.body.innerHTML = '';
  });

  it('渲染弹窗、默认个人空间与上传提示', () => {
    renderModal();
    expect(screen.getByText('上传文件')).toBeTruthy();
    expect(screen.getByText('个人空间')).toBeTruthy(); // 当前选中值
    expect(screen.getByText(/点击或拖拽文件到此区域上传/)).toBeTruthy();
    expect(screen.getByText('选择文件后将自动开始上传')).toBeTruthy();
  });

  it('默认个人空间：选择文件自动上传并在成功回调后触发 onSuccess', async () => {
    const { upload, onSuccess } = renderModal();
    upload('hello.txt');

    await waitFor(() => expect(mocks.runUpload).toHaveBeenCalledTimes(1));
    expect(mocks.runUpload.mock.calls[0][1]).toBe('PERSONAL');
    await waitFor(() => expect(onSuccess).toHaveBeenCalled());
    expect(mocks.messageSuccess).toHaveBeenCalled();
  });

  it('选择部门空间但未选部门：上传被拦截并提示（TC-UP-004）', async () => {
    const { upload } = renderModal();

    // 打开空间下拉并选择「部门空间」
    const combobox = document.querySelector('.ant-select-selector') as HTMLElement;
    fireEvent.mouseDown(combobox);
    const deptOption = (await screen.findByText('部门空间')).closest(
      '.ant-select-item-option',
    );
    expect(deptOption).toBeTruthy();
    fireEvent.click(deptOption!);

    // 等待部门下拉出现（空间状态已提交为 DEPT）
    await screen.findByText('选择部门');

    // 未选择具体部门时上传文件
    upload('report.pdf');

    await waitFor(() => expect(mocks.messageWarning).toHaveBeenCalled());
    expect(mocks.messageWarning).toHaveBeenCalledWith(expect.stringContaining('必须选择部门'));
    expect(mocks.runUpload).not.toHaveBeenCalled();
  });
});
