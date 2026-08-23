import { describe, expect, it } from 'vitest';

import { citationEvidenceLabel } from './citation-utils';

describe('citationEvidenceLabel', () => {
  it('shows the number of source anchors for multi-source evidence', () => {
    expect(citationEvidenceLabel({ anchors: [{ page: 1 }, { page: 2 }] })).toBe(' · 2处依据');
  });

  it('keeps a single-source citation compact', () => {
    expect(citationEvidenceLabel({ anchors: [{ page: 1 }] })).toBe('');
  });
});
