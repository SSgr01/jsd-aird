const LEGACY_DOCX_PARSER_VERSION = 'docx-univer-v5';
const CURRENT_DOCX_PARSER_VERSION = 'docx-univer-v6';

export function upgradeLegacyDocxTextRunEnds(snapshot: Record<string, unknown>) {
  const wordImport = snapshot.wordImport;
  if (!isRecord(wordImport) || wordImport.parserVersion !== LEGACY_DOCX_PARSER_VERSION) return false;

  upgradeBody(snapshot.body);
  upgradeSegments(snapshot.headers);
  upgradeSegments(snapshot.footers);
  wordImport.parserVersion = CURRENT_DOCX_PARSER_VERSION;
  wordImport.migratedFromParserVersion = LEGACY_DOCX_PARSER_VERSION;
  return true;
}

function upgradeSegments(value: unknown) {
  if (!isRecord(value)) return;
  for (const segment of Object.values(value)) {
    if (isRecord(segment)) upgradeBody(segment.body);
  }
}

function upgradeBody(value: unknown) {
  if (!isRecord(value) || typeof value.dataStream !== 'string') return;
  const dataStream = value.dataStream;
  const textRuns = value.textRuns;
  if (!isUnknownArray(textRuns)) return;
  for (let index = 0; index < textRuns.length; index += 1) {
    const run = textRuns[index];
    if (!isRecord(run) || typeof run.ed !== 'number' || !Number.isFinite(run.ed)) continue;
    const next = textRuns[index + 1];
    const nextStart = isRecord(next) && typeof next.st === 'number' ? next.st : undefined;
    const end = Math.trunc(run.ed);
    if (end < 0 || end >= dataStream.length) continue;

    // v5 wrote inclusive offsets. Do not extend a run that an editor has
    // already converted to an exclusive boundary before this migration.
    const alreadyEndsAtNextRun = nextStart === end;
    const alreadyEndsAtControlCharacter = dataStream.charCodeAt(end) <= 31;
    if (!alreadyEndsAtNextRun && !alreadyEndsAtControlCharacter) run.ed = end + 1;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function isUnknownArray(value: unknown): value is unknown[] {
  return Array.isArray(value);
}
