export interface SearchMaterial {
  materialId: string
  fixed?: boolean
  ratio?: number
  minimum?: number
  maximum?: number
}

/** Internal feasible starting point; proportions of variable materials are searched by AI. */
export function feasibleFormulaSeed(materials: SearchMaterial[]) {
  if (!materials.length) throw new Error('请先选择模型支持的材料')
  if (new Set(materials.map((item) => item.materialId)).size !== materials.length) throw new Error('配方材料不能重复')
  if (materials.every((item) => item.fixed)) throw new Error('请至少选择一种由AI预测比例的材料')
  const seed = materials.map((item) => ({
    materialId: item.materialId,
    minimum: item.fixed ? item.ratio : item.minimum ?? 0,
    maximum: item.fixed ? item.ratio : item.maximum ?? 100,
    ratio: item.fixed ? item.ratio ?? 0 : item.minimum ?? 0,
  }))
  for (const item of seed) {
    if (item.minimum == null || item.maximum == null || !Number.isFinite(item.minimum) || !Number.isFinite(item.maximum)
      || item.minimum < 0 || item.maximum > 100 || item.minimum > item.maximum) {
      throw new Error('固定材料请填写比例；待预测材料的范围需在0～100%内，最小值不能大于最大值')
    }
  }
  let remaining = 100 - seed.reduce((sum, item) => sum + item.ratio, 0)
  if (remaining < -1e-6 || seed.reduce((sum, item) => sum + item.maximum!, 0) < 100 - 1e-6) {
    throw new Error('这些材料范围无法组成100%的配方，请调整固定比例或最小、最大比例')
  }
  for (let pass = 0; pass < seed.length && remaining > 1e-6; pass++) {
    const adjustable = seed.filter((item) => item.maximum! - item.ratio > 1e-6)
    const share = remaining / adjustable.length
    for (const item of adjustable) {
      const added = Math.min(share, item.maximum! - item.ratio)
      item.ratio += added
      remaining -= added
    }
  }
  return seed.map((item) => ({ materialId: item.materialId, ratio: item.ratio, unit: 'PERCENT', amountKnown: true }))
}
