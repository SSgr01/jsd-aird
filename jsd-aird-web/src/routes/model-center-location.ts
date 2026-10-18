const modelCenterParameters = new Set(['tab', 'targetId', 'modelId'])

export function isCurrentModelCenterQuery(search: URLSearchParams) {
  const tab = search.get('tab')
  return [...search.keys()].every(key => modelCenterParameters.has(key))
    && (tab === null || tab === 'models' || tab === 'workbench')
}
