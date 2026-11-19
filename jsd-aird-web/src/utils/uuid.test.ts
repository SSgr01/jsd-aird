import { afterEach, describe, expect, it, vi } from 'vitest';
import { generateUUID } from './uuid';

const cryptoApi = globalThis.crypto;
const pattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

afterEach(() => vi.unstubAllGlobals());

describe('UUID browser compatibility', () => {
  it('generates unique v4 IDs on HTTP with only getRandomValues', () => {
    vi.stubGlobal('crypto', { getRandomValues: cryptoApi.getRandomValues.bind(cryptoApi) });
    const ids = Array.from({ length: 100 }, () => generateUUID());
    ids.forEach(id => expect(id).toMatch(pattern));
    expect(new Set(ids).size).toBe(100);
  });

  it('keeps the native UUID implementation when available', () => {
    const native = vi.fn(() => '11111111-1111-4111-8111-111111111111');
    vi.stubGlobal('crypto', { randomUUID: native });
    expect(generateUUID()).toBe('11111111-1111-4111-8111-111111111111');
    expect(native).toHaveBeenCalledOnce();
  });
});
