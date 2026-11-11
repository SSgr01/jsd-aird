/* One cached RDKit instance per card group; results arrive separately and no source leaves the browser. */
importScripts('RDKit_minimal.js');
let runtime;
async function rdkitModule() {
  runtime ??= initRDKitModule({ locateFile: () => 'RDKit_minimal.wasm' }).then(rdkit => {
    rdkit.disable_logging(); return rdkit;
  });
  return runtime;
}
onmessage = async ({ data }) => {
  try {
    const rdkit = await rdkitModule();
    if (data.type === 'init') { postMessage({ type: 'ready' }); return; }
    let molecule;
    const item = data.item;
    try {
      molecule = data.reaction ? rdkit.get_rxn(item.source, JSON.stringify({ useSmiles: true })) : rdkit.get_mol(item.source);
      if (!molecule || (!data.reaction && !molecule.is_valid())) throw new Error('invalid');
      // Negative dimensions ask RDKit for a canvas based on its actual 2D coordinates, including long reactions.
      const svg = molecule.get_svg(-1, -1);
      if (new TextEncoder().encode(svg).length > data.maxSvgBytes) {
        postMessage({ type: 'result', id: data.id, item: { ...item, valid: false, error: '结构图超过输出预算，请拆分结构后重试' } }); return;
      }
      const width = Number(/\bwidth=['"]([\d.]+)/u.exec(svg)?.[1]);
      const height = Number(/\bheight=['"]([\d.]+)/u.exec(svg)?.[1]);
      postMessage({ type: 'result', id: data.id, item: {
        ...item, valid: true, canonical: data.reaction ? null : molecule.get_smiles(), svg, width, height,
      } });
    } catch {
      postMessage({ type: 'result', id: data.id, item: { ...item, valid: false, error: '结构校验未通过，请核对 SMILES 的语法和原子价态' } });
    } finally { molecule?.delete(); }
  } catch { postMessage({ type: 'error', error: '结构绘图组件加载失败，请重试' }); }
};
