import { mergeLocales } from '@univerjs/presets';
import { UniverDocsDrawingPreset } from '@univerjs/preset-docs-drawing';
import DrawingZhCN from '@univerjs/preset-docs-drawing/locales/zh-CN';
import { UniverDocsHyperLinkPreset } from '@univerjs/preset-docs-hyper-link';
import HyperLinkZhCN from '@univerjs/preset-docs-hyper-link/locales/zh-CN';
import { UniverFindReplacePlugin } from '@univerjs/find-replace';
import FindReplaceZhCN from '@univerjs/find-replace/locale/zh-CN';
import { UniverDocsFindReplacePlugin } from '@univerjs/docs-find-replace';

import '@univerjs/preset-docs-drawing/lib/index.css';
import '@univerjs/preset-docs-hyper-link/lib/index.css';
import '@univerjs/find-replace/lib/index.css';

export const docsFeatureLocale: Record<string, unknown> = mergeLocales(DrawingZhCN, HyperLinkZhCN, FindReplaceZhCN);
export const docsFeaturePlugins = [UniverFindReplacePlugin, UniverDocsFindReplacePlugin];
export function docsFeaturePresets() {
  return [UniverDocsDrawingPreset(), UniverDocsHyperLinkPreset()];
}
