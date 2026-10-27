import { LexerTreeBuilder, deserializeRangeWithSheet, serializeRangeToRefString, sequenceNodeType, generateStringWithSequence } from '@univerjs/preset-sheets-core';
import { parseRange } from './structure-migration';

const lexer = new LexerTreeBuilder();

export function offsetWorkbookFormula(formula: string, columns: number, rows: number) {
  return lexer.moveFormulaRefOffset(formula, columns, rows);
}

export function expandAggregateReferences(formula: string, changes: Array<{ before: string; after: string; axis: 'ROW' | 'COLUMN' }>) {
  const nodes = lexer.sequenceNodesBuilder(formula);
  if (!nodes) return formula;
  const updated = nodes.map(node => {
    if (typeof node === 'string' || node.nodeType !== sequenceNodeType.REFERENCE) return node;
    const reference = deserializeRangeWithSheet(node.token);
    if (reference.sheetName || reference.unitId) return node;
    const range = { ...reference.range };
    for (const change of changes) {
      const before = parseRange(change.before), after = parseRange(change.after);
      if (!before || !after) continue;
      if (change.axis === 'ROW' && range.startRow === before.startRow - 1 && range.endRow === before.endRow - 1
        && range.startColumn >= before.startColumn - 1 && range.endColumn <= before.endColumn - 1) {
        range.startRow = after.startRow - 1; range.endRow = after.endRow - 1;
      } else if (change.axis === 'COLUMN' && range.startColumn === before.startColumn - 1 && range.endColumn === before.endColumn - 1
        && range.startRow >= before.startRow - 1 && range.endRow <= before.endRow - 1) {
        range.startColumn = after.startColumn - 1; range.endColumn = after.endColumn - 1;
      } else continue;
      return { ...node, token: serializeRangeToRefString({ ...reference, range }) };
    }
    return node;
  });
  return `${formula.startsWith('=') ? '=' : ''}${generateStringWithSequence(updated)}`;
}
