import { describe, expect, it } from 'vitest'

import { canViewPath, firstAccessiblePath, requiredPermissionForPath } from './route-permissions'

describe('modeling settings route permissions', () => {
  it('exposes one model center entry for either read permission', () => {
    expect(requiredPermissionForPath('/assistant/model-center')).toBeUndefined()
    expect(canViewPath('/assistant/model-center', ['ai.model.read'])).toBe(true)
    expect(canViewPath('/assistant/model-center', ['ai.modeling.read'])).toBe(true)
    expect(canViewPath('/assistant/model-center', ['ai.use'])).toBe(false)
  })

  it('opens model center for model-only and modeling-only users', () => {
    expect(firstAccessiblePath(['ai.modeling.read'])).toBe('/assistant/model-center')
    expect(firstAccessiblePath(['ai.model.read'])).toBe('/assistant/model-center')
    expect(firstAccessiblePath(['ai.model.read', 'ai.modeling.read'])).toBe('/assistant/model-center')
  })
})
