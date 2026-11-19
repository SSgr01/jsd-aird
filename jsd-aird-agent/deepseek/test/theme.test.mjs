import test from 'node:test';
import assert from 'node:assert/strict';
import { themeTokens } from '../../enterprise-plugin/src/theme.mjs';

const luminance = hex => {
  const [r, g, b] = [1, 3, 5].map(offset => {
    const channel = parseInt(hex.slice(offset, offset + 2), 16) / 255;
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return r * 0.2126 + g * 0.7152 + b * 0.0722;
};

for (const mode of ['light', 'dark']) {
  test(`${mode} workbench text, links and primary actions meet WCAG AA`, () => {
    const color = key => themeTokens['--dsw-' + key][mode];
    const readable = (foreground, background) => {
      const a = luminance(color(foreground)), b = luminance(color(background));
      const ratio = (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
      assert.ok(ratio >= 4.5, `${mode}: ${foreground} on ${background}: ${ratio.toFixed(2)}`);
    };
    for (const surface of ['alias-bg-base', 'alias-bg-layer-1', 'alias-bg-layer-2', 'alias-bg-layer-3', 'specific-bubble', 'specific-sidebar-fill', 'specific-sidebar-nav-item-active', 'alias-state-success-tertiary', 'alias-state-warn-tertiary']) {
      for (const label of ['alias-label-primary', 'alias-label-secondary', 'alias-label-caption', 'alias-link']) {
        readable(label, surface);
      }
    }
    for (const fill of ['alias-button-primary-fill', 'alias-button-primary-hover']) {
      readable('alias-label-primary-foreground', fill);
    }
  });
}
