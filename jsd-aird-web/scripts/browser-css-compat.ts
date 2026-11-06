import postcss, { type Declaration, type Plugin, type Rule } from 'postcss';

// Default theme tokens. Modern browsers keep evaluating the original variables;
// Chromium 106 uses these equivalent solid colors instead of color-mix().
const colors: Record<string, string> = {
  'app-primary': '#315cf6', 'app-success': '#18a66a', 'app-danger': '#dc2626', 'app-focus': '#2448d8',
  'app-surface': '#fefeff', 'app-border': '#dce4ef',
  'oa-accent': '#315cf6', 'oa-surface': '#ffffff', 'oa-border': '#e8edf4',
};
const supportsColorMix = '(color: color-mix(in srgb, red, blue))';

function fallback(value: string): string {
  return value.replace(/color-mix\(in srgb, var\(--([\w-]+)\) (\d+)%, (?:var\(--([\w-]+)\)|(transparent))\)/g,
    (original: string, first: string, percent: string, second: string | undefined, transparent: string | undefined) => {
      const a = colors[first];
      const b = second ? colors[second] : undefined;
      if (!a || (!transparent && !b)) return original;
      const channels = (hex: string) => [1, 3, 5].map(index => parseInt(hex.slice(index, index + 2), 16));
      const left = channels(a);
      if (transparent) return `rgb(${left.join(' ')} / ${percent}%)`;
      if (!b) return original;
      const right = channels(b);
      const weight = Number(percent) / 100;
      return '#' + left.map((channel, index) => Math.round(channel * weight + right[index] * (1 - weight))
        .toString(16).padStart(2, '0')).join('');
    });
}

/** Shared by dev, production and standalone Office fixtures. */
export function browserCssCompat(): Plugin {
  return {
    postcssPlugin: 'aird-browser-css-compat',
    Once(root) {
      const rules: Rule[] = [];
      root.walkRules(rule => { rules.push(rule); });
      for (const rule of rules) {
        const modern: Declaration[] = [];
        for (const node of [...rule.nodes]) {
          if (node.type !== 'decl') continue;
          if (node.value.includes('dvh') && !node.prop.startsWith('--')) {
            node.cloneBefore({ value: node.value.replace(/dvh/g, 'vh') });
          }
          if (!node.value.includes('color-mix(') || !/--(?:app|oa)-/.test(node.value)) continue;
          const legacy = fallback(node.value);
          if (legacy.includes('color-mix(')) throw node.error('Missing Chromium 106 color fallback');
          modern.push(node.clone());
          node.value = legacy;
        }
        if (modern.length) {
          // A declaration containing var() can fail at computed-value time in
          // old browsers. A preceding fallback alone would then be discarded.
          const supported = postcss.atRule({ name: 'supports', params: supportsColorMix });
          supported.append(rule.clone({ nodes: modern }));
          rule.after(supported);
        }
      }
    },
  };
}
