import { Alert, Button, Typography } from 'antd';

import type { DataIssue, DataSheet } from '@/services/data/data-api';

export function DataImportQualityNotice({ issues, sheets, onLocate }: { issues: DataIssue[]; sheets: DataSheet[]; onLocate?: (issue: DataIssue) => void }) {
  const openIssues = issues.filter((issue) => !['RESOLVED', 'IGNORED'].includes(issue.status));
  if (!openIssues.length) return null;

  const blockers = openIssues.filter((issue) => issue.severity === 'BLOCKER').length;
  const warnings = openIssues.filter((issue) => issue.severity === 'WARNING').length;
  const type = blockers ? 'error' : warnings ? 'warning' : 'info';
  const message = blockers
    ? `${blockers} 项问题会阻止提交，另有 ${openIssues.length - blockers} 项提示`
    : warnings
      ? `${warnings} 项需要核对`
      : `${openIssues.length} 项导入提示`;

  return (
    <Alert
      banner
      showIcon
      type={type}
      message={message}
      description={(
        <div className="data-import-quality-notice">
          <details open={blockers > 0}>
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
                  {issue.message}
                  {issue.fieldCode ? `（${issue.fieldCode}）` : ''}
                  {onLocate && <Button size="small" type="link" onClick={() => onLocate(issue)}>{issue.address ? '定位并修正' : '处理映射与工作表'}</Button>}
                </li>;
              })}
            </ul>
          </details>
          <Typography.Text type="secondary">提示不会改写原文件；如需修正，系统会单独保存修正值。</Typography.Text>
        </div>
      )}
    />
  );
}
