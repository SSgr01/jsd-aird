import { describe, expect, it } from 'vitest';
import { parseAssistantSseData } from './assistant-api';

describe('parseAssistantSseData', () => {
  it('parses JSON string payloads emitted by the normalized backend', () => {
    expect(parseAssistantSseData('"重排服务不可用"')).toBe('重排服务不可用');
  });

  it('keeps legacy unquoted warning payloads as text', () => {
    expect(parseAssistantSseData('重排服务不可用，已使用 RRF 排序')).toBe('重排服务不可用，已使用 RRF 排序');
  });

  it('parses structured stage payloads', () => {
    expect(parseAssistantSseData('{"status":"ready"}')).toEqual({ status: 'ready' });
  });
});
