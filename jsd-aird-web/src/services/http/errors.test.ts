import { describe, expect, it } from 'vitest';
import { httpErrorMessage } from './errors';

describe('HTTP error messages', () => {
  it('distinguishes a rejected origin from denied account permissions', () => {
    expect(httpErrorMessage(403, undefined, 'Invalid CORS request', undefined)).toContain('访问地址');
    expect(httpErrorMessage(403, undefined, undefined, 'Request failed with status code 403')).toContain('服务配置');
    expect(httpErrorMessage(403, 'PERMISSION_DENIED', undefined, undefined)).toBe('当前用户没有操作权限');
    expect(httpErrorMessage(403, 'PERMISSION_DENIED', '没有实验审核权限', undefined)).toBe('没有实验审核权限');
  });
});
