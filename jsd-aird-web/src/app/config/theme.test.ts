// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { appTheme } from './theme';

function luminance(value: unknown) {
  if (typeof value !== 'string' || !/^#[a-f\d]{6}$/i.test(value)) {
    throw new Error(`Expected a resolved hex color, received ${String(value)}`);
  }
  const [r = 0, g = 0, b = 0] = [1, 3, 5].map((offset) => {
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

describe('Soft rose theme readability', () => {
  const token = appTheme.token!;
  const button = appTheme.components!.Button!;

  const css = readFileSync(new URL('../../styles/variables.css', import.meta.url), 'utf8');
  const variables = new Map<string, string>(
    [...css.matchAll(/(--[\w-]+):\s*([^;]+);/g)].map((match) => [match[1]!, match[2]!.trim()]),
  );
  function cssColor(name: string): string {
    const value = variables.get(name);
    if (!value) throw new Error(`Missing CSS token ${name}`);
    return value.startsWith('var(') ? cssColor(value.slice(4, -1)) : value;
  }

  it('keeps custom CSS controls consistent with Ant Design and readable on tinted surfaces', () => {
    for (const [name, value] of Object.entries({
      '--app-primary': token.colorPrimary,
      '--app-action': button.colorPrimary,
      '--app-action-hover': button.colorPrimaryHover,
      '--app-action-active': button.colorPrimaryActive,
      '--app-primary-soft': token.colorPrimaryBg,
      '--app-background': token.colorBgLayout,
      '--app-text': token.colorText,
      '--app-text-secondary': token.colorTextSecondary,
      '--app-info': token.colorInfoText,
      '--app-success': token.colorSuccessText,
      '--app-warning': token.colorWarningText,
      '--app-danger': token.colorErrorText,
    }))
      expect(cssColor(name)).toBe(value);
    for (const surface of [
      '--app-surface',
      '--app-navigation',
      '--app-primary-soft',
      '--app-info-soft',
      '--app-success-soft',
      '--app-warning-soft',
      '--app-danger-soft',
    ]) {
      for (const ink of ['--app-text', '--app-text-secondary', '--app-text-muted'])
        expectReadable(cssColor(ink), cssColor(surface));
    }
  });

  it('keeps body text, captions and links readable on working surfaces', () => {
    for (const background of [token.colorBgContainer, token.colorBgLayout, token.colorPrimaryBg]) {
      for (const foreground of [
        token.colorText,
        token.colorTextSecondary,
        token.colorTextTertiary,
        token.colorTextPlaceholder,
        token.colorLink,
        token.colorLinkHover,
        token.colorLinkActive,
      ]) {
        expectReadable(foreground, background);
      }
    }
  });

  it('keeps white primary labels readable throughout rose button interaction states', () => {
    for (const background of [
      button.colorPrimary,
      button.colorPrimaryHover,
      button.colorPrimaryActive,
    ]) {
      expectReadable(button.primaryColor, background);
    }
    expect(button.primaryColor).toBe('#ffffff');
  });

  it('preserves readable success, warning and error labels', () => {
    expectReadable(token.colorInfoText, token.colorInfoBg);
    expectReadable(token.colorSuccessText, token.colorSuccessBg);
    expectReadable(token.colorWarningText, token.colorWarningBg);
    expectReadable(token.colorErrorText, token.colorErrorBg);
  });
});
