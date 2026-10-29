/* eslint-disable @typescript-eslint/no-unsafe-member-access, @typescript-eslint/no-unsafe-assignment */
import { describe, expect, it } from 'vitest'
import { historicalCandidateDraft, historicalCandidatesDraft } from './historical-optimization-draft'
import type { HistoryDataset, OptimizationRun, OptimizationCandidate } from '@/services/ai-rnd/optimization-api'

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
  it('reads the actual workbook format when source coordinates omit it, and respects literal percent signs',()=>{
    const formatted=structuredClone(run)
    formatted.frozenRequest.datasetSnapshot.rows[0].sourceRefs['formula:a']=[{sheetId:'sheet',cellRange:'B2'}]
    formatted.frozenBaseline!.templateSnapshot.styles={pct:{n:{pattern:'0.00%'}},literal:{n:{pattern:'0.00"%"'}}}
    formatted.frozenBaseline!.templateSnapshot.sheets.sheet.cellData['1']['1'].s='pct'
    expect(historicalCandidateDraft(formatted,candidate).editModel.documentSnapshot.sheets.sheet.cellData['1']['1'].v).toBe(0.45)
    formatted.frozenBaseline!.templateSnapshot.sheets.sheet.cellData['1']['1'].s='literal'
    expect(historicalCandidateDraft(formatted,candidate).editModel.documentSnapshot.sheets.sheet.cellData['1']['1'].v).toBe(45)
  })
  it('clears lab values that are not present in the training projection',()=>{
    const lab=structuredClone(run)
    const cells=lab.frozenBaseline!.templateSnapshot.sheets.sheet.cellData
    cells['2']={'1':{v:'旧外观',custom:{lab:{role:'VALUE',fieldCode:'appearance'}}},'2':{v:'配方',custom:{lab:{role:'LABEL'}}}}
    const draft=historicalCandidateDraft(lab,candidate)
    expect(draft.editModel.documentSnapshot.sheets.sheet.cellData['2']['1'].v).toBeUndefined()
    expect(draft.editModel.documentSnapshot.sheets.sheet.cellData['2']['2'].v).toBe('配方')
  })
  it('writes several selected proposals into distinct groups of one workbook',()=>{
    const multi=structuredClone(run)
    multi.frozenBaseline!.templateSnapshot={sheets:{sheet:{columnData:{'1':{custom:{lab:{sampleKey:'old1'}}},'2':{custom:{lab:{sampleKey:'old2'}}}},cellData:{'0':{'1':{},'2':{}},'1':{'1':{},'2':{}},'2':{'1':{},'2':{}}}}}}
    const source=multi.frozenRequest.datasetSnapshot as HistoryDataset
    source.columns.push({code:'label',name:'样品/方案编号',kind:'CATEGORICAL',filledCount:2})
    source.rows=[2,3].map((column,index)=>({id:`row${index}`,versionId:'version',label:`记录 ${index}`,title:'实验',sourceContextKey:'experiment',values:{'formula:a':40,y:81},sourceRefs:{
      'formula:a':[{sheetId:'sheet',row:2,column}],y:[{sheetId:'sheet',row:3,column}],label:[{sheetId:'sheet',row:1,column}],
    }}))
    const other={...candidate,id:'other',title:'方案 2',inputs:{'formula:a':65}}
    const draft=historicalCandidatesDraft(multi,[candidate,other])
    const cells=draft.editModel.documentSnapshot.sheets.sheet.cellData
    expect([cells['1']['1'].v,cells['1']['2'].v]).toEqual([45,65])
    expect([cells['0']['1'].v,cells['0']['2'].v]).toEqual(['方案 1','方案 2'])
    expect([cells['2']['1'].v,cells['2']['2'].v]).toEqual([undefined,undefined])
    expect(draft.editModel.sampleGroups).toHaveLength(2)
    expect(draft.editModel.aiResearch.candidates).toHaveLength(2)
    expect(draft.editModel.documentSnapshot.sheets.sheet.columnData['1'].custom.lab.sampleKey).toBe(draft.editModel.aiResearch.candidates[0].sampleKey)
    expect(draft.editModel.documentSnapshot.sheets.sheet.columnData['2'].custom.lab.sampleKey).toBe(draft.editModel.aiResearch.candidates[1].sampleKey)
    source.rows=source.rows.slice(0,1)
    const withEmptySlot=historicalCandidatesDraft(multi,[candidate,other])
    expect([withEmptySlot.editModel.documentSnapshot.sheets.sheet.cellData['1']['1'].v,withEmptySlot.editModel.documentSnapshot.sheets.sheet.cellData['1']['2'].v]).toEqual([45,65])
  })
  it('writes a derived ratio back to its original gram cell using the complete formula total',()=>{
    const unified=structuredClone(run)
    const source=unified.frozenRequest.datasetSnapshot as HistoryDataset
    source.columns[0]!.semanticType='MATERIAL_RATIO'
    source.columns.push({code:'formula:a|g|mass_amount',name:'树脂用量',kind:'NUMERIC',filledCount:1,materialId:'a',semanticType:'MATERIAL_AMOUNT',compositionBasis:'MASS_AMOUNT',unit:'g'})
    source.rows[0]!.values['formula:a|g|mass_amount']=200
    source.rows[0]!.sourceRefs!['formula:a']=[{sheetId:'sheet',cellRange:'B2'}]
    source.rows[0]!.sourceRefs!.y=[{sheetId:'sheet',cellRange:'C2'}]
    const draft=historicalCandidateDraft(unified,candidate)
    expect(draft.editModel.documentSnapshot.sheets.sheet.cellData['1']['1'].v).toBe(90)
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
