import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
  Spin,
  Alert,
} from 'antd';
import { UploadOutlined, DownloadOutlined, DeleteOutlined, EditOutlined, SwapOutlined, EyeOutlined, ZoomInOutlined, ZoomOutOutlined } from '@ant-design/icons';
import { get, post, put, del } from '../api/client';
import { formatSize } from '../utils/file';
import { flatOrgs } from '../utils/org';
import UploadModal from '../components/UploadModal';
import type { AuthUser, OrgNode, FileInfo, UserVo } from '../api/types';
import mammoth from 'mammoth/mammoth.browser';
import * as XLSX from 'xlsx';
import * as pdfjsLib from 'pdfjs-dist';
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import DOMPurify from 'dompurify';
import { marked } from 'marked';

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorkerUrl;

type PreviewKind = 'image' | 'pdf' | 'text' | 'video' | 'audio' | 'docx' | 'xlsx';

// 返回可原生/本地预览的类型；null 表示暂不支持在线预览（如 doc/xls/ppt、压缩包）
export function previewKind(name: string): PreviewKind | null {
  const ext = (name || '').split('.').pop()?.toLowerCase() || '';
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg'].includes(ext)) return 'image';
  if (ext === 'pdf') return 'pdf';
  if (['mp4', 'webm', 'ogg', 'mov', 'm4v'].includes(ext)) return 'video';
  if (['mp3', 'wav', 'm4a', 'aac', 'flac', 'oga'].includes(ext)) return 'audio';
  if (ext === 'docx') return 'docx';
  if (ext === 'xlsx') return 'xlsx';
  if (
    ['txt', 'log', 'md', 'csv', 'json', 'xml', 'yml', 'yaml', 'sql', 'ini', 'properties', 'ts', 'tsx', 'js', 'java'].includes(
      ext,
    )
  ) {
    return 'text';
  }
  return null;
}

export default function FileList() {
  const [me, setMe] = useState<AuthUser | null>(null);
  const [data, setData] = useState<FileInfo[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [keyword, setKeyword] = useState('');
  const [spaceType, setSpaceType] = useState<string | undefined>();
  const [orgId, setOrgId] = useState<number | undefined>();
  const [orgTree, setOrgTree] = useState<OrgNode[]>([]);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [selectedKeys, setSelectedKeys] = useState<number[]>([]);

  // 归属变更弹窗状态
  const [ownerTarget, setOwnerTarget] = useState<FileInfo | null>(null);
  const [ownerBatch, setOwnerBatch] = useState(false);
  const [ownerSpace, setOwnerSpace] = useState('PERSONAL');
  const [ownerOrg, setOwnerOrg] = useState<number | undefined>();
  const [ownerUser, setOwnerUser] = useState<number | undefined>();
  const [users, setUsers] = useState<UserVo[]>([]);
  const [renameTarget, setRenameTarget] = useState<FileInfo | null>(null);
  const [renameForm] = Form.useForm();
  const [previewTarget, setPreviewTarget] = useState<FileInfo | null>(null);

  const fetchMe = useCallback(() => get<AuthUser>('/api/auth/me').then(setMe), []);

  const fetchOrgs = useCallback(() => {
    get<OrgNode[]>('/api/org/tree').then(setOrgTree).catch(() => setOrgTree([]));
  }, []);

  const fetchList = useCallback(async () => {
    const params = new URLSearchParams({
      pageNum: String(pageNum),
      pageSize: String(pageSize),
    });
    if (keyword) params.set('keyword', keyword);
    if (spaceType) params.set('spaceType', spaceType);
    if (orgId) params.set('orgId', String(orgId));
    const d = await get<{ list: FileInfo[]; total: number }>(`/api/file/page?${params}`);
    setData(d.list);
    setTotal(d.total);
  }, [pageNum, pageSize, keyword, spaceType, orgId]);

  useEffect(() => {
    fetchMe();
    fetchOrgs();
  }, [fetchMe, fetchOrgs]);
  useEffect(() => {
    fetchList();
  }, [fetchList]);

  const isAdmin = me?.roleCode === 'ADMIN';
  const canManage = me?.roleCode === 'ADMIN' || me?.roleCode === 'ORG_ADMIN';

  const openOwnerModal = async (f: FileInfo) => {
    setOwnerBatch(false);
    setOwnerTarget(f);
    setOwnerSpace(f.spaceType);
    setOwnerOrg(f.orgId);
    setOwnerUser(undefined);
    if (canManage) {
      get<UserVo[]>('/api/user/page?pageSize=100').then((d: any) => setUsers(d.list || []));
    }
  };

  const openOwnerBatch = async () => {
    if (!selectedKeys.length) {
      message.warning('请先勾选文件');
      return;
    }
    setOwnerBatch(true);
    setOwnerTarget({ id: 0, originalName: `选中的 ${selectedKeys.length} 个文件`, spaceType: 'PERSONAL' } as FileInfo);
    setOwnerSpace('PERSONAL');
    setOwnerOrg(undefined);
    setOwnerUser(undefined);
    if (canManage) {
      get<UserVo[]>('/api/user/page?pageSize=100').then((d: any) => setUsers(d.list || []));
    }
  };

  const confirmOwner = async () => {
    if (!ownerTarget) return;
    const body = {
      spaceType: ownerSpace,
      orgId: ownerSpace === 'ORG' ? ownerOrg : null,
      ownerId: ownerUser,
    };
    try {
      if (ownerBatch) {
        const r = await put<{ message: string }>('/api/file/batchOwner', { ids: selectedKeys, ...body });
        message.success(r?.message || '归属变更成功');
      } else {
        await put(`/api/file/${ownerTarget.id}/owner`, body);
        message.success('归属变更成功');
      }
      setOwnerTarget(null);
      setOwnerBatch(false);
      setSelectedKeys([]);
      fetchList();
    } catch (e: any) {
      message.error(e.message || '归属变更失败');
    }
  };

  const batchDelete = async () => {
    if (!selectedKeys.length) {
      message.warning('请先勾选文件');
      return;
    }
    try {
      const r = await post<{ message: string }>('/api/file/batchDelete', { ids: selectedKeys });
      message.success(r?.message || '删除成功');
      setSelectedKeys([]);
      fetchList();
    } catch (e: any) {
      message.error(e.message || '批量删除失败');
    }
  };

  const download = async (f: FileInfo) => {
    try {
      const d = await get<{ token: string }>(`/api/file/${f.id}/downloadToken`);
      const resp = await fetch(`/api/file/download/${d.token}`, { credentials: 'include' });
      if (!resp.ok) {
        let msg = `下载失败（HTTP ${resp.status}）`;
        try {
          const body = await resp.json();
          if (body?.message) msg = body.message;
        } catch (_) {
          /* ignore */
        }
        throw new Error(msg);
      }
      const blob = await resp.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = f.originalName;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e: any) {
      message.error(e.message || '下载失败');
    }
  };

  const batchDownload = async () => {
    if (!selectedKeys.length) {
      message.warning('请先选择文件');
      return;
    }
    try {
      const d = await post<{ token: string }>('/api/file/batchDownload', { ids: selectedKeys });
      const resp = await fetch(`/api/file/download/${d.token}`, { credentials: 'include' });
      if (!resp.ok) {
        throw new Error(`批量下载失败（HTTP ${resp.status}）`);
      }
      const blob = await resp.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `batch-${Date.now()}.zip`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e: any) {
      message.error(e.message || '批量下载失败');
    }
  };

  const spaceTag = (s: string) => {
    const map: Record<string, [string, string]> = {
      PERSONAL: ['个人', 'blue'],
      ORG: ['组织', 'green'],
      PUBLIC: ['公共', 'orange'],
    };
    const [label, color] = map[s] || [s, 'default'];
    return <Tag color={color}>{label}</Tag>;
  };

  const diskStatusTag = (s?: string) => {
    if (s === 'MISSING') {
      return <Tag color="red">目录文件已被删除</Tag>;
    }
    if (s === 'UPDATED') {
      return <Tag color="gold">源文件已被更新</Tag>;
    }
    return null;
  };

  const orgNameById = useMemo(() => {
    const m = new Map<number, string>();
    const walk = (nodes: OrgNode[]) => {
      for (const n of nodes) {
        m.set(n.id, n.name);
        walk(n.children || []);
      }
    };
    walk(orgTree);
    return m;
  }, [orgTree]);

  const columns = [
    { title: '文件名', dataIndex: 'originalName', ellipsis: true, width: 280 },
    { title: '状态', dataIndex: 'diskStatus', width: 130, render: (v: string) => diskStatusTag(v) },
    { title: '类型', dataIndex: 'fileType', width: 80, render: (t: string) => (t ? <Tag>{t}</Tag> : '-') },
    { title: '大小', dataIndex: 'fileSize', width: 100, render: (v: number) => formatSize(v) },
    { title: '空间', dataIndex: 'spaceType', width: 80, render: (v: string) => spaceTag(v) },
    {
      title: '组织',
      dataIndex: 'orgId',
      width: 120,
      render: (_: unknown, row: FileInfo) =>
        row.spaceType === 'ORG' ? orgNameById.get(row.orgId) || '-' : '-',
    },
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
      width: 320,
      render: (_: unknown, row: FileInfo) => (
        <Space size="small">
          {previewKind(row.originalName) && (
            <Button size="small" icon={<EyeOutlined />} onClick={() => setPreviewTarget(row)}>
              预览
            </Button>
          )}
          <Button size="small" icon={<DownloadOutlined />} onClick={() => download(row)}>
            下载
          </Button>
          <Button size="small" icon={<EditOutlined />} onClick={() => { setRenameTarget(row); renameForm.setFieldsValue({ newName: row.originalName }); }}>
            重命名
          </Button>
          <Button size="small" icon={<SwapOutlined />} onClick={() => openOwnerModal(row)}>
            归属
          </Button>
          <Popconfirm
            title="确认删除？文件将进入回收站"
            onConfirm={async () => {
              try {
                await del(`/api/file/${row.id}`);
                message.success('已删除');
                fetchList();
              } catch (e: any) {
                message.error(e.message || '删除失败');
              }
            }}
          >
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
        <Button icon={<SwapOutlined />} onClick={openOwnerBatch} disabled={!selectedKeys.length}>
          批量归属
        </Button>
        <Popconfirm title={`确认删除选中的 ${selectedKeys.length} 个文件？文件将进入回收站`} onConfirm={batchDelete}>
          <Button danger icon={<DeleteOutlined />} disabled={!selectedKeys.length}>
            批量删除
          </Button>
        </Popconfirm>
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
            { value: 'ORG', label: '组织空间' },
            { value: 'PUBLIC', label: '公共空间' },
          ]}
        />
        <Select
          allowClear
          showSearch
          placeholder="组织筛选"
          style={{ width: 180 }}
          value={orgId}
          onChange={(v) => { setOrgId(v); setPageNum(1); }}
          options={flatOrgs(orgTree)}
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
        orgTree={orgTree}
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
              { value: 'ORG', label: '组织空间' },
              { value: 'PUBLIC', label: '公共空间' },
            ]}
          />
          {ownerSpace === 'ORG' && (
            <Select
              showSearch
              placeholder="选择目标组织"
              style={{ width: '100%' }}
              value={ownerOrg}
              onChange={setOwnerOrg}
              options={flatOrgs(orgTree)}
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
          try {
            const v = await renameForm.validateFields();
            await put(`/api/file/${renameTarget!.id}/rename`, v);
            message.success('重命名成功');
            setRenameTarget(null);
            fetchList();
          } catch (e: any) {
            if (e?.errorFields) return;
            message.error(e.message || '重命名失败');
          }
        }}
      >
        <Form form={renameForm}>
          <Form.Item name="newName" rules={[{ required: true, message: '请输入新文件名' }]}>
            <Input />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title={`预览 - ${previewTarget?.originalName || ''}`}
        open={!!previewTarget}
        onCancel={() => setPreviewTarget(null)}
        footer={null}
        width={960}
        destroyOnClose
      >
        {previewTarget && <FilePreview file={previewTarget} />}
      </Modal>
    </Space>
  );
}

function FilePreview({ file }: { file: FileInfo }) {
  const kind = previewKind(file.originalName);
  const src = `/api/file/${file.id}/preview`;
  if (kind === 'image') {
    return <PreviewImage src={src} alt={file.originalName} />;
  }
  if (kind === 'video') {
    return <video src={src} controls style={{ width: '100%', maxHeight: '70vh' }} />;
  }
  if (kind === 'audio') {
    return <audio src={src} controls style={{ width: '100%' }} />;
  }
  if (kind === 'pdf') {
    return <PdfPreview src={src} />;
  }
  return <LocalPreview src={src} kind={kind!} name={file.originalName} />;
}

// PDF：pdf.js 本地渲染到 Canvas（只读，不依赖浏览器内嵌查看器）
function PdfPreview({ src }: { src: string }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let disposed = false;
    setLoading(true);
    setError(null);
    (async () => {
      try {
        const resp = await fetch(src, { credentials: 'include' });
        if (!resp.ok) {
          if (!disposed) {
            setError(`PDF 加载失败（HTTP ${resp.status}）`);
            setLoading(false);
          }
          return;
        }
        const data = await resp.arrayBuffer();
        const pdf = await pdfjsLib.getDocument({
          data,
          // pdf.js 渲染含标准字体（Helvetica 等）的 PDF 时需要标准字体数据，否则画布文字为空
          standardFontDataUrl: `${import.meta.env.BASE_URL}standard_fonts/`,
        }).promise;
        if (disposed) return;
        const container = containerRef.current;
        if (!container) return;
        const dpr = window.devicePixelRatio || 1;
        for (let i = 1; i <= pdf.numPages; i++) {
          const page = await pdf.getPage(i);
          const viewport = page.getViewport({ scale: 1 });
          const width = Math.min(viewport.width, 860);
          const scale = width / viewport.width;
          const vp = page.getViewport({ scale });
          const canvas = document.createElement('canvas');
          canvas.width = Math.floor(vp.width * dpr);
          canvas.height = Math.floor(vp.height * dpr);
          canvas.style.width = `${Math.floor(vp.width)}px`;
          canvas.style.height = `${Math.floor(vp.height)}px`;
          canvas.style.margin = '0 auto 12px';
          const ctx = canvas.getContext('2d')!;
          ctx.scale(dpr, dpr);
          await page.render({ canvas, canvasContext: ctx, viewport: vp }).promise;
          container.appendChild(canvas);
        }
      } catch (e) {
        if (!disposed) setError(String(e));
      } finally {
        if (!disposed) setLoading(false);
      }
    })();
    return () => {
      disposed = true;
      if (containerRef.current) containerRef.current.innerHTML = '';
    };
  }, [src]);

  if (error) return <Alert type="warning" showIcon message="PDF 解析失败" description={error} />;
  return (
    <div>
      <Spin spinning={loading} tip="PDF 解析中…">
        <div
          ref={containerRef}
          style={{ maxHeight: '72vh', overflow: 'auto', background: '#3f3f3f', padding: 12, textAlign: 'center' }}
        />
      </Spin>
    </div>
  );
}

const XLSX_MAX_ROWS = 500;
const XLSX_MAX_COLS = 60;

function escHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function cellText(v: XLSX.CellObject | undefined): string {
  if (!v) return '';
  // 优先展示格式化文本 v.w（保留 Excel 的日期/数字格式），否则原始值 v.v
  if (v.w != null) return String(v.w);
  return v.v == null ? '' : String(v.v);
}

// 把工作簿渲染成可读的只读表格（数字右对齐、字符串左对齐、首行作表头；多工作表各带标题）
function xlsxToHtml(wb: XLSX.WorkBook): string {
  const parts: string[] = [];
  for (const sheetName of wb.SheetNames) {
    const ws = wb.Sheets[sheetName];
    const range = XLSX.utils.decode_range(ws['!ref'] || 'A1');
    const lastCol = Math.min(range.e.c, range.s.c + XLSX_MAX_COLS);
    const headerRow = range.s.r;
    const lastRow = Math.min(range.e.r, headerRow + XLSX_MAX_ROWS);
    parts.push(`<div class="pf-sheet-caption">${escHtml(`工作表：${sheetName}`)}</div>`);
    parts.push('<table><thead><tr>');
    for (let c = range.s.c; c <= lastCol; c++) {
      const v = ws[XLSX.utils.encode_cell({ r: headerRow, c })];
      parts.push(`<th>${escHtml(cellText(v))}</th>`);
    }
    parts.push('</tr></thead><tbody>');
    for (let r = headerRow + 1; r <= lastRow; r++) {
      parts.push('<tr>');
      for (let c = range.s.c; c <= lastCol; c++) {
        const v = ws[XLSX.utils.encode_cell({ r, c })];
        parts.push(`<td class="${v && v.t === 'n' ? 'num' : 'str'}">${escHtml(cellText(v))}</td>`);
      }
      parts.push('</tr>');
    }
    parts.push('</tbody></table>');
  }
  return parts.join('');
}

// 文本(MD 渲染) / docx / xlsx：fetch 后本地只读渲染
function LocalPreview({ src, kind, name }: { src: string; kind: 'text' | 'docx' | 'xlsx'; name?: string }) {
  const [text, setText] = useState<string | null>(null);
  const [html, setHtml] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const isMarkdown = ['md', 'markdown'].includes((name || '').split('.').pop()?.toLowerCase() || '');

  useEffect(() => {
    let disposed = false;
    setText(null);
    setHtml(null);
    setError(null);
    (async () => {
      try {
        const resp = await fetch(src, { credentials: 'include' });
        if (!resp.ok) {
          if (!disposed) setError(`加载失败（HTTP ${resp.status}）`);
          return;
        }
        if (kind === 'text') {
          const t = await resp.text();
          if (!disposed) {
            if (isMarkdown) {
              setHtml(DOMPurify.sanitize(marked.parse(t) as string));
            } else {
              setText(t);
            }
          }
        } else if (kind === 'docx') {
          const ab = await resp.arrayBuffer();
          const result = await mammoth.convertToHtml({ arrayBuffer: ab });
          if (!disposed) setHtml(DOMPurify.sanitize(result.value));
        } else {
          const ab = await resp.arrayBuffer();
          const wb = XLSX.read(ab);
          if (!disposed) setHtml(xlsxToHtml(wb));
        }
      } catch (e) {
        if (!disposed) setError(String(e));
      }
    })();
    return () => {
      disposed = true;
    };
  }, [src, kind, isMarkdown]);

  if (error) return <Alert type="warning" showIcon message="预览失败" description={error} />;
  if (kind === 'text' && !isMarkdown) {
    return <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', padding: 12, maxHeight: '72vh', overflow: 'auto', margin: 0 }}>{text ?? '加载中…'}</pre>;
  }
  const cls = kind === 'xlsx' ? 'pf-preview-xlsx' : 'pf-preview-doc';
  return (
    <div className={`pf-preview-scroll ${cls}`}>
      {html ? <div dangerouslySetInnerHTML={{ __html: html }} /> : <Spin spinning />}
    </div>
  );
}

// 图片预览：支持放大 / 缩小 / 重置；放大后容器可横纵滚动查看任意区域
function PreviewImage({ src, alt }: { src: string; alt: string }) {
  const [scale, setScale] = useState(1);
  const [natW, setNatW] = useState<number | null>(null);
  const MIN = 0.5;
  const MAX = 4;
  const zoom = (delta: number) =>
    setScale((s) => Math.min(MAX, Math.max(MIN, +(s + delta).toFixed(2))));
  return (
    <div>
      <div style={{ display: 'flex', gap: 4, justifyContent: 'center', alignItems: 'center', marginBottom: 12 }}>
        <Button size="small" icon={<ZoomOutOutlined />} onClick={() => zoom(-0.25)} disabled={scale <= MIN}>
          缩小
        </Button>
        <Button size="small" onClick={() => setScale(1)}>
          {Math.round(scale * 100)}%
        </Button>
        <Button size="small" icon={<ZoomInOutlined />} onClick={() => zoom(0.25)} disabled={scale >= MAX}>
          放大
        </Button>
      </div>
      <div className="pf-preview-img-wrap">
        <img
          src={src}
          alt={alt}
          onLoad={(e) => setNatW(e.currentTarget.naturalWidth)}
          style={{ width: natW ? Math.round(natW * scale) : '100%' }}
        />
      </div>
    </div>
  );
}
