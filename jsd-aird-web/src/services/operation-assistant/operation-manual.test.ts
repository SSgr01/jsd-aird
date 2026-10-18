import { matchRoutes } from 'react-router-dom';
import { routeConfig } from '@/routes/route-config';
import { requiredPermissionForPath } from '@/routes/route-permissions';
import { operationTestUser } from '@/test/operation-assistant-fixtures';
import {
  canUseOperationPage,
  currentOperationPage,
  operationAnswerText,
  operationManual,
  operationSuggestionLabel,
  operationSuggestions,
} from './operation-manual';

describe('operation handbook navigation', () => {
  it('maps every public help target to an existing page rather than a catch-all or a record placeholder', () => {
    for (const page of operationManual.pages) {
      const pathname = page.path.split('?')[0] || page.path;
      const matched = matchRoutes(routeConfig, pathname);
      expect(matched?.at(-1)?.route.path, page.id).not.toBe('*');
      expect(matched, page.id).not.toBeNull();
      expect(page.path).not.toContain(':');
      const permission = requiredPermissionForPath(pathname);
      if (permission && page.id !== 'knowledge-review')
        expect(page.permissions, page.id).toContain(permission);
      expect(
        operationManual.sections.some((section) => section.pageIds.some((id) => id === page.id)),
        page.id,
      ).toBe(true);
    }
    const login = matchRoutes(routeConfig, '/login');
    expect(login).toHaveLength(1);
    expect(
      matchRoutes(routeConfig, '/production-orders/order-1/workspace')?.[0]?.route.path,
    ).toBeUndefined();
  });

  it('recognizes standalone workspaces and exact pages before generic patterns', () => {
    expect(currentOperationPage('/experiments/upload')?.id).toBe('experiment-upload');
    expect(currentOperationPage('/assistant/model-center', '?tab=workbench')?.id).toBe('modeling-workbench');
    expect(currentOperationPage('/assistant/model-center', '?tab=models')?.id).toBe('model-management');
    expect(currentOperationPage('/assistant/model-center', '?modelId=model-1&tab=models')?.id).toBe('model-management');
    expect(currentOperationPage('/assistant/model-center', '?tab=settings')).toBeUndefined();
    expect(currentOperationPage('/experiments/template-imports/job-1')?.id).toBe(
      'experiment-upload',
    );
    expect(currentOperationPage('/production-orders/order-1/workspace')?.id).toBe(
      'production-list',
    );
    expect(currentOperationPage('/knowledge/view', '?reviewStatus=PENDING_REVIEW')?.id).toBe(
      'knowledge-review',
    );
  });

  it.each([
    '/assistant/modeling-settings',
    '/assistant/modeling-settings/samples',
    '/assistant/modeling-settings/samples/sample-1',
    '/assistant/model-management',
    '/assistant/model-center/targets/target-1',
    '/assistant/model-center/samples',
    '/assistant/model-center/samples/sample-1',
    '/experiments/imports/import-1',
    '/knowledge/review',
  ])('does not recognize or redirect a removed page: %s', (path) => {
    expect(currentOperationPage(path)).toBeUndefined();
    expect(matchRoutes(routeConfig, path)?.at(-1)?.route.path).toBe('*');
  });

  it('filters recommended questions and links by operation, page and menu grants', () => {
    const user = operationTestUser(['experiment.create']);
    expect(operationSuggestions('experiment-upload', user)[0]).toBe('怎样导入实验记录？');
    expect(operationSuggestionLabel(operationSuggestions('experiment-upload', user)[0]!)).toBe(
      '导入实验记录',
    );
    expect(operationSuggestionLabel('模型中心在哪里？')).toBe('模型中心');
    expect(operationSuggestions('experiment-upload', user).join(' ')).not.toContain('权限配置');
    const page = operationManual.pages.find((item) => item.id === 'experiment-upload')!;
    expect(canUseOperationPage(page, user)).toBe(true);
    expect(canUseOperationPage(page, { ...user, menuKeys: ['customer.list'] })).toBe(false);
    expect(canUseOperationPage(page, operationTestUser([]))).toBe(false);
    expect(canUseOperationPage(page, null)).toBe(false);
  });

  it('removes generated navigation links and incomplete evidence markers', () => {
    expect(
      operationAnswerText('步骤 [[H1]] [入口](//evil.example) ![图片](https://evil.example/x)'),
    ).toBe('步骤  入口 图片');
    expect(operationAnswerText('步骤 [[H')).toBe('步骤 ');
    expect(operationAnswerText('[按钮](javascript:alert(1))')).not.toContain('javascript:');
  });
});
