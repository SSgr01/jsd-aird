// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { appTheme } from './theme';

function luminance(value: unknown) {
  if (typeof value !== 'string' || !/^#[a-f\d]{6}$/i.test(value)) {
    throw new Error(`Expected a resolved hex color, received ${String(value)}`);
  }
  const [r = 0, g = 0, b = 0] = [1, 3, 5].map(offset => {
    const channel = Number.parseInt(value.slice(offset, offset + 2), 16) / 255;
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return r * 0.2126 + g * 0.7152 + b * 0.0722;
}

function expectReadable(foreground: unknown, background: unknown) {
  const a = luminance(foreground);
  const b = luminance(background);
  expect((Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)).toBeGreaterThanOrEqual(4.5);
}

describe('Monochrome theme readability', () => {
  const token = appTheme.token!;
  const button = appTheme.components!.Button!;

  it('keeps body text, captions and links readable on working surfaces', () => {
    for (const background of [token.colorBgContainer, token.colorBgLayout, token.colorPrimaryBg]) {
      for (const foreground of [token.colorText, token.colorTextSecondary, token.colorTextTertiary,
        token.colorTextPlaceholder, token.colorLink, token.colorLinkHover, token.colorLinkActive]) {
        expectReadable(foreground, background);
      }
    }
  });

  it('keeps white primary labels readable throughout black button interaction states', () => {
    for (const background of [button.colorPrimary, button.colorPrimaryHover, button.colorPrimaryActive]) {
      expectReadable(button.primaryColor, background);
      expect(luminance(background)).toBeLessThan(0.05);
    }
    expect(button.primaryColor).toBe('#ffffff');
  });

  it('preserves readable success, warning and error labels', () => {
    expectReadable(token.colorSuccessText, token.colorSuccessBg);
    expectReadable(token.colorWarningText, token.colorWarningBg);
    expectReadable(token.colorErrorText, token.colorErrorBg);
  });
});
