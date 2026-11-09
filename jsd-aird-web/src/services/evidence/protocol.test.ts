import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import fixture from '../../../../jsd-aird-agent/contracts/evidence.json';
import { applyTextUpdate, canonicalSourceJson } from './identity';
import type { SourceRef } from './protocol';

describe('shared evidence vectors', () => {
  it('matches Java/Python IDs and preserves raw text, labels and quote spans', () => {
    for (const item of fixture.cases) {
      const digest = createHash('sha256')
        .update(canonicalSourceJson(item.source as SourceRef))
        .digest('hex');
      expect('e' + digest.slice(0, 24)).toBe(item.evidence_id);
      expect(createHash('sha256').update(item.text).digest('hex')).toBe(item.source.content_sha256);
      for (const quote of item.quotes) {
        expect(Array.from(item.text).slice(quote.start, quote.end).join('')).toBe(quote.text);
        expect(quote.evidence_id).toBe(item.evidence_id);
      }
    }
  });
  it('uses explicit transport replacement without mixing conversation snapshots', () => {
    expect(applyTextUpdate('old', 'new', true)).toBe('new');
    expect(applyTextUpdate('first', ' second')).toBe('first second');
  });
});
