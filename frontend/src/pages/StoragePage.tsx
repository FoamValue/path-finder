import { useEffect, useState } from 'react';
import { Card, Col, Row, Progress, Statistic, Alert } from 'antd';
import { get } from '../api/client';
import { formatSize } from '../utils/file';
import type { StorageInfo } from '../api/types';

export default function StoragePage() {
  const [info, setInfo] = useState<StorageInfo | null>(null);

  useEffect(() => {
    get<StorageInfo>('/api/storage/info').then(setInfo);
    const timer = setInterval(() => get<StorageInfo>('/api/storage/info').then(setInfo), 10000);
    return () => clearInterval(timer);
  }, []);

  if (!info) return null;

  return (
    <>
      {info.alert && (
        <Alert type="error" showIcon message="磁盘使用率已达 85% 阈值，请及时扩容或清理" style={{ marginBottom: 16 }} />
      )}
      <Row gutter={16}>
        <Col span={6}>
          <Card>
            <Statistic title="总容量" value={formatSize(info.total)} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="已用" value={formatSize(info.used)} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="可用" value={formatSize(info.available)} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Progress type="dashboard" percent={Math.round(info.ratio * 100)} />
          </Card>
        </Col>
      </Row>
    </>
  );
}
