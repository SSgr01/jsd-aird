"""Export task-specific candidate rows from local history drafts; never train/import."""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
import tempfile

from history_sources import contained
from workflow import digest, read_json, write_json


def candidate_rows(documents, task):
    target_key = task['targetSemanticKey']
    if not target_key or not isinstance(task.get('targetUnit'), str):
        raise ValueError('训练候选任务必须明确目标语义和单位；未知单位可为空但不能隐式混用')
    required = task.get('requiredConditions', [])
    vocabulary = task.get('materialVocabulary', {})
    accepted, excluded = [], []
    for document in documents:
        source = document['source']
        for record in document['experiments']:
            conflicts = {x.get('sourceGroupKey') for x in document.get('issues', [])
                         if x['code'] == 'SOURCE_TOTAL_CONFLICT'}
            for observation in record['testResults']:
                if observation.get('semanticKey') != target_key:
                    continue
                group = observation['sourceGroupKey']
                reasons = []
                if record.get('recordRole','EXPERIMENT') != 'EXPERIMENT':
                    reasons.append('来源汇总记录不能作为新的独立实验样本')
                normalized = observation['normalized']
                if observation.get('semanticKey') in {'wear_observation_cycles','reported_damage_mark_length'}:
                    reasons.append('这是规定次数下的评价或损伤备注，不能作为耐磨寿命标量目标')
                if normalized['kind'] != 'NUMBER':
                    reasons.append('目标不是明确单值；区间/上下限/定性结果不擅自转成标量')
                if observation['unit'] != task['targetUnit']:
                    reasons.append('目标单位与任务不一致')
                if not observation['unit']:
                    reasons.append('目标单位尚未明确，不能假定不同来源同量纲')
                if group in conflicts:
                    reasons.append('该组源配方合计存在冲突')
                context = observation.get('conditions', {})
                features = {}
                for name in required:
                    field = context.get(name)
                    if field is None or field['normalized']['kind'] not in {'NUMBER','TEXT','QUALITATIVE'}:
                        reasons.append('缺少明确条件：' + name)
                    else:
                        features['condition:'+name] = {'value':field['value'],'unit':field['unit']}
                        if field['normalized']['kind'] == 'NUMBER' and not field['unit'] and name not in task.get('dimensionlessConditions',[]):
                            reasons.append('数值条件单位尚未明确：'+name)
                        if name in task.get('conditionUnits',{}) and field['unit'] != task['conditionUnits'][name]:
                            reasons.append('条件单位与任务不一致：'+name)
                for name, expected in task.get('conditionEquals', {}).items():
                    field = context.get(name)
                    if field is None or field['value'] != expected['value'] or field['unit'] != expected.get('unit',''):
                        reasons.append('条件不属于本任务：' + name)
                if task.get('testMethod') is not None and observation.get('testMethod') != task['testMethod']:
                    reasons.append('检测方法与任务不一致')
                if task.get('testStage') is not None and observation.get('testStage') != task['testStage']:
                    reasons.append('检测阶段与任务不一致；不能混用设计值和实测值')
                amounts = [x for x in record['formulaItems'] if x['sourceGroupKey'] == group]
                if task.get('includeFormulas', True):
                    if any(x['role']=='UNQUANTIFIED_RECIPE_LABEL' for x in document.get('documentFacts',[])):
                        reasons.append('文件含无用量的配方行，需确认组别及用量后才能用于配方训练')
                    if not amounts:
                        reasons.append('缺少可关联的配方')
                    for item in amounts:
                        name = item['materialName']
                        code = vocabulary.get(name)
                        if not code:
                            reasons.append('任务内物料名称尚未明确对应：' + str(name))
                            continue
                        kind = item['normalized']['kind']
                        if kind == 'NOT_USED' and task.get('notUsedAsZero'):
                            value = 0
                        elif kind == 'NUMBER':
                            value = item['value']
                        else:
                            reasons.append('配方用量未明确数值化：' + str(name))
                            continue
                        if task.get('compositionBasis') is None or item.get('compositionBasis') != task['compositionBasis']:
                            reasons.append('配方计量基准未明确或不一致')
                        if task.get('formulaUnit') is None or item['unit'] != task['formulaUnit']:
                            reasons.append('配方用量单位未明确或不一致')
                        if not item['unit']:
                            reasons.append('原配方用量单位缺失，需确认后才能作数值训练特征')
                        feature = 'material:'+str(code)+':'+item.get('stageKey','')
                        if feature in features:
                            reasons.append('同一物料多次投料缺少可区分阶段，未自动相加')
                        features[feature] = {'value':value,'unit':item['unit']}
                all_refs = observation['sourceRefs'] + [r for a in amounts for r in a['sourceRefs']]
                for identity in record['sourceGroups']:
                    if identity['sourceGroupKey'] == group:
                        all_refs += identity['sourceRefs'] + [r for v in identity.get('properties',{}).values() for r in v['sourceRefs']]
                if any(r.get('evidenceType') == 'IMAGE_TRANSCRIPTION' for r in all_refs):
                    reasons.append('包含尚需对照原图核对的图片转录')
                if not features:
                    reasons.append('没有明确的输入特征，不能形成训练样本')
                row = {'sourceHash':source['source_sha256'], 'sourcePath':source['source'],
                    'recordKey':record['recordKey'],'sourceGroupKey':group,
                    'splitGroupKey':source['source_sha256']+':'+record['recordKey'],
                    'features':features,'target':observation['value'],'targetUnit':observation['unit'],
                    'sourceRefs':all_refs,'approval':'NOT_GRANTED'}
                if reasons:
                    excluded.append({**row,'reasons':sorted(set(reasons))})
                else:
                    accepted.append(row)
    units = {}
    for row in accepted:
        for name, feature in row['features'].items():
            units.setdefault(name,set()).add(feature['unit'])
    mixed = {name for name, values in units.items() if len(values)>1}
    if mixed:
        retained=[]
        for row in accepted:
            affected=mixed.intersection(row['features'])
            if affected:
                excluded.append({**row,'reasons':['同一输入特征的来源单位混用，未自动换算：'+','.join(sorted(affected))]})
            else:retained.append(row)
        accepted=retained
    return {'candidateRows':accepted,'excludedRows':excluded,'approval':'NOT_GRANTED',
        'scope':'仅按任务口径筛选的候选表；需人工逐份核对并经现有实验审核流程，不能直接训练、注册或发布模型'}


def export_candidates(root, task_path, inputs, output):
    root = Path(root).resolve()
    destination = contained(root, output)
    if destination.exists():
        raise ValueError('训练候选输出已存在；不覆盖既有候选集')
    documents = []
    for path in inputs:
        path = contained(root, path)
        verification = path.parent/'verification.json'
        report = read_json(verification)
        if report.get('artifacts',{}).get(path.name) != digest(path):
            raise ValueError('历史数据与提取核验指纹不符')
        document = read_json(path)
        if digest(contained(root, document['source']['source'])) != document['source']['source_sha256']:
            raise ValueError('来源已变化，不能继续使用旧提取结果')
        original = document['source'].get('original')
        if original and digest(contained(root,original['path'])) != original['sha256']:
            raise ValueError('原始历史资料已变化')
        documents.append(document)
    task = read_json(task_path)
    result = candidate_rows(documents, task)
    destination.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.candidates-',dir=destination.parent) as temporary:
        stage = Path(temporary)
        write_json(stage/'candidates.json',result)
        write_json(stage/'task.json',task)
        with (stage/'candidates.csv').open('w',encoding='utf-8-sig',newline='') as handle:
            feature_keys=sorted({key for row in result['candidateRows'] for key in row['features']})
            keys=['sourcePath','recordKey','sourceGroupKey','splitGroupKey',
                  *[key for feature in feature_keys for key in [feature,feature+':unit']],
                  'target','targetUnit','sourceRefs','approval']
            writer=csv.DictWriter(handle,fieldnames=keys);writer.writeheader()
            for row in result['candidateRows']:
                output_row={key:json.dumps(row[key],ensure_ascii=False) if isinstance(row[key],(dict,list)) else row[key]
                            for key in ['sourcePath','recordKey','sourceGroupKey','splitGroupKey','target','targetUnit','sourceRefs','approval']}
                for key, feature in row['features'].items():
                    output_row[key]=feature['value'];output_row[key+':unit']=feature['unit']
                writer.writerow(output_row)
        stage.rename(destination)
    return {'output':destination.relative_to(root).as_posix(),'candidates':len(result['candidateRows']),
            'excluded':len(result['excludedRows']),'approval':'NOT_GRANTED'}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',required=True);parser.add_argument('--task',required=True)
    parser.add_argument('--input',action='append',required=True);parser.add_argument('--output',required=True)
    args=parser.parse_args()
    try:
        print(json.dumps(export_candidates(args.root,args.task,args.input,args.output),ensure_ascii=False));return 0
    except Exception as error:
        print(json.dumps({'status':'BLOCKED','reason':str(error)},ensure_ascii=False));return 2


if __name__=='__main__':
    raise SystemExit(main())
