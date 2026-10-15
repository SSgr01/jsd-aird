import { describe, expect, it } from 'vitest'

import { HttpError } from '@/services/http/errors'
import { workbenchRequestMessage } from './modeling-workbench-feedback'

describe('modeling workbench feedback', () => {
  it('shows actionable validation details returned by the API', () => {
    expect(workbenchRequestMessage(new HttpError('至少选择一列 X', 'SYS_VALIDATION_ERROR', 422)))
      .toBe('至少选择一列 X')
  })

  it('explains when the login permission does not allow training', () => {
    expect(workbenchRequestMessage(new HttpError('denied', 'PERMISSION_DENIED', 403)))
      .toBe('当前账号没有训练权限，请联系管理员开通后重试。')
  })

  it('explains an expired page token and reassures the user that their selection remains', () => {
    expect(workbenchRequestMessage(new HttpError('expired', 'CSRF_TOKEN_INVALID', 403)))
      .toContain('已选数据和 X/Y 会保留')
  })
})
