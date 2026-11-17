"""Build a source-linked local review checklist; never approve or edit facts."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import csv
from datetime import date
import json
from pathlib import Path
import re
import tempfile

from history_sources import contained
from workflow import digest, read_json, write_json


def unique_refs(refs):
    result, seen = [], set()
    for ref in refs:
        key = json.dumps(ref, ensure_ascii=False, sort_keys=True)
        if key not in seen:
            seen.add(key); result.append(ref)
    return result


def review_rows(documents, requirements=None):
    """requirements maps exact semantic keys to task-required condition names.

    A missing structured field is a review gap, not proof the source omitted it.
    First inspect source evidence; only then ask its author for missing facts.
    """
    requirements = requirements or {}
    scoped_rules=requirements.get('rules')
    if 'rules' in requirements:
        if set(requirements)!={'rules'} or not isinstance(scoped_rules,list):
            raise ValueError('按任务限定的条件规则须使用rules列表，不能与全局条件混用')
        for rule in scoped_rules:
            if (not isinstance(rule,dict) or set(rule)!={'sourcePath','semanticKey','requiredConditions'}
                    or not isinstance(rule['sourcePath'],str) or not rule['sourcePath']
                    or not isinstance(rule['semanticKey'],str) or not rule['semanticKey']
                    or not isinstance(rule['requiredConditions'],list)
                    or any(not isinstance(c,str) or not c for c in rule['requiredConditions'])):
                raise ValueError('条件规则须明确来源路径、目标语义及条件名；不隐式套用全批次')
    rows = []
    dimensionless = {'pH', 'refractive_index', 'friction_coefficient', 'L_value',
                     'calculated_solids_fraction'}
    for document in documents:
        source = document['source']

        def add(code, route, question, refs, values=None, record='', group=''):
            rows.append({'sourcePath': source['source'], 'sourceHash': source['source_sha256'],
                'recordKey': record, 'sourceGroupKey': group, 'code': code,
                'route': route, 'question': question, 'currentValues': values,
                'sourceRefs': unique_refs(refs), 'status': '待处理',
                'confirmedValue': '', 'reviewer': '', 'approval': 'NOT_GRANTED'})

        for record in document['experiments']:
            key = record['recordKey']
            if record.get('recordRole') == 'SUMMARY':
                add('SOURCE_SUMMARY', '提取方核对汇总关联', '这是来源汇总，核对与原实验的对应；不得作为新独立样本计数。',
                    [ref for domain in ['formulaItems','processSteps','testResults'] for x in record[domain] for ref in x['sourceRefs']],
                    {'sourceGroups':record['sourceGroups'],'independentExperiment':False},key)
                continue
            dates=[g.get('properties',{}).get('date',record.get('metadata',{}).get('date')) for g in record['sourceGroups']]
            if 'metadata' in record and (not dates or any(not d for d in dates)):
                add('SOURCE_DATE_UNRECORDED', '作者确认日期', '原文没有可明确关联的实验日期。请补日期及适用组别；不依据目录年份或其他页猜补。',
                    [ref for g in record['sourceGroups'] for ref in g['sourceRefs']],record.get('metadata',{}),key)
            for g,d in zip(record['sourceGroups'],dates):
                if not d:continue
                raw=d['value'];raw=raw.get('iso','') if isinstance(raw,dict) else str(raw)
                try:when=date.fromisoformat(raw[:10])
                except ValueError:continue
                for ref in d['sourceRefs']:
                    sheet=ref.get('anchor','').split('!')[0]
                    weekdays={'星期一':0,'星期二':1,'星期三':2,'星期四':3,'星期五':4,'星期六':5,'星期日':6,'星期天':6}
                    if sheet in weekdays and weekdays[sheet]!=when.weekday():
                        add('SOURCE_DATE_WEEKDAY_CONFLICT','作者确认日期','页签星期与填写日期不一致，请确认正确日期及适用组别；两者均保留。',
                            d['sourceRefs'],{'sheet':sheet,'recordedDate':d['rawValue']},key,g['sourceGroupKey'])
            for group in record['sourceGroups']:
                gid = group['sourceGroupKey']
                formulas = [x for x in record['formulaItems'] if x['sourceGroupKey'] == gid]
                results = [x for x in record['testResults'] if x['sourceGroupKey'] == gid]
                processes=[x for x in record.get('processSteps',[]) if x['sourceGroupKey']==gid]
                refs = (group['sourceRefs'] + [ref for x in formulas + results + processes for ref in x['sourceRefs']]
                    + [ref for x in record.get('metadata',{}).values() for ref in x['sourceRefs']]
                    + [ref for x in group.get('properties',{}).values() for ref in x['sourceRefs']])
                add('HUMAN_SOURCE_CHECK', '逐份人工核对',
                    '对照原件确认组别、原值和条件没有串组；机器提取状态不代替人工确认。',
                    refs, {'sourceIdentity': group['sourceIdentity']}, key, gid)
                if formulas:
                    refs = [ref for x in formulas for ref in x['sourceRefs']]
                    missing_units = [x for x in formulas if x['normalized']['kind'] == 'NUMBER' and not x['unit']]
                    if missing_units:
                        add('FORMULA_UNIT_UNCONFIRMED', '先查原文，再补映射或询问作者',
                            '确认配方用量单位。先检查原文标题/注释；未记录时向作者确认，不能按模板补g。',
                            [ref for x in missing_units for ref in x['sourceRefs']],
                            [x['rawValue'] for x in missing_units], key, gid)
                    if any(x.get('compositionBasis') in {None, '', 'UNSPECIFIED'} for x in formulas):
                        add('COMPOSITION_BASIS_UNCONFIRMED', '先查原文，再补映射或询问作者',
                            '确认质量份、质量百分比、固体份或溶液用量等计量基准；不从合计猜测。',
                            refs, sorted({x.get('compositionBasis', '') for x in formulas}), key, gid)
                    unmatched = [x for x in formulas if not x.get('materialId')]
                    if unmatched:
                        add('MATERIAL_MASTER_UNMATCHED', '物料主数据核对',
                            '将物料原名逐项核对到材料主数据；名称相近或编号相似不代表同一材料。',
                            [ref for x in unmatched for ref in x['sourceRefs']],
                            sorted({str(x['materialName']) for x in unmatched}), key, gid)
                    repeated = Counter(str(x['materialName']) for x in formulas)
                    names = {name for name, count in repeated.items() if count > 1}
                    if names:
                        add('REPEATED_ADDITION_REVIEW', '投料阶段核对',
                            '确认同名物料多次投料的顺序、阶段及用量；原顺序已保留，未自动相加。',
                            [ref for x in formulas if str(x['materialName']) in names for ref in x['sourceRefs']],
                            [{'material': x['materialName'], 'value': x['rawValue'], 'stage': x['stageKey']}
                             for x in formulas if str(x['materialName']) in names], key, gid)
                gaps = defaultdict(list)
                for result in results:
                    semantic = result.get('semanticKey', '')
                    undefined_evaluation=semantic=='marker_pen_source_score'
                    if not semantic:
                        gaps['SEMANTIC_KEY_UNMAPPED'].append(result)
                    if undefined_evaluation:
                        gaps['EVALUATION_CRITERIA_UNCONFIRMED'].append(result)
                    if (result['normalized']['kind'] in {'NUMBER', 'INTERVAL', 'BOUND'}
                            and not result['unit'] and semantic not in dimensionless and not undefined_evaluation):
                        gaps['RESULT_UNIT_UNCONFIRMED'].append(result)
                    required=(requirements.get(semantic,[]) if scoped_rules is None else
                        list(dict.fromkeys(name for rule in scoped_rules
                            if rule['sourcePath']==source['source'] and rule['semanticKey']==semantic
                            for name in rule['requiredConditions'])))
                    for name in required:
                        condition = result.get('conditions', {}).get(name)
                        if not condition or condition['normalized']['kind'] in {'MISSING', 'INVALID', 'NOT_USED'}:
                            gaps['TASK_CONDITION_MISSING:' + name].append(result)
                for code, affected in gaps.items():
                    question = ('统一数据未定义该检测项的任务语义；先由提取方核对原文并补映射。'
                                if code == 'SEMANTIC_KEY_UNMAPPED' else
                                '检测值单位尚未结构化确认；先查原文及Excel格式，不能直接认定客户漏填。'
                                if code == 'RESULT_UNIT_UNCONFIRMED' else
                                '请说明记号笔数值代表评分、等级还是其他测量，以及方法、范围和好坏方向；先明确评价口径，不直接补物理单位。'
                                if code == 'EVALUATION_CRITERIA_UNCONFIRMED' else
                                '当前任务需要条件 ' + code.split(':', 1)[1] + '；先检查原文是否有记录，再确定是补映射还是补资料。')
                    add(code, '提取方先查原文', question,
                        [ref for x in affected for ref in x['sourceRefs']],
                        [{'item': x['testItem'], 'rawValue': x['rawValue']} for x in affected], key, gid)

        for issue in document.get('issues', []):
            add(issue['code'], '来源冲突或类型核对', issue.get('reason', issue['code']),
                issue.get('sourceRefs', []), issue, group=issue.get('sourceGroupKey', ''))
        for fact in document.get('documentFacts',[]):
            if fact['role']=='UNQUANTIFIED_RECIPE_LABEL':
                add('FORMULA_AMOUNT_UNRECORDED','作者确认配方用量',
                    '配方行有物料名但没有对应用量。请确认是否投料、适用组别及实际用量；空白未填0。',
                    fact['field']['sourceRefs'],fact['field']['rawValue'])
        unplaced = defaultdict(list)
        for item in document.get('unplaced', []):
            refs = item['sourceRefs']
            sheet = str(refs[0].get('anchor', 'unknown')).split('!')[0] if refs else 'unknown'
            unplaced[sheet].append(item)
        for sheet, items in unplaced.items():
            add('UNPLACED_SOURCE', '提取适配',
                '这些来源尚未进入统一事实；区分表式标签、结论和实验观测后补映射，不能将未落位视为客户缺项。',
                [ref for x in items for ref in x['sourceRefs']],
                {'section': sheet, 'count': len(items), 'items': items})
        for image in document.get('unreadImages', []):
            add('UNREAD_IMAGE', '图片适配', '图片尚未提取；对照原图辨读，模糊值保持待核对。', [], image)
        all_refs = [ref for row in rows if row['sourceHash'] == source['source_sha256'] for ref in row['sourceRefs']]
        image_refs = [ref for ref in all_refs if ref.get('evidenceType') == 'IMAGE_TRANSCRIPTION']
        if image_refs:
            add('IMAGE_TRANSCRIPTION_REVIEW', '对照原图核对', '逐项对照图片区域核对转录；图片身份校验不证明转录正确。', image_refs)
    return rows


def consolidate_rows(rows, previous_questions=None):
    """One question per file/topic; retain every underlying group and source row.

    Consolidation grants no shared mapping approval. Each file still has its own
    confirmation, and the answer must state its applicable sheets/groups.
    """
    author_codes={'SOURCE_TOTAL_CONFLICT','INVALID_VALUE','NON_NUMERIC_FORMULA',
        'REPEATED_ADDITION_REVIEW','IMAGE_TRANSCRIPTION_REVIEW','SOURCE_DATE_UNRECORDED',
        'SOURCE_DATE_WEEKDAY_CONFLICT','RESULT_UNIT_UNCONFIRMED','FORMULA_AMOUNT_UNRECORDED','EVALUATION_CRITERIA_UNCONFIRMED'}
    buckets={}
    for index,row in enumerate(rows,1):
        code=row['code'];topic=code
        if code=='HUMAN_SOURCE_CHECK':bucket='逐份确认'
        elif code in {'FORMULA_UNIT_UNCONFIRMED','COMPOSITION_BASIS_UNCONFIRMED'}:
            bucket='客户需确认';topic='FORMULA_MEASUREMENT_BASIS'
        elif code in author_codes:bucket='客户需确认'
        else:bucket='内部处理'
        key=(row['sourcePath'],topic)
        if key not in buckets:
            question=row['question']
            if topic=='FORMULA_MEASUREMENT_BASIS':
                question='请注明配方用量的单位及计量基准（质量份、质量百分比、固体份或溶液用量等），并写明适用工作表/组别；不同口径分别填写。'
            elif topic=='SOURCE_TOTAL_CONFLICT':
                question='原合计与明细求和不一致，请确认哪项用量或合计需要更正；不同组原因不同时，按来源坐标分别说明。原值未改。'
            elif topic=='INVALID_VALUE':
                question='原文填写无法明确数值化，请注明正确读法或数值及适用单元格；无法确认的保持未知。'
            elif topic=='NON_NUMERIC_FORMULA':
                question='配方中的短横线等文字代表未使用、未填写还是其他含义？请注明适用组别；没有将其当作0。'
            elif topic=='RESULT_UNIT_UNCONFIRMED':
                question='请逐项确认下列检测项的原始单位及适用组别；原文没有记录且无法确认的，注明未知，不补猜。'
            buckets[key]={'sourcePath':row['sourcePath'],'sourceHash':row['sourceHash'],'bucket':bucket,
                'code':topic,'question':question,'recordKeys':[],'sourceGroupKeys':[],'sourceRowIndices':[],
                'currentValues':[],'sourceRefs':[],'status':'待处理','confirmedValue':'','reviewer':'','approval':'NOT_GRANTED'}
        merged=buckets[key]
        if row['recordKey'] and row['recordKey'] not in merged['recordKeys']:merged['recordKeys'].append(row['recordKey'])
        if row['sourceGroupKey'] and row['sourceGroupKey'] not in merged['sourceGroupKeys']:merged['sourceGroupKeys'].append(row['sourceGroupKey'])
        merged['sourceRowIndices'].append(index)
        merged['currentValues'].append({'recordKey':row['recordKey'],'sourceGroupKey':row['sourceGroupKey'],
                                       'code':code,'values':row['currentValues']})
        merged['sourceRefs']+=row['sourceRefs']
    previous={};used_ids=set();next_id=0
    for q in previous_questions or []:
        qid=q['questionId']
        if not re.fullmatch(r'Q\d+',qid) or qid in used_ids:raise ValueError('旧问题编号无效或重复')
        key=(q['sourcePath'],q['sourceHash'],q['code'])
        if key in previous:raise ValueError('旧问题标识重复')
        previous[key]=qid;used_ids.add(qid);next_id=max(next_id,int(qid[1:]))
    for merged in buckets.values():
        key=(merged['sourcePath'],merged['sourceHash'],merged['code'])
        qid=previous.get(key)
        if qid is None:next_id+=1;qid=f'Q{next_id:04d}'
        merged['questionId']=qid
        merged['sourceRefs']=unique_refs(merged['sourceRefs'])
    return list(buckets.values())


def export_review(root, inputs, output, requirements=None, previous_questions=None):
    root = Path(root).resolve(); destination = contained(root, output)
    if destination.exists():
        raise ValueError('核对清单已存在；不覆盖人工填写内容')
    documents = []
    for relative in inputs:
        path = contained(root, relative); report = read_json(path.parent / 'verification.json')
        if report.get('artifacts', {}).get(path.name) != digest(path):
            raise ValueError('历史数据与提取核验指纹不符')
        document = read_json(path); source = document['source']
        if digest(contained(root, source['source'])) != source['source_sha256']:
            raise ValueError('工作副本已变化')
        original = source.get('original')
        if original and digest(contained(root, original['path'])) != original['sha256']:
            raise ValueError('原件已变化')
        documents.append(document)
    rows = review_rows(documents, requirements)
    questions=consolidate_rows(rows,previous_questions)
    summary = {'files': len(documents), 'rows': len(rows),
        'byCode': dict(Counter(row['code'] for row in rows)), 'approval': 'NOT_GRANTED',
        'consolidatedQuestions':len(questions),'byBucket':dict(Counter(q['bucket'] for q in questions)),
        'scope': '本地核对工作表；缺项先查原文，非自动补值、审核或训练授权'}
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.review-', dir=destination.parent) as temporary:
        stage = Path(temporary)
        write_json(stage/'review.json', {'summary': summary, 'requirements': requirements or {}, 'items': rows})
        write_json(stage/'questions.json', {'summary':summary,'items':questions,
            'answerScope':'答案须说明适用工作表/组别；合并提问不代表自动复用规则或逐份审核通过'})
        with (stage/'review.csv').open('w', encoding='utf-8-sig', newline='') as handle:
            fields = ['sourcePath','recordKey','sourceGroupKey','code','route','question','currentValues',
                      'sourceRefs','status','confirmedValue','reviewer','approval']
            writer = csv.DictWriter(handle, fieldnames=fields); writer.writeheader()
            for row in rows:
                writer.writerow({name: json.dumps(row[name], ensure_ascii=False) if isinstance(row[name], (dict, list))
                                 else row[name] for name in fields})
        stage.rename(destination)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True); parser.add_argument('--input', action='append', required=True)
    parser.add_argument('--output', required=True); parser.add_argument('--requirements')
    parser.add_argument('--previous-questions')
    args = parser.parse_args()
    try:
        print(json.dumps(export_review(args.root, args.input, args.output,
            read_json(args.requirements) if args.requirements else None,
            read_json(contained(Path(args.root),args.previous_questions))['items'] if args.previous_questions else None), ensure_ascii=False)); return 0
    except Exception as error:
        print(json.dumps({'status': 'BLOCKED', 'reason': str(error)}, ensure_ascii=False)); return 2


if __name__ == '__main__':
    raise SystemExit(main())
