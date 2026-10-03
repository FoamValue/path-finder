export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface PageResult<T> {
  list: T[];
  total: number;
  pageNum: number;
  pageSize: number;
}

export interface AuthUser {
  id: number;
  username: string;
  realName: string;
  roleCode: string;
  orgId: number;
  mustChangePassword: number;
}

export interface Captcha {
  uuid: string;
  image: string;
}

export interface FileInfo {
  id: number;
  originalName: string;
  fileSize: number;
  fileMd5: string;
  fileType: string;
  spaceType: string;
  orgId: number;
  ownerId: number;
  ownerName: string;
  creatorName: string;
  status: string;
  diskStatus?: string;
  createdAt: string;
}

export interface OrgNode {
  id: number;
  parentId: number;
  name: string;
  sortOrder: number;
  status: number;
  children: OrgNode[];
}

export interface UserVo {
  id: number;
  username: string;
  realName: string;
  orgId: number;
  orgName: string;
  roleCode: string;
  status: number;
  mustChangePassword: number;
  createdAt: string;
}

export interface OperationLog {
  id: number;
  operatorName: string;
  operationType: string;
  targetType: string;
  targetId: string;
  targetName: string;
  success: number;
  createdAt: string;
  detail: string;
}

export interface StorageInfo {
  total: number;
  used: number;
  available: number;
  ratio: number;
  alert: boolean;
}

export interface RecycleItem {
  id: number;
  fileId: number;
  deletedBy: number;
  deletedAt: string;
  expireAt: string;
  originalName: string;
  fileType: string;
  fileSize: number;
  spaceType: string;
}

export interface UploadTicket {
  identifier: string;
  fileId: number;
}

export interface DownloadTicket {
  token: string;
}
