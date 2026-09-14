import type {
  SpectrumAnswerIntent,
  SpectrumResult,
  SpectrumResultPresentation,
} from '@/services/spectrum';

const detailSectionKeys = [
  'observations',
  'comparisons',
  'peakMappings',
  'candidateInterpretations',
  'overlapCandidates',
  'unmatchedFeatures',
  'conflicts',
  'suggestedValidationExperiments',
  'testConditionLimitations',
  'aiReviewFocus',
];

const summaryKeys = [
  'summary',
  'observation',
  'comparison',
  'description',
  'interpretation',
  'possibleInterpretation',
  'conclusion',
  'reason',
  'experiment',
  'purpose',
  'feature',
  'region',
  'peak',
  'peakPosition',
];

export interface ResolvedSpectrumPresentation extends SpectrumResultPresentation {
  legacy: boolean;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function conciseText(value: unknown, sentenceLimit = 2) {
  if (typeof value !== 'string') return '';
  const cleaned = value
    .replace(/^\s{0,3}#{1,6}\s*/gm, '')
    .replace(/^\s*(?:[-*+] |\d+[.)、]\s*)/gm, '')
    .replace(/\s+/g, ' ')
    .trim();
  if (!cleaned || cleaned.startsWith('{')) return '';
  const sentences = cleaned.match(/[^。！？!?]+[。！？!?]?/g)?.map((item) => item.trim()) || [];
  return sentences.slice(0, sentenceLimit).join('') || cleaned;
}

export function spectrumItemSummary(value: unknown) {
  if (typeof value === 'string' || typeof value === 'number') return conciseText(String(value));
  if (!isRecord(value)) return '';
  for (const key of summaryKeys) {
    const item = value[key];
    if ((typeof item === 'string' || typeof item === 'number') && String(item).trim()) {
      return conciseText(String(item));
    }
  }
  return '';
}

function normalizeText(value: string) {
  return value.toLocaleLowerCase().replace(/[\p{P}\p{S}\s]+/gu, '');
}

export function isRepeatedSpectrumText(candidate: string, displayed: string[]) {
  const normalized = normalizeText(candidate);
  if (!normalized) return true;
  return displayed.some((item) => {
    const existing = normalizeText(item);
    const shorter = normalized.length <= existing.length ? normalized : existing;
    const longer = normalized.length <= existing.length ? existing : normalized;
    return normalized === existing || (shorter.length >= 12 && longer.includes(shorter));
  });
}

function collectUnique(values: unknown[], displayed: string[], limit: number) {
  const result: string[] = [];
  for (const value of values) {
    const text = spectrumItemSummary(value);
    if (!text || isRepeatedSpectrumText(text, [...displayed, ...result])) continue;
    result.push(text);
    if (result.length >= limit) break;
  }
  displayed.push(...result);
  return result;
}

function legacyConclusion(result: SpectrumResult, fallbackContent?: string) {
  if (
    result.referenceAvailability &&
    result.evidenceSufficiency?.startsWith('INSUFFICIENT')
  ) {
    return '当前证据不足以支持明确归因，也不能建立可靠的峰位映射。';
  }
  const answer = conciseText(result.answerMarkdown || fallbackContent);
  if (answer) return answer;
  const boundary = result.conclusionBoundary;
  if (boundary && !/^[A-Z0-9_]+$/.test(boundary)) return conciseText(boundary);
  return '已完成图谱分析，以下结论仍需结合原始数据和测试条件复核。';
}

function validPresentation(value: unknown): value is SpectrumResultPresentation {
  if (!isRecord(value)) return false;
  return (
    typeof value.conclusion === 'string' &&
    Array.isArray(value.keyFindings) &&
    Array.isArray(value.validationSteps) &&
    Array.isArray(value.detailSectionKeys)
  );
}

export function resolveSpectrumPresentation(
  result: SpectrumResult,
  fallbackContent?: string,
): ResolvedSpectrumPresentation {
  if (validPresentation(result.presentation)) {
    const displayed = [result.presentation.conclusion];
    const keyFindings = collectUnique(result.presentation.keyFindings, displayed, 3);
    const validationSteps = collectUnique(result.presentation.validationSteps, displayed, 3);
    return {
      ...result.presentation,
      version: result.presentation.version || 1,
      primaryIntent: result.presentation.primaryIntent || 'OVERVIEW',
      conclusion: conciseText(result.presentation.conclusion),
      keyFindings,
      validationSteps,
      detailSectionKeys: result.presentation.detailSectionKeys.filter((key) =>
        detailSectionKeys.includes(key),
      ),
      legacy: false,
    };
  }

  const conclusion = legacyConclusion(result, fallbackContent);
  const displayed = [conclusion];
  const sourceFields = result.referenceAvailability
    ? ['comparisons', 'candidateInterpretations', 'observations', 'conflicts']
    : ['observations', 'comparisons', 'candidateInterpretations', 'conflicts'];
  const findings: string[] = [];
  for (const field of sourceFields) {
    const values = result[field];
    if (!Array.isArray(values)) continue;
    findings.push(...collectUnique(values, displayed, 3 - findings.length));
    if (findings.length >= 3) break;
  }
  const validationSteps = collectUnique(
    Array.isArray(result.suggestedValidationExperiments)
      ? result.suggestedValidationExperiments
      : [],
    displayed,
    3,
  );
  const availableDetails = detailSectionKeys.filter((key) => {
    const value =
      key === 'conflicts' &&
      (!Array.isArray(result.conflicts) || result.conflicts.length === 0)
        ? result.uncertainty
        : result[key];
    return Array.isArray(value) && value.length > 0;
  });
  const primaryIntent: SpectrumAnswerIntent = result.referenceAvailability
    ? 'ATTRIBUTION'
    : 'OVERVIEW';
  return {
    version: 1,
    primaryIntent,
    conclusion,
    keyFindings: findings,
    validationSteps,
    detailSectionKeys: availableDetails,
    legacy: true,
  };
}

export function remainingSpectrumDetails(values: unknown[], displayed: string[]) {
  return values.filter((value) => {
    if (typeof value === 'object' && value !== null) return true;
    const summary = spectrumItemSummary(value);
    return !summary || !isRepeatedSpectrumText(summary, displayed);
  });
}
