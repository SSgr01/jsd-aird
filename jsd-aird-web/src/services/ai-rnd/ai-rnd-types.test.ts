/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import type { CoveragePreview, PredictionResponse, TypedPrediction } from './ai-rnd-types'

describe('ai-rnd.v1 frozen types', () => {
  it('reads the shared v2 golden without changing 98.8 or result kinds', () => {
    const golden = JSON.parse(
      readFileSync(
        resolve(process.cwd(), '../jsd-aird-ai/contracts/formula-model.v2/examples/formula-model.v2.golden.json'),
        'utf8',
      ),
    ) as { formula: { recordedTotal: number }; typedResults: Array<{ result: TypedPrediction }> }
    expect(golden.formula.recordedTotal).toBe(98.8)
    expect(golden.typedResults.map((item) => item.result.resultType)).toEqual([
      'CONTINUOUS',
      'ORDINAL',
      'BINARY',
      'CATEGORICAL',
    ])
  })

  it('expresses a blocked target without a prediction', () => {
    const response: PredictionResponse = {
      requestId: 'request', predictionId: 'prediction', executionStatus: 'SUCCEEDED', outcomeStatus: 'BLOCKED',
      results: [{ targetId: 'target', status: 'BLOCKED', errorCode: 'NO_ACTIVE_MODEL', message: 'no model' }],
    }
    expect(response.results[0]).not.toHaveProperty('prediction')
  })

  it('expresses the R02 coverage state without fake zero counts', () => {
    const preview: CoveragePreview = {
      evaluationStatus: 'NOT_EVALUATED',
      unavailableReason: 'DATA_PIPELINE_NOT_READY',
      validationIssues: [],
      schemaHash: 'a'.repeat(64),
      changedSampleIds: [],
    }
    expect(preview.candidateSamples).toBeUndefined()
    expect(preview.trainable).toBeUndefined()
    expect(preview.excluded).toBeUndefined()
  })
})
