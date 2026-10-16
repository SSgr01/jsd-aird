import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { XYColumnPicker } from './XYColumnPicker'
import type { HistoryDataset } from '@/services/ai-rnd/optimization-api'

const dataset: HistoryDataset = { id: 'data', name: '配方', rowCount: 8,
  columns: [{ code: 'temperature', name: '环境温度', kind: 'NUMERIC', unit: '℃', filledCount: 8 },
    { code: 'formula:zero', materialId: 'zero', name: '固定助剂比例', kind: 'NUMERIC', unit: '%', filledCount: 8 },
    { code: 'appearance', name: '涂层外观', kind: 'CATEGORICAL', filledCount: 8 }],
  rows: Array.from({ length: 8 }, (_, i) => ({ id: String(i), versionId: '', label: `配方 ${i}`, title: '', values: { temperature: 20+i, 'formula:zero': 0, appearance: i%2 ? '透明' : '浑浊' } })),
}
describe('shared X/Y cards', () => {
  it('keeps selected fields when searching and makes a chosen Y leave X', () => {
    const onXChange=vi.fn(), onYChange=vi.fn()
    render(<XYColumnPicker dataset={dataset} mode="modeling" xColumns={['temperature']} onXChange={onXChange} onYChange={onYChange} />)
    expect(screen.getAllByRole('radio').map(element => element.getAttribute('value'))).toEqual(['temperature', 'appearance', 'formula:zero'])
    fireEvent.change(screen.getByRole('textbox',{name:'搜索 X 字段'}),{target:{value:'助剂'}})
    expect(screen.queryByRole('checkbox',{name:/环境温度/})).not.toBeInTheDocument()
    expect(screen.getByText('已选 1 列')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('radio',{name:/环境温度/}))
    expect(onYChange).toHaveBeenCalledWith('temperature')
    expect(onXChange).toHaveBeenCalledWith([])
  })
  it('allows constant materials and shows categorical optimization targets with a reason', () => {
    const onXChange=vi.fn()
    render(<XYColumnPicker dataset={dataset} mode="optimization" xColumns={[]} yColumn="temperature" onXChange={onXChange} onYChange={vi.fn()} />)
    expect(screen.getAllByRole('checkbox').map(element => element.getAttribute('value'))).toEqual(['formula:zero', 'appearance', 'temperature'])
    const zero=screen.getByRole('checkbox',{name:/固定助剂比例/})
    expect(zero).toBeEnabled(); fireEvent.click(zero)
    expect(onXChange).toHaveBeenCalledWith(['formula:zero'])
    expect(screen.getByText('固定 0%')).toBeInTheDocument()
    expect(screen.getByRole('radio',{name:/涂层外观/})).toBeDisabled()
    expect(screen.getByText('当前优化需要数值结果')).toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })
  it('searches long names in a hundred-field data set without dropping selections', () => {
    const many={...dataset,rows:dataset.rows.map((row,i)=>({...row,values:Object.fromEntries(Array.from({length:100},(_,j)=>[`x${j}`,i+j]))})),columns:Array.from({length:100},(_,i)=>({...dataset.columns[0]!,code:`x${i}`,name:`树脂涂层在不同制备条件下的性能输入字段 ${i}`}))}
    render(<XYColumnPicker dataset={many} mode="modeling" xColumns={['x1']} onXChange={vi.fn()} onYChange={vi.fn()} />)
    fireEvent.change(screen.getByRole('textbox',{name:'搜索 X 字段'}),{target:{value:'字段 99'}})
    expect(screen.getAllByRole('checkbox')).toHaveLength(1)
    expect(screen.getByText('已选 1 列')).toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox',{name:'搜索 X 字段'}),{target:{value:''}})
    expect(screen.getByRole('checkbox',{name:/字段 1 数值/})).toBeChecked()
  })
})
