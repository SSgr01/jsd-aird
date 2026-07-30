import { ApiOutlined, DatabaseOutlined, LayoutOutlined } from '@ant-design/icons';
import { Card, Col, Row, Space, Tag, Typography } from 'antd';

import { ServiceStatus } from '@/components/ServiceStatus';

const foundations = [
  {
    icon: <LayoutOutlined />,
    title: '前端基础',
    description: 'React、TypeScript、Ant Design、路由、状态和请求封装。',
  },
  {
    icon: <ApiOutlined />,
    title: '后端基础',
    description: 'Spring Boot、Spring Modulith、MyBatis-Plus和统一公共契约。',
  },
  {
    icon: <DatabaseOutlined />,
    title: '数据基础',
    description: 'PostgreSQL、pgvector、Flyway及显式Schema边界。',
  },
];

export function HomePage() {
  return (
    <Space direction="vertical" size={24} className="page-stack">
      <Card variant="borderless">
        <Space direction="vertical" size={12}>
          <Tag color="blue">基础工程</Tag>
          <Typography.Title level={2} className="page-title">
            研发数字化与 AI 平台
          </Typography.Title>
          <Typography.Paragraph type="secondary" className="page-description">
            当前仓库只包含基础工程、模块边界和本地开发环境，尚未实现具体业务功能。
          </Typography.Paragraph>
          <ServiceStatus />
        </Space>
      </Card>

      <Row gutter={[16, 16]}>
        {foundations.map((item) => (
          <Col xs={24} md={8} key={item.title}>
            <Card variant="borderless" className="foundation-card">
              <Space align="start">
                <span className="foundation-icon">{item.icon}</span>
                <span>
                  <Typography.Title level={4}>{item.title}</Typography.Title>
                  <Typography.Paragraph type="secondary">{item.description}</Typography.Paragraph>
                </span>
              </Space>
            </Card>
          </Col>
        ))}
      </Row>
    </Space>
  );
}
