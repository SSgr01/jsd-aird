import { describe, expect, it } from 'vitest';

import { upgradeLegacyDocxTextRunEnds } from './word-snapshot-compatibility';

describe('legacy Word snapshot compatibility', () => {
  it('converts v5 inclusive text-run ends without changing the text', () => {
    const snapshot = {
      wordImport: { parserVersion: 'docx-univer-v5' },
      body: {
        dataStream: '生产试制申请记录\r\n',
        textRuns: [{ st: 0, ed: 7, ts: { fs: 20 } }],
      },
    };

    expect(upgradeLegacyDocxTextRunEnds(snapshot)).toBe(true);
    const [textRun] = snapshot.body.textRuns;
    expect(textRun?.ed).toBe(8);
    expect(snapshot.body.dataStream).toBe('生产试制申请记录\r\n');
    expect(snapshot.wordImport.parserVersion).toBe('docx-univer-v6');
  });

  it('does not extend a run that already ends at an exclusive boundary', () => {
    const snapshot = {
      wordImport: { parserVersion: 'docx-univer-v5' },
      body: {
        dataStream: '标题\r\n',
        textRuns: [{ st: 0, ed: 2, ts: { fs: 20 } }],
      },
    };

    upgradeLegacyDocxTextRunEnds(snapshot);

    const [textRun] = snapshot.body.textRuns;
    expect(textRun?.ed).toBe(2);
  });

  it('leaves current snapshots unchanged', () => {
    const snapshot = {
      wordImport: { parserVersion: 'docx-univer-v6' },
      body: {
        dataStream: '标题\r\n',
        textRuns: [{ st: 0, ed: 2, ts: { fs: 20 } }],
      },
    };

    expect(upgradeLegacyDocxTextRunEnds(snapshot)).toBe(false);
    const [textRun] = snapshot.body.textRuns;
    expect(textRun?.ed).toBe(2);
  });
});
