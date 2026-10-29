import { mergeLocales } from '@univerjs/presets';
import { UniverSheetsFilterPreset } from '@univerjs/preset-sheets-filter';
import FilterZhCN from '@univerjs/preset-sheets-filter/locales/zh-CN';
import { UniverSheetsSortPreset } from '@univerjs/preset-sheets-sort';
import SortZhCN from '@univerjs/preset-sheets-sort/locales/zh-CN';
import { UniverSheetsFindReplacePreset } from '@univerjs/preset-sheets-find-replace';
import FindReplaceZhCN from '@univerjs/preset-sheets-find-replace/locales/zh-CN';
import { UniverSheetsDataValidationPreset } from '@univerjs/preset-sheets-data-validation';
import DataValidationZhCN from '@univerjs/preset-sheets-data-validation/locales/zh-CN';
import { UniverSheetsConditionalFormattingPreset } from '@univerjs/preset-sheets-conditional-formatting';
import ConditionalFormattingZhCN from '@univerjs/preset-sheets-conditional-formatting/locales/zh-CN';
import { UniverSheetsHyperLinkPreset } from '@univerjs/preset-sheets-hyper-link';
import HyperLinkZhCN from '@univerjs/preset-sheets-hyper-link/locales/zh-CN';
import { UniverSheetsNotePreset } from '@univerjs/preset-sheets-note';
import NoteZhCN from '@univerjs/preset-sheets-note/locales/zh-CN';

import '@univerjs/preset-sheets-filter/lib/index.css';
import '@univerjs/preset-sheets-sort/lib/index.css';
import '@univerjs/preset-sheets-find-replace/lib/index.css';
import '@univerjs/preset-sheets-data-validation/lib/index.css';
import '@univerjs/preset-sheets-conditional-formatting/lib/index.css';
import '@univerjs/preset-sheets-hyper-link/lib/index.css';
import '@univerjs/preset-sheets-note/lib/index.css';

export const sheetsFeatureLocale: Record<string, unknown> = mergeLocales(
  FilterZhCN, SortZhCN, FindReplaceZhCN, DataValidationZhCN,
  ConditionalFormattingZhCN, HyperLinkZhCN, NoteZhCN,
);

export function sheetsFeaturePresets() {
  return [
    UniverSheetsFilterPreset(), UniverSheetsSortPreset(), UniverSheetsFindReplacePreset(),
    UniverSheetsDataValidationPreset(), UniverSheetsConditionalFormattingPreset(),
    UniverSheetsHyperLinkPreset(), UniverSheetsNotePreset(),
  ];
}
