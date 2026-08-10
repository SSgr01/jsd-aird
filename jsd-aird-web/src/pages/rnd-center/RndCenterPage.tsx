import { FileSearchOutlined, FolderOpenOutlined, RobotOutlined } from '@ant-design/icons';
import { Card, Col, Row, Space, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';

export function RndCenterPage() {
  const navigate = useNavigate();
  const cards = [
    { title: '研发知识库', description: '上传、解析、版本管理和 AI 使用授权。', icon: <FolderOpenOutlined />, path: '/knowledge/library' },
    { title: '文件检索', description: '按关键词和语义快速定位研发资料原文。', icon: <FileSearchOutlined />, path: '/knowledge/search' },
    { title: 'AI 研发助手', description: '基于已授权知识库内容进行问答并返回引用。', icon: <RobotOutlined />, path: '/assistant' },
  ];
  return <div className="business-page">
    <div className="page-heading"><div><Typography.Title level={2}>研发中心</Typography.Title><Typography.Text type="secondary">研发资料、检索和 AI 辅助研发工作台。</Typography.Text></div></div>
    <Row gutter={[16, 16]}>
      {cards.map((card) => <Col xs={24} md={8} key={card.path}>
        <Card hoverable className="content-card" onClick={() => navigate(card.path)}>
          <Space direction="vertical" size={12}>
            <span style={{ fontSize: 30, color: '#2563eb' }}>{card.icon}</span>
            <Typography.Title level={4} style={{ margin: 0 }}>{card.title}</Typography.Title>
            <Typography.Text type="secondary">{card.description}</Typography.Text>
          </Space>
        </Card>
      </Col>)}
    </Row>
  </div>;
}
