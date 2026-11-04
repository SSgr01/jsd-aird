// Geometry serves the original-text reader. Keep it in the durable details,
// without sending every polygon back to the model on each research turn.
const evidenceTools = new Set([
  'knowledge_search', 'private_document_search', 'document_read', 'private_document_read',
  'document_outline', 'private_document_outline', 'evidence_context', 'evidence_read', 'table_read',
  'matrix_record', 'matrix_read',
]);
const readerFields = new Set([
  'polygon', 'pageWidth', 'pageHeight', 'rotation', 'coordinateSpace',
  'providerCoordinateSpace', 'sourceNodeKeys',
]);

function modelAnchor(anchor) {
  if (!anchor || typeof anchor !== 'object' || Array.isArray(anchor)) return anchor;
  return Object.fromEntries(Object.entries(anchor).filter(([key]) => !readerFields.has(key))
    .map(([key, value]) => [key, key === 'original' ? modelAnchor(value) : value]));
}

function modelEvidence(item) {
  if (Array.isArray(item)) return item.map(modelEvidence);
  if (!item || typeof item !== 'object') return item;
  return Object.fromEntries(Object.entries(item).map(([key, value]) => [key,
    key === 'source' && value?.anchor
      ? { ...value, anchor: modelAnchor(value.anchor) } : modelEvidence(value)]));
}

export function modelToolResult(name, result) {
  if (!evidenceTools.has(name)) return result;
  return modelEvidence(result);
}
