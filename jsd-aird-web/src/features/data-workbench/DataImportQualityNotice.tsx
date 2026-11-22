import { Alert, Button, Space, Typography } from 'antd';

import type { DataIssue, DataSheet } from '@/services/data/data-api';
import { distinctRequiredNotices, importIssueMessage, requiresTemplateChange } from '@/services/data/import-guidance';

export function DataImportQualityNotice({ issues, sheets, onLocate }: { issues: DataIssue[]; sheets: DataSheet[]; onLocate?: (issue: DataIssue) => void }) {
  const required = distinctRequiredNotices(issues);
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
    {required.length > 0 && <Space size={4} wrap aria-label="必填字段提示">
      <Typography.Text type="secondary">{required.length} 处必填字段未填写，可稍后补充，不影响提交。</Typography.Text>
      {required.map((issue, index) => {
        const location = [sheets.find(sheet => sheet.sheetId === issue.sheetId)?.sheetName, issue.address || (issue.rowNumber ? `${issue.rowNumber} 行` : issue.fieldCode)].filter(Boolean).join(' · ');
        return onLocate ? <Button key={index} type="link" size="small" onClick={() => onLocate(issue)}>{location || '定位字段'}</Button>
          : <Typography.Text key={index} type="secondary">{location}</Typography.Text>;
      })}
    </Space>}
    {openIssues.length > 0 && <Alert
      banner
      showIcon
      type={type}
      message={message}
      description={(
        <div className="data-import-quality-notice">
          <details open>
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
