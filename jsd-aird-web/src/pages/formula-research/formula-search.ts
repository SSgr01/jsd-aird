export interface SearchMaterial {
  materialId: string
  fixed?: boolean
  ratio?: number
  minimum?: number
  maximum?: number
}

/** A starting dose in grams for each material; no fixed overall total. */
export function feasibleFormulaSeed(materials: SearchMaterial[]) {
  if (!materials.length) throw new Error('请先选择模型支持的材料')
  if (new Set(materials.map(item => item.materialId)).size !== materials.length) throw new Error('配方材料不能重复')
  if (materials.every(item => item.fixed)) throw new Error('请至少选择一种可调整用量的材料')
  return materials.map(item => {
    const minimum = item.fixed ? item.ratio : item.minimum
    const maximum = item.fixed ? item.ratio : item.maximum
    if (minimum == null || maximum == null || !Number.isFinite(minimum) || !Number.isFinite(maximum) || minimum < 0 || minimum > maximum)
      throw new Error('请填写有效的材料用量及范围（g）')
    return { materialId: item.materialId, ratio: (minimum + maximum) / 2, unit: 'g', amountKnown: true }
  })
}
