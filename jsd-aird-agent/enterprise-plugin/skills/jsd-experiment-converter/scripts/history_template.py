"""Project verified local history drafts into compatible supplied templates.

No fact values are accepted from the request. Re-read the source and recompile
the original mapping before deciding whether a complete projection is possible.
"""
from __future__ import annotations

import argparse
from datetime import datetime
import json
import math
from pathlib import Path
import re
import tempfile

from history_data import Compiler, NUMBER, compile_document_facts
from history_sources import HistorySource, contained
from workflow import digest, read_json, write_json


class NotSuitable(ValueError):
    pass


# Deliberately small semantic registry. Unknown items remain historical facts;
# do not select a neighbouring template row using substring similarity.
TEST_FIELDS = {
    'ph': ('pH', {''}, ('pH','PH')),
    'resin_viscosity_verbatim': ('黏度\ncP（mPa·s）/ KU / s', {''}, ('粘度','黏度')),
    'particle_size_verbatim': ('粒径（nm/μm）\nD10 / D50 / D90 / 平均', {''}, ('粒径',)),
    'particle_size': ('粒径（nm/μm）\nD10 / D50 / D90 / 平均', {'nm','μm'}, ('粒径','粒径（nm）','粒径（μm）')),
    'resin_viscosity': ('黏度\ncP（mPa·s）/ KU / s', {'cps','cP','mPa·s','KU','s'}, ('粘度','黏度','树脂粘度','树脂黏度')),
    'water_contact_angle': ('水接触角（°）', {'°'}, ('水接触角','水接触角（°）','水接触角(°)','水滴角/°')),
    'hexadecane_contact_angle': ('正十六烷接触角（°）', {'°'}, ('正十六烷接触角','正十六烷接触角（°）')),
    'diiodomethane_contact_angle': ('二碘甲烷接触角（°）', {'°'}, ('二碘甲烷接触角','二碘甲烷接触角（°）')),
    'static_friction_coefficient': ('静摩擦系数', {''}, ('静摩擦系数',)),
    'dynamic_friction_coefficient': ('动摩擦系数', {''}, ('动摩擦系数',)),
    'measured_solids': ('固含（%）', {'%'}, ('固含','固含（%）','实测固含','测量固含')),
    'resin_appearance': ('外观', {''}, ('外观','树脂外观')),
    'coating_appearance': ('涂料外观', {''}, ('涂料外观',)),
    'film_appearance': ('板面外观', {''}, ('板面外观','漆膜外观','喷板')),
    'quv_delta_e': ('QUV后：ΔE', {''}, ('QUV/20次△E',)),
}
SYNTHESIS_FIELDS = {
    'measured_solids': ('固含（%）', {'%'}, ('固含','固含（%）','实测固含','测量固含')),
    'refractive_index': ('折光率', {''}, ('折光率','折射率')),
}
METADATA = {'title':'B1','experimentName':'B1','operator':'B2','date':'D2',
            'project':'B3','projectNumber':'B4','background':'B5','purpose':'B6'}


def norm(value):
    return ''.join(str(value).split())


def display(field, target_format='General'):
    value = field['value']
    kind = field['normalized']['kind']
    if kind in {'INVALID','MISSING'}:
        raise NotSuitable('字段不是可写入的有效来源值')
    if kind == 'NOT_USED':
        return '/'
    if kind in {'INTERVAL','BOUND'}:
        if not isinstance(field['rawValue'],str):
            raise NotSuitable('区间/上下限缺少可保留的原始文本')
        return field['rawValue']
    if isinstance(value,dict):
        if set(value)=={'type','iso'} and value['type'] in {'datetime','date','time'}:
            return datetime.fromisoformat(value['iso']) if value['type'] in {'datetime','date'} else value['iso']
        raise NotSuitable('当前模板不能承载复合值')
    if kind == 'NUMBER' and field['unit']=='%' and '%' in target_format:
        return value / 100
    return value


def verify_input(root, history):
    history = contained(root, history)
    directory = history.parent
    report = read_json(directory/'verification.json')
    for name in ('history.json','mapping.json'):
        if report.get('artifacts',{}).get(name) != digest(directory/name):
            raise ValueError('历史数据或映射指纹不匹配')
    data, plan = read_json(history), read_json(directory/'mapping.json')
    if report.get('planSha256') != digest(directory/'mapping.json'):
        raise ValueError('提取映射与核验报告不匹配')
    source = HistorySource(root,plan['source'],plan['source_sha256'])
    try:
        if data['source']['source_sha256'] != source.sha:
            raise ValueError('历史数据绑定的工作副本不一致')
        if data['source']['source'] != plan['source'] or data['source'].get('original') != plan.get('original'):
            raise ValueError('历史数据与原映射的来源位置不一致')
        original = plan.get('original')
        if original and digest(contained(root,original['path'])) != original['sha256']:
            raise ValueError('历史原件指纹已变')
        compiler = Compiler(source)
        fresh = [compiler.compile_record(record) for record in plan['records']]
        facts = compile_document_facts(compiler, plan)
        if fresh != data['experiments'] or facts != data.get('documentFacts',[]) or compiler.issues != data['issues']:
            raise ValueError('当前来源重提取与历史事实不一致，需重新提取；不能靠抄写JSON绕过核对')
        for item in plan.get('structural',[]):
            value,_ = source.read(item['ref'])
            if not item.get('reason') or not isinstance(value,str) or re.fullmatch(NUMBER,value.strip()):
                raise ValueError('原映射结构标签声明无效')
        explicit_unplaced = bool(plan.get('unplaced'))
        for item in plan.get('unplaced',[]):source.read(item['ref'])
        remaining = len(source.cells.keys()-source.used)
        reasons=[]
        if any(r.get('recordRole')=='SUMMARY' for r in data['experiments']):
            reasons.append('含来源汇总记录，不能作为新增独立实验填入标准模板')
        if any(x['role'] in {'SOURCE_NOTE','UNQUANTIFIED_RECIPE_LABEL'} for x in facts):
            reasons.append('有文件级原文说明，当前模板未声明其完整对应位置')
        if data['templateAssessment']['decision'] != 'POSSIBLE':
            reasons.append(data['templateAssessment']['reason'])
        if compiler.issues:reasons.append('提取数据包含类型问题或源合计冲突')
        if remaining or explicit_unplaced or data['unplaced']:
            reasons.append('仍有未落位来源，不能声称标准模板完整表达了实验')
        if source.images:reasons.append('存在原图或图片转录，须先完成人工对照，当前不自动生成标准稿')
        return history, data, report, plan, reasons
    finally:
        source.close()


def build_sheet(workbook, blank, record, kind, sheet_name):
    from openpyxl.utils import get_column_letter
    from template_ops import pristine_sheet, expand_recipe, expand_groups, find_label, total_formula_and_value, fit_written_text
    from execute_plan import check_inline_lists
    ws=pristine_sheet(workbook,blank,sheet_name)
    groups=record['sourceGroups']
    if not groups or any(not g['sourceIdentity'] for g in groups):
        raise NotSuitable('组别没有可显示的原始标识，不能补造实验编号')
    by_group={g['sourceGroupKey']:g for g in groups}
    if any(item['sourceGroupKey'] not in by_group for domain in ('formulaItems','processSteps','testResults') for item in record[domain]):
        raise NotSuitable('存在未关联到明确组别的事实')
    materials=[]
    recipe={g:[] for g in by_group}
    for item in record['formulaItems']:
        group=item['sourceGroupKey']
        if item['materialName'] in [v['materialName'] for v in recipe[group]]:
            raise NotSuitable('同组存在重复投料；固定配方行不能保留其顺序，不能自动相加')
        if item['unit']!='g' or item['normalized']['kind'] not in {'NUMBER','NOT_USED'}:
            raise NotSuitable('标准模板配方以g计量，原用量单位/数值不足以直接对应')
        if item['normalized']['kind']=='NUMBER' and item['value']<0:
            raise NotSuitable('负用量需要保留原始业务含义，不能按绝对投料量写入')
        recipe[group].append(item)
        if item['materialName'] not in materials:materials.append(item['materialName'])
    info,_=expand_recipe(ws,len(materials),kind);expand_groups(ws,info,len(groups))
    if any(c.data_type=='f' and c.row!=info['total'] for row in ws for c in row):
        raise NotSuitable('模板包含配方合计以外的公式，不能声称已经正确重算')
    protected={c.coordinate:c.value for row in ws for c in row if c.value is not None and c.data_type!='f'}
    writes={}; evidence=[]; observations=set(); caches={}
    def put(coord,value,refs=(),shared=False):
        if coord in writes:
            if shared and writes[coord]==value:return
            raise NotSuitable('多项事实对应同一目标格，不能折叠重复测量或不同条件：'+coord)
        if ws[coord].value is not None and ws[coord].data_type!='f':
            raise NotSuitable('目标包含模板标签，拒绝覆盖：'+coord)
        writes[coord]=value;ws[coord]=value
        if isinstance(value,str) and value.startswith('='):ws[coord].data_type='s'
        evidence.append({'target':coord,'value':value,'sourceRefs':list(refs)})
    def at_label(label,col,field,units=None,shared=False):
        if units is not None and field['normalized']['kind']!='NOT_USED' and field['unit'] not in units:
            raise NotSuitable('来源单位不能直接对应模板字段：'+label+' / '+str(field['unit']))
        row=find_label(ws,label,2).row;coord=get_column_letter(col)+str(row)
        put(coord,display(field,ws[coord].number_format),field['sourceRefs'],shared)
    for key,field in record['metadata'].items():
        coord=METADATA.get(key)
        if key=='conclusion':coord='B136' if kind=='test' else 'B53'
        if key=='plan' and kind=='synthesis':coord='B7'
        if coord is None:
            raise NotSuitable('当前模板没有此元数据的明确位置：'+key)
        # Recipe expansion shifts lower content; locate its label instead.
        if key=='conclusion':
            row=find_label(ws,'实验结论' if kind=='test' else '小结',1).row;coord='B'+str(row)
        put(coord,display(field,ws[coord].number_format),field['sourceRefs'])
    for index,group in enumerate(groups):
        key=group['sourceGroupKey'];col=info['first_col']+index;letter=get_column_letter(col)
        put(letter+str(info['id_row']),group['sourceIdentity'],group['sourceRefs'])
        if kind=='test' and group['sourceIdentityType']=='EXPERIMENT':
            put(letter+str(info['recipe_id_row']),group['sourceIdentity'],group['sourceRefs'])
        for name,value in group.get('properties',{}).items():
            if name=='plan' and kind=='test':at_label('具体方案',col,value)
            elif name=='date':
                put('D2',display(value,ws['D2'].number_format),value['sourceRefs'],shared=True)
            else:raise NotSuitable('样品属性尚无明确模板位置：'+name)
        amounts={v['materialName']:v for v in recipe[key]}
        for n,name in enumerate(materials):
            row=info['first']+n
            if index==0:put('B'+str(row),name,amounts[name]['sourceRefs'] if name in amounts else next(v['sourceRefs'] for v in record['formulaItems'] if v['materialName']==name))
            if name in amounts:put(letter+str(row),display(amounts[name]),amounts[name]['sourceRefs'])
        steps=[s for s in record['processSteps'] if s['sourceGroupKey']==key]
        if len(steps)>1:raise NotSuitable('多个工艺步骤不能合并进单次固化/反应参数')
        if steps:
            step=steps[0]
            if kind=='test':
                aliases={'UV光固化':'UV光固化','UV固化':'UV光固化','热固化':'热固化','EB电子束固化':'EB电子束固化'}
                if step['operation'] not in aliases or norm(step['rawValue'])!=norm(step['operation']):
                    raise NotSuitable('复合工艺原文不能由单次固化栏完整表达')
                target=find_label(ws,'固化方式',2).row
                put(letter+str(target),aliases[step['operation']],step['sourceRefs'])
                params={'temperature':('固化温度（℃）',{'℃','°C'}),'duration':('固化时间（s/min/h）',{'s','min','h'}),
                    'wavelength':('光源波长 / 波段（nm）',{'nm'}),
                    'intensity':('光强（mW/cm²）',{'mW/cm²','mW/cm2','mw/cm2'}),
                    'energy':('光能量（mJ/cm²）',{'mJ/cm²','mJ/cm2','mj/cm2'}),
                    'device':('固化设备 / 光源',None)}
            else:
                at_label('工艺简介',col,{'value':step['rawValue'],'rawValue':step['rawValue'],
                    'normalized':{'kind':'TEXT'},'unit':'','sourceRefs':step['sourceRefs']})
                params={'materialTemperature':('反应温度（℃）\n油温 / 料温',{'℃','°C'}),
                        'oilTemperature':('反应温度（℃）\n油温 / 料温',{'℃','°C'}),
                        'duration':('反应时间（s/min/h）',{'s','min','h'})}
            for name,value in step.get('parameters',{}).items():
                if name not in params:raise NotSuitable('工艺参数未适配：'+name)
                label,units=params[name]
                if name=='duration':
                    if value['unit'] not in units or value['normalized']['kind']!='NUMBER':raise NotSuitable('反应/固化时间单位未明确')
                    value={**value,'value':str(value['value'])+value['unit'],'normalized':{'kind':'TEXT'}}
                if kind=='synthesis' and name in {'materialTemperature','oilTemperature'}:
                    role='料温' if name=='materialTemperature' else '油温'
                    if not any(role in str(r['rawValue']) for r in value['sourceRefs']):
                        raise NotSuitable('反应温度类型必须有原文证据，不能猜油温或料温')
                    value={**value,'value':role+str(value['value'])+value['unit'],'normalized':{'kind':'TEXT'}}
                at_label(label,col,value,units)
        for observation in [v for v in record['testResults'] if v['sourceGroupKey']==key]:
            rule=(TEST_FIELDS if kind=='test' else SYNTHESIS_FIELDS).get(observation['semanticKey'])
            if rule is None:raise NotSuitable('检测项目尚无安全的模板对应：'+observation['semanticKey'])
            label,units,aliases=rule
            if observation['semanticKey'].endswith('_verbatim') and observation['normalized']['kind'] not in {'TEXT','QUALITATIVE'}:
                raise NotSuitable('原文保留字段仅接受文字，不推定数字的单位或方法')
            if norm(observation['testItem']) not in {norm(v) for v in aliases}:
                raise NotSuitable('原始检测标签与语义字段不能明确对应：'+str(observation['testItem']))
            if observation.get('testMethod') or observation.get('testStage'):
                raise NotSuitable('逐项方法/阶段没有明确模板位置，不能丢掉检测口径')
            for condition,value in observation.get('conditions',{}).items():
                if kind=='test' and condition=='substrate':at_label('实验素材\n基材 / 厚度（mm/μm）',col,value,shared=True)
                elif kind=='test' and condition=='filmThickness':at_label('膜厚（μm）',col,value,{'μm'},shared=True)
                else:raise NotSuitable('模板无法完整表达逐项条件：'+condition)
            destination=(key,label)
            if destination in observations:raise NotSuitable('同一组同一项目有多项观测，不能折叠平行样或时间点')
            if observation['semanticKey']=='quv_delta_e':
                # The original item itself specifies the cycle count. Keep it
                # with the raw interval/placeholder, never reduce to a midpoint.
                observation={**observation,'value':observation['testItem']+'：'+str(observation['rawValue']),
                             'normalized':{'kind':'TEXT'}}
            observations.add(destination);at_label(label,col,observation,units)
    # Empty formulation stays empty. For actual g formulations, reuse the
    # existing checked SUM/cache implementation and preserve source totals.
    for col in range(info['first_col'],info['output_last_col']+1):
        coordinate=get_column_letter(col)+str(info['total'])
        if materials:
            formula,value=total_formula_and_value(ws,col,info['first'],info['last'])
            ws[coordinate]=formula;writes[coordinate]=formula;caches[coordinate]=value
        else:ws[coordinate]=None;writes[coordinate]=None
    for total in record.get('sourceTotals',[]):
        if total['comparison']!='MATCH' or total['unit']!='g':raise NotSuitable('源配方合计未按g计量核对一致')
    fit_written_text(ws,writes)
    check_inline_lists(ws)
    return {'sheet':sheet_name,'recordKey':record['recordKey'],'writes':writes,
            'protected':protected,'caches':caches,'evidence':evidence,'groups':len(groups)}


def project(root, history, output=None, allow_partial=False):
    root=Path(root).resolve()
    history,data,source_report,plan,reasons=verify_input(root,history)
    types={r['experimentType'] for r in data['experiments']}
    kind='test' if types=={'TEST'} else 'synthesis' if types=={'SYNTHESIS'} else None
    if kind is None:reasons.append('缺少统一明确的实验类型；混合类型不硬套一个模板')
    category='测试实验' if kind=='test' else '合成实验' if kind=='synthesis' else '待判断'
    source_name=Path(data['source']['source']).stem
    target=contained(root,output or ('转换结果/'+category+'/'+source_name+'-数据投影.xlsx'))
    allowed=[root/'转换结果'/category,root/'_work']
    if target.suffix.lower()!='.xlsx' or not any(target.is_relative_to(p.resolve()) for p in allowed):
        raise ValueError('标准Excel输出须在对应转换结果分类或_work内')
    report_path=target.with_suffix('.projection.json')
    template=root/'标准模板'/((kind or 'test')+'.xlsx')
    if not template.is_file():reasons.append('当前工作区没有对应空白标准模板')
    fingerprint={'historySha256':digest(history),'mappingSha256':digest(history.parent/'mapping.json'),
        'templateSha256':digest(template) if template.is_file() else None,
        'engineSha256':digest(Path(__file__))+digest(Path(__file__).with_name('template_ops.py')),
        'allowPartial':bool(allow_partial)}
    if report_path.exists():
        old=read_json(report_path)
        if (all(old.get(k)==v for k,v in fingerprint.items()) and
            ((old['status']=='SKIPPED' and not target.exists()) or
             (old['status'] in ('PROJECTED','PARTIAL') and target.is_file() and digest(target)==old['outputSha256']))):
            return {'cache_hit':True,**old}
        raise ValueError('投影结果或输入指纹已变；不覆盖旧文件，请指定新的输出位置')
    if target.exists():raise ValueError('目标文件已存在，不能覆盖')
    report={**fingerprint,'history':history.relative_to(root).as_posix(),
        'output':target.relative_to(root).as_posix(),'status':'SKIPPED',
        'reasons':reasons,'approval':'NOT_GRANTED'}
    from openpyxl import load_workbook
    from template_ops import cache_totals
    workbook=None
    try:
        # Also explain actual field incompatibilities when a previous semantic
        # assessment already requested skipping. This dry run never emits XLSX.
        if kind and template.is_file():
            workbook=load_workbook(template)
            if len(workbook.worksheets)!=1:raise ValueError('空模板必须仅有一张底稿')
            blank=workbook.active;blank.title='_pristine'
            details=[]
            build_ok=True
            try:
                for n,record in enumerate(data['experiments'],1):
                    name=category if n==1 else category+'-'+str(n)
                    details.append(build_sheet(workbook,blank,record,kind,name))
            except NotSuitable as error:
                report['reasons'].append(str(error))
                build_ok=False
            # Partial projection is explicit: every omitted fact remains in the
            # verified historical facts and report. Never emit a blank template,
            # a summary as a new experiment, or a failed/half-written sheet.
            partial_allowed=(allow_partial and build_ok and bool(details)
                and all(r.get('recordRole')!='SUMMARY' and any(r[d] for d in ('formulaItems','processSteps','testResults')) for r in data['experiments']))
            if build_ok and (not report['reasons'] or partial_allowed):
                workbook.remove(blank)
                target.parent.mkdir(parents=True,exist_ok=True)
                with tempfile.TemporaryDirectory(prefix='.projection-',dir=target.parent) as temporary:
                    candidate=Path(temporary)/'candidate.xlsx';workbook.save(candidate)
                    cache_totals(candidate,{f'xl/worksheets/sheet{n}.xml':d['caches'] for n,d in enumerate(details,1)})
                    saved=load_workbook(candidate,data_only=False);values=load_workbook(candidate,data_only=True)
                    try:
                        from openpyxl.worksheet.print_settings import PrintArea
                        from openpyxl.worksheet.cell_range import CellRange
                        from execute_plan import check_inline_lists
                        for detail in details:
                            sheet=saved[detail['sheet']]
                            from execute_plan import same
                            for coord,value in detail['writes'].items():
                                if not same(sheet[coord].value,value):raise ValueError('保存后数据核对失败：'+coord)
                            for coord,value in detail['protected'].items():
                                if sheet[coord].value!=value:raise ValueError('模板标签被改变：'+coord)
                            for coord,value in detail['caches'].items():
                                actual=values[detail['sheet']][coord].value
                                if value is None:
                                    if actual is not None:raise ValueError('空合计缓存被错误填值')
                                elif not isinstance(actual,(int,float)) or not math.isclose(actual,value,abs_tol=1e-8):
                                    raise ValueError('保存后合计缓存核对失败：'+coord)
                            areas=list(PrintArea.from_string(str(sheet.print_area)).ranges)
                            if not areas or any(not any(coord in area for area in areas) for coord in detail['writes']):
                                raise ValueError('打印范围没有覆盖输出数据')
                            if any(not any(CellRange(str(r)).issubset(a) for a in areas) for r in sheet.merged_cells.ranges):
                                raise ValueError('打印范围截断模板合并区域')
                            check_inline_lists(sheet)
                    finally:saved.close();values.close()
                    if digest(contained(root,plan['source']))!=plan['source_sha256'] or digest(template)!=fingerprint['templateSha256']:
                        raise ValueError('投影执行过程中来源或模板已变化')
                    original=plan.get('original')
                    if original and digest(contained(root,original['path']))!=original['sha256']:
                        raise ValueError('投影执行过程中历史原件已变化')
                    candidate.rename(target)
                report.update(status='PARTIAL' if report['reasons'] else 'PROJECTED',outputSha256=digest(target),sheets=len(details),
                    groups=sum(d['groups'] for d in details),
                    placements=[{'sheet':d['sheet'],'recordKey':d['recordKey'],'fields':d['evidence']} for d in details])
        report['limitations']='本地模板投影草稿；重新读取了原件工作副本，写入和样式检查不代表人工语义审核；未入库或训练'
        report_path.parent.mkdir(parents=True,exist_ok=True);write_json(report_path,report)
        return {'cache_hit':False,**report}
    finally:
        if workbook is not None:workbook.close()


def project_batch(root, manifest_path):
    root=Path(root).resolve();results=[]
    for item in read_json(manifest_path)['items']:
        try:
            result=project(root,item['history'],item.get('output'))
            results.append({k:v for k,v in result.items() if k!='placements'})
        except Exception as error:
            results.append({'history':item.get('history'),'status':'BLOCKED','reason':str(error)})
    return {'items':results,'counts':{s:sum(x['status']==s for x in results)
                                     for s in ('PROJECTED','SKIPPED','BLOCKED')}}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',required=True)
    inputs=parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument('--history');inputs.add_argument('--manifest')
    parser.add_argument('--output')
    parser.add_argument('--allow-partial',action='store_true',help='输出已明确对应的事实，保留未落位原因并标记PARTIAL；不放宽字段、单位或条件核验')
    args=parser.parse_args()
    try:
        if args.manifest:
            result=project_batch(args.root,args.manifest)
            if args.output:write_json(contained(args.root,args.output),result)
        else:result=project(args.root,args.history,args.output,args.allow_partial)
        print(json.dumps({k:v for k,v in result.items() if k!='placements'},ensure_ascii=False));return 0
    except Exception as error:
        print(json.dumps({'status':'BLOCKED','reason':str(error)},ensure_ascii=False));return 2


if __name__=='__main__':raise SystemExit(main())
