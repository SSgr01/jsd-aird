import { describe, expect, it } from 'vitest'

import { canViewPath, firstAccessiblePath, requiredPermissionForPath } from './route-permissions'

describe('modeling settings route permissions', () => {
  it('exposes one model center entry for either read permission', () => {
    expect(requiredPermissionForPath('/assistant/model-center')).toBeUndefined()
    expect(canViewPath('/assistant/model-center', ['ai.model.read'])).toBe(true)
    expect(canViewPath('/assistant/model-center', ['ai.modeling.read'])).toBe(true)
    expect(canViewPath('/assistant/model-center', ['ai.use'])).toBe(false)
  })

  it('uses the new read permission without inheriting ai.use', () => {
    expect(requiredPermissionForPath('/assistant/modeling-settings')).toBe('ai.modeling.read')
    expect(canViewPath('/assistant/modeling-settings', ['ai.use'])).toBe(false)
    expect(canViewPath('/assistant/modeling-settings', ['ai.modeling.read'])).toBe(true)
  })

  it('opens model center for model-only and modeling-only users', () => {
    expect(firstAccessiblePath(['ai.modeling.read'])).toBe('/assistant/model-center')
    expect(firstAccessiblePath(['ai.model.read'])).toBe('/assistant/model-center')
    expect(firstAccessiblePath(['ai.model.read', 'ai.modeling.read'])).toBe('/assistant/model-center')
  })
})
