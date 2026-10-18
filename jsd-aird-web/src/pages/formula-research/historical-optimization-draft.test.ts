/* eslint-disable @typescript-eslint/no-unsafe-member-access */
import { describe, expect, it } from 'vitest'
import { historicalCandidateDraft } from './historical-optimization-draft'
import type { OptimizationRun, OptimizationCandidate } from '@/services/ai-rnd/optimization-api'

const ref={sheetId:'sheet',sheetName:'配方',row:2,column:2,numberFormat:'0.00%'}
const snapshot={sheets:{sheet:{cellData:{'1':{'1':{v:0.4},'2':{v:81}}}}}}
const run={id:'run',frozenBaseline:{templateSnapshot:snapshot},frozenRequest:{target:'y',targetName:'光泽',features:[{code:'formula:a'}],
  datasetSnapshot:{id:'data',rows:[{id:'sample',values:{'formula:a':40,y:81},sourceRefs:{'formula:a':[ref],y:[{...ref,column:3,numberFormat:'General'}]}}],
    columns:[{code:'formula:a',name:'树脂比例',materialId:'a',compositionBasis:'MASS_PERCENT',unit:'%'},{code:'y',name:'光泽',unit:'GU'}]}}} as unknown as OptimizationRun
const candidate={id:'candidate',title:'方案 1',inputs:{'formula:a':45},results:{targets:[{result:{value:88}}]}} as unknown as OptimizationCandidate
describe('optimization draft source mapping',()=>{
  it('fills inputs in the original workbook without turning predictions into measurements',()=>{
    const draft=historicalCandidateDraft(run,candidate)
    expect(draft.editModel.documentSnapshot.sheets.sheet.cellData['1']['1'].v).toBe(0.45)
    expect(draft.editModel.documentSnapshot.sheets.sheet.cellData['1']['2'].v).toBeUndefined()
    expect(draft.editModel.testResults[0].value).toBeNull()
    expect(draft.editModel.formulaItems[0].ratio).toBe(45)
    expect(snapshot.sheets.sheet.cellData['1']['1'].v).toBe(0.4)
    expect(snapshot.sheets.sheet.cellData['1']['2'].v).toBe(81)
  })
  it('uses the saved measured value when one exists',()=>{
    const draft=historicalCandidateDraft(run,candidate,84)
    expect(draft.editModel.documentSnapshot.sheets.sheet.cellData['1']['2'].v).toBe(84)
    expect(draft.editModel.testResults[0].value).toBe(84)
  })
  it('does not create an empty workbook when an input has no source coordinates',()=>{
    const broken=structuredClone(run)
    broken.frozenRequest.datasetSnapshot.rows[0].sourceRefs['formula:a']=[]
    expect(()=>historicalCandidateDraft(broken,candidate)).toThrow('缺少原表位置')
  })
  it('writes native table inputs to an editable table and leaves measured Y blank',()=>{
    const native=structuredClone(run)
    native.frozenRequest.datasetSnapshot.rows[0].sourceRefs={}
    const draft=historicalCandidateDraft(native,candidate)
    expect(draft.editModel.documentSnapshot.sheets['sheet-1'].cellData['1']['0'].v).toBe(45)
    expect(draft.editModel.documentSnapshot.sheets['sheet-1'].cellData['1']['1'].v).toBeUndefined()
    expect(draft.templateVersionId).toBeUndefined()
    expect(draft.editModel.testResults[0].value).toBeNull()
  })
})
