import { Alert, Button, Pagination, Space, Typography } from 'antd';
import { useState } from 'react';

import type { DataIssue, DataSheet } from '@/services/data/data-api';
import { distinctRequiredNotices, importIssueMessage, requiresTemplateChange } from '@/services/data/import-guidance';

export function DataImportQualityNotice({ issues, sheets, onLocate, experimentDraft = false }: { issues: DataIssue[]; sheets: DataSheet[]; onLocate?: (issue: DataIssue) => void; experimentDraft?: boolean }) {
  const required = distinctRequiredNotices(issues);
  const [requiredPage, setRequiredPage] = useState(1);
  const page = Math.min(requiredPage, Math.max(1, Math.ceil(required.length / 20)));
  const openIssues = issues.filter((issue) => issue.issueType !== 'REQUIRED_MISSING' && !['RESOLVED', 'IGNORED'].includes(issue.status));
  if (!openIssues.length && !required.length) return null;

  const blockers = openIssues.filter((issue) => issue.severity === 'BLOCKER').length;
  const warnings = openIssues.filter((issue) => issue.severity === 'WARNING').length;
  const type = blockers ? 'error' : warnings ? 'warning' : 'info';
  const message = blockers
    ? `${blockers} 项问题会阻止提交，另有 ${openIssues.length - blockers} 项提示`
    : warnings
      ? `${warnings} 项数据需要核对`
      : `${openIssues.length} 项导入提示`;

  return (
    <>
    {required.length > 0 && <div aria-label="必填字段提示">
      <Typography.Text type="secondary">{required.length} 处必填字段未填写，{experimentDraft ? '可保存导入；原资料未记录的题目、实验人或日期将保留缺项说明，无需补造。' : '可稍后补充，不影响提交。'}</Typography.Text>
      <details>
      <summary>查看缺失字段和来源位置（{required.length} 处）</summary>
      <Space size={4} wrap>
      {required.slice((page - 1) * 20, page * 20).map((issue, index) => {
        const location = [sheets.find(sheet => sheet.sheetId === issue.sheetId)?.sheetName, issue.address || (issue.rowNumber ? `${issue.rowNumber} 行` : issue.fieldCode)].filter(Boolean).join(' · ');
        return onLocate ? <Button key={index} type="link" size="small" onClick={() => onLocate(issue)}>{location || '定位字段'}</Button>
          : <Typography.Text key={index} type="secondary">{location}</Typography.Text>;
      })}
      </Space>
      {required.length > 20 && <Pagination size="small" current={page} pageSize={20} total={required.length} showSizeChanger={false} onChange={setRequiredPage} />}
      </details>
    </div>}
    {openIssues.length > 0 && <Alert
      banner
      showIcon
      type={type}
      message={message}
      description={(
        <div className="data-import-quality-notice">
          <details>
            <summary>查看问题和来源位置</summary>
            <ul>
              {openIssues.map((issue, index) => {
                const sheetName = sheets.find((sheet) => sheet.sheetId === issue.sheetId)?.sheetName;
                const location = [
                  sheetName,
                  issue.address,
                  issue.rowNumber && !issue.address ? `${issue.rowNumber} 行` : undefined,
                ].filter(Boolean).join(' · ');
                return <li key={issue.id || `${issue.issueType}-${issue.sheetId}-${index}`}>
                  {location ? <Typography.Text strong>{location}：</Typography.Text> : null}
                  {importIssueMessage(issue.issueType, issue.message)}
                  {onLocate && <Button size="small" type="link" onClick={() => onLocate(issue)}>{issue.address && !requiresTemplateChange(issue.issueType) ? '修改数据' : '重新选择模板或文件'}</Button>}
                </li>;
              })}
            </ul>
          </details>
          <Typography.Text type="secondary">修改会自动保存到本次记录，原文件保持不变。</Typography.Text>
        </div>
      )}
    />}
    </>
  );
}
