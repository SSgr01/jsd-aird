import { describe, expect, it } from 'vitest';
import { parseRecipient } from './sample-recipient-parse';

describe('parseRecipient', () => {
  it('reads labeled shipping details', () => {
    expect(parseRecipient('公司：松井材料有限公司\n收件人：张三\n电话：13800138000\n地址：江苏省苏州市工业园区星湖街1号')).toEqual({
      name: '张三', phone: '13800138000', address: '江苏省苏州市工业园区星湖街1号',
    });
  });
  it('reads a compact courier message', () => {
    expect(parseRecipient('张三，13800138000，江苏省苏州市工业园区星湖街1号')).toEqual({
      name: '张三', phone: '13800138000', address: '江苏省苏州市工业园区星湖街1号',
    });
  });
  it('leaves missing values for manual entry', () => {
    expect(parseRecipient('张三\n13800138000')).toEqual({ name: '张三', phone: '13800138000', address: undefined });
  });
});
