import { describe, expect, it } from 'vitest';

import type { ExperimentImportConfiguration } from './types';
import { isExperimentTemplate } from './experiment-semantics';

const configuration = (
  patch: Partial<ExperimentImportConfiguration> = {},
): ExperimentImportConfiguration => ({
  templateUsage: 'GENERAL_DATA',
  identities: [],
  listProjections: [],
  ...patch,
});

describe('isExperimentTemplate', () => {
  it('hides semantics for ordinary data templates', () => {
    expect(isExperimentTemplate(configuration())).toBe(false);
  });

  it('hides semantics when only a batch number was inferred', () => {
    expect(isExperimentTemplate(configuration({
      templateUsage: 'EXPERIMENT_DATA',
      identities: [{
        identityType: 'BATCH_NO',
        sourceKind: 'BINDING',
        componentId: 'component-1',
        bindingId: 'binding-1',
      }],
    }))).toBe(false);
  });

  it('keeps semantics for an explicit experiment identity', () => {
    expect(isExperimentTemplate(configuration({
      templateUsage: 'EXPERIMENT_DATA',
      identities: [{
        identityType: 'EXPERIMENT_NO',
        sourceKind: 'BINDING',
        componentId: 'component-1',
        bindingId: 'binding-1',
      }],
    }))).toBe(true);
  });

  it('keeps semantics for a structured experiment matrix', () => {
    expect(isExperimentTemplate(configuration({
      templateUsage: 'EXPERIMENT_DATA',
      listProjections: [{
        listProjectionId: 'matrix-1',
        domain: 'FORMULA',
        componentId: 'component-1',
        recordAxis: 'COLUMN',
        itemAxis: 'ROW',
        labelRange: 'A1:A2',
        valueRange: 'B1:C2',
        labelSemantic: 'MATERIAL_NAME',
        valueSemantic: 'RATIO',
      }],
    }))).toBe(true);
  });
});
