"""Local, source-bound historical experiment extraction before template rendering.

The model supplies semantic mappings. This module reads evidence, validates types,
keeps unplaced facts and emits draft data; it never calls an API or trains a model.
"""
from __future__ import annotations

import argparse
import csv
from decimal import Decimal, InvalidOperation
import hashlib
import json
import math
from pathlib import Path
import re
import tempfile
import zipfile

from history_sources import HistorySource, contained, nonempty
from workflow import digest, read_json, write_json

NUMBER = r'[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?'


def identifier(*parts):
    return hashlib.sha256(json.dumps(parts, ensure_ascii=False, sort_keys=True, default=str).encode()).hexdigest()[:24]


def decimal_number(value):
    if isinstance(value, bool) or not isinstance(value, (str, int, float)) or not re.fullmatch(NUMBER, str(value).strip()):
        raise ValueError('不是无歧义的数字：' + repr(value))
    number = Decimal(str(value).strip())
    if not number.is_finite() or not math.isfinite(float(number)):
        raise ValueError('数字不是有限值')
    return int(number) if number == number.to_integral_value() else float(number)


def typed_value(raw, spec, number_format=''):
    """No implicit averaging, unit inference, slash-to-zero or boolean coercion."""
    if not nonempty(raw):
        return {'kind': 'MISSING', 'value': None}
    if raw == '/':
        return {'kind': 'NOT_USED', 'value': None}
    mode = spec.get('parse', 'text')
    value = raw
    if spec.get('pattern'):
        if not isinstance(raw, str):
            raise ValueError('显式文本提取需要字符串来源')
        matches = list(re.finditer(spec['pattern'], raw, re.DOTALL))
        if len(matches) != 1:
            raise ValueError('源文本提取未唯一命中')
        value = matches[0].group(spec.get('group', 1))
    if mode == 'text':
        return {'kind': 'TEXT', 'value': value}
    if mode == 'number':
        return {'kind': 'NUMBER', 'value': decimal_number(value)}
    if mode == 'percent_points':
        if isinstance(value, str) and value.strip().endswith('%'):
            result = decimal_number(value.strip()[:-1])
        elif isinstance(value, (int, float)) and not isinstance(value, bool) and '%' in number_format:
            result = decimal_number(str(Decimal(str(value)) * 100))
        else:
            raise ValueError('百分比需由来源百分号或Excel百分比格式证明，不能猜乘100')
        return {'kind': 'NUMBER', 'value': result}
    if mode in {'interval', 'bound'}:
        text = str(value).strip()
        unit = spec.get('unit', '')
        if unit and text.endswith(unit):
            text = text[:-len(unit)].strip()
        if mode == 'interval':
            match = re.fullmatch('('+NUMBER+r')\s*[-~～—–至]\s*('+NUMBER+')', text)
            if not match:
                raise ValueError('区间须保留明确的上下界')
            lower, upper = (decimal_number(x) for x in match.groups())
            if lower > upper:
                raise ValueError('源区间上下界倒置')
            return {'kind': 'INTERVAL', 'value': None, 'lower': lower, 'upper': upper}
        match = re.fullmatch(r'(>=|<=|≥|≤|>|<)\s*('+NUMBER+')', text)
        if not match:
            raise ValueError('上下限须保留比较符号')
        return {'kind': 'BOUND', 'value': None, 'operator': match[1], 'limit': decimal_number(match[2])}
    if mode == 'qualitative':
        return {'kind': 'QUALITATIVE', 'value': str(value)}
    raise ValueError('未知显式解析方式：' + str(mode))


class Compiler:
    def __init__(self, source):
        self.source, self.issues = source, []

    def field(self, spec):
        spec = {'ref': spec} if isinstance(spec, str) else spec
        if not isinstance(spec, dict) or 'ref' not in spec or any(k in spec for k in ('value', 'literal', 'verified', 'approved')):
            raise ValueError('事实字段必须引用来源，不能直接提供值或审核状态')
        raw, ref = self.source.read(spec['ref'])
        refs = [ref]
        unit = spec.get('unit', '')
        if not isinstance(unit, str):
            raise ValueError('单位必须是来源明确的文字')
        if spec.get('unitRef'):
            _, unit_ref = self.source.read(spec['unitRef'])
            refs.append(unit_ref)
        else:
            unit_ref = ref
        if unit and unit not in str(unit_ref['rawValue']) and not (unit == '%' and '%' in ref.get('numberFormat', '')):
            raise ValueError('单位未见于声明的来源，不能推断：' + unit)
        try:
            if ref.get('formulaCacheMissing'):
                raise ValueError('源公式缺少缓存，不能伪造重算值')
            if ref.get('physicalValueType') == 'e':
                raise ValueError('源单元格为Excel错误值')
            parsed = typed_value(raw, spec, ref.get('numberFormat', ''))
        except (ValueError, InvalidOperation, OverflowError) as error:
            parsed = {'kind': 'INVALID', 'value': None}
            self.issues.append({'code': 'INVALID_VALUE', 'reason': str(error), 'sourceRefs': refs})
        return {'rawValue': raw, 'rawUnit': unit, 'unit': unit, 'normalized': parsed,
                'value': parsed['value'], 'sourceRefs': refs, 'mappingStatus': 'PROPOSED'}

    def fields(self, mapping):
        return {name: self.field(value) for name, value in mapping.items()}

    def compile_record(self, spec):
        key = spec['key']
        if not isinstance(key, str) or not key:
            raise ValueError('实验区块须有稳定内部key，不能冒充客户实验编号')
        experiment_type = spec.get('experimentType', 'UNKNOWN')
        if experiment_type not in {'TEST','SYNTHESIS','UNKNOWN'}:
            raise ValueError('实验类型必须为TEST、SYNTHESIS或UNKNOWN')
        role = spec.get('recordRole', 'EXPERIMENT')
        if role not in {'EXPERIMENT', 'SUMMARY'}:
            raise ValueError('记录角色必须为EXPERIMENT或SUMMARY；汇总不计为新的独立实验')
        record = {'schemaVersion': 2, 'recordKey': key, 'experimentType': experiment_type, 'recordRole': role, 'metadata': self.fields(spec.get('metadata', {})),
                  'sourceGroups': [], 'sourceContexts': [], 'formulaItems': [], 'processSteps': [], 'testResults': []}
        groups = {}
        for group in spec.get('groups', []):
            if not group.get('key') or group['key'] in groups:
                raise ValueError('同一实验区块的内部组key缺失或重复')
            identity = self.field(group['identity']) if group.get('identity') else None
            value = identity['value'] if identity else ''
            group_key = identifier(self.source.sha, key, group['key'])
            groups[group['key']] = group_key
            record['sourceGroups'].append({'sourceGroupKey': group_key, 'sampleKey': group_key,
                'internalKey': group['key'], 'sourceIdentity': value,
                'sourceIdentityType': group.get('identityType', 'UNSPECIFIED'),
                'sourceRefs': identity['sourceRefs'] if identity else [],
                'properties': self.fields(group.get('properties', {}))})
        context_keys = set()
        def membership(item):
            if item.get('group') not in groups:
                raise ValueError('事实必须关联当前实验内明确的组：' + str(item.get('group')))
            return groups[item['group']]
        def common(item, ordinal, domain):
            group = membership(item)
            return {'itemId': identifier(self.source.sha, key, domain, ordinal),
                    'sourceGroupKey': group, 'sampleKey': group, 'sourceRecordKey': key,
                    'mappingStatus': 'PROPOSED'}
        for index, item in enumerate(spec.get('formulaItems', [])):
            name, amount = self.field(item['material']), self.field(item['amount'])
            if not nonempty(name['value']) or isinstance(name['value'], (dict, list)):
                raise ValueError('物料名称必须来自明确的非空原文')
            result = {**common(item, index, 'FORMULA'), **amount,
                'materialName': name['value'], 'materialId': None, 'materialCode': '',
                'ratio': amount['value'], 'stageKey': item.get('stageKey', ''),
                'sequence': index + 1, 'compositionBasis': item.get('compositionBasis', 'UNSPECIFIED'),
                'sourceRefs': name['sourceRefs'] + amount['sourceRefs']}
            if item.get('reportedFraction'):
                result['reportedFraction'] = self.field(item['reportedFraction'])
                result['sourceRefs'] += result['reportedFraction']['sourceRefs']
            if amount['normalized']['kind'] not in {'NUMBER', 'NOT_USED', 'MISSING', 'INVALID'}:
                self.issues.append({'code': 'NON_NUMERIC_FORMULA', 'sourceRefs': result['sourceRefs'],
                                    'reason': '用量尚未明确数值化，保留原文，不纳入数值配方合计'})
            record['formulaItems'].append(result)
        for index, item in enumerate(spec.get('processSteps', [])):
            operation = self.field(item['operation'])
            parameters = self.fields(item.get('parameters', {}))
            record['processSteps'].append({**common(item, index, 'PROCESS'),
                'operation': operation['value'], 'rawValue': operation['rawValue'],
                'order': item.get('order', index + 1), 'parameters': parameters,
                'sourceRefs': operation['sourceRefs'] + [r for v in parameters.values() for r in v['sourceRefs']]})
        for index, item in enumerate(spec.get('testResults', [])):
            label, value = self.field(item['item']), self.field(item['value'])
            conditions = self.fields(item.get('conditions', {}))
            method = self.field(item['method']) if item.get('method') else None
            stage = self.field(item['stage']) if item.get('stage') else None
            context_key = identifier(self.source.sha, key, item['group'],
                {k: (v['normalized'], v['unit']) for k, v in conditions.items()},
                method['value'] if method else '', stage['value'] if stage else '')
            if context_key not in context_keys:
                record['sourceContexts'].append({'sourceContextKey': context_key,
                    'sourceGroupKey': membership(item), 'conditions': conditions,
                    'method': method, 'stage': stage})
                context_keys.add(context_key)
            result = {**common(item, index, 'TEST'), **value,
                'testItem': label['value'], 'semanticKey': item.get('semanticKey', ''),
                'testMethod': method['value'] if method else '', 'testStage': stage['value'] if stage else '',
                'testCondition': '', 'substrate': conditions.get('substrate', {}).get('value', ''),
                'sourceContextKey': context_key, 'conditions': conditions,
                'sourceRefs': label['sourceRefs'] + value['sourceRefs'] +
                    [r for v in conditions.values() for r in v['sourceRefs']] +
                    (method['sourceRefs'] if method else []) + (stage['sourceRefs'] if stage else [])}
            record['testResults'].append(result)
        record['sourceTotals'] = []
        for item in spec.get('totals', []):
            group = membership(item)
            total = self.field(item['value'])
            amounts = [v for v in record['formulaItems'] if v['sourceGroupKey'] == group]
            units = {v['unit'] for v in amounts if v['normalized']['kind'] == 'NUMBER'}
            if item.get('compositionBasis'):
                amounts = [v for v in amounts if v['compositionBasis'] == item['compositionBasis']]
                units = {v['unit'] for v in amounts if v['normalized']['kind'] == 'NUMBER'}
            comparable = (bool(amounts) and total['normalized']['kind'] == 'NUMBER' and len(units) == 1
                and total['unit'] in units and all(v['normalized']['kind'] in {'NUMBER', 'NOT_USED'} for v in amounts))
            computed = float(sum((Decimal(str(v['value'])) for v in amounts if v['normalized']['kind'] == 'NUMBER'), Decimal(0))) if comparable else None
            entry = {'sourceGroupKey': group, **total, 'computedTotal': computed,
                     'comparison': 'UNRESOLVED' if computed is None else 'MATCH' if math.isclose(computed, total['value'], abs_tol=1e-8, rel_tol=1e-10) else 'CONFLICT'}
            record['sourceTotals'].append(entry)
            if entry['comparison'] == 'CONFLICT':
                self.issues.append({'code': 'SOURCE_TOTAL_CONFLICT', 'sourceGroupKey': group,
                    'sourceRefs': total['sourceRefs'], 'sourceTotal': total['value'], 'computedTotal': computed,
                    'reason': '保留实际用量与源合计，两者不一致；未改写原件或填造用量'})
        if not any(record[k] for k in ('formulaItems','processSteps','testResults')):
            raise ValueError('实验区块没有配方、工艺或检测事实；空白表式应明确归档，不伪造实验')
        return record


def compile_document_facts(compiler, plan):
    """Preserve file notes and proven unused template calculations separately.

    A zero/error alone is never evidence that a cell is an unused template.
    Proof checks the actual identity, input and observation cells in that column.
    """
    source = compiler.source
    material_refs = set()
    for record in plan.get('records', []):
        for item in record.get('formulaItems', []):
            ref = item['material']
            if isinstance(ref, dict):ref = ref.get('ref')
            if isinstance(ref, str):material_refs.add(source.aliases.get(ref, ref))
    result = []
    for item in plan.get('documentFacts', []):
        role, reason = item.get('role'), item.get('reason')
        if not reason or role not in {'SOURCE_NOTE', 'TEMPLATE_CALCULATION', 'UNQUANTIFIED_RECIPE_LABEL'}:
            raise ValueError('文件级事实须有来源角色和明确说明')
        spec = item['field'];ref = spec if isinstance(spec, str) else spec['ref']
        if not isinstance(ref, str):raise ValueError('文件级事实须引用单元格或正文')
        canonical = source.aliases.get(ref, ref)
        cell = source.cells[canonical]
        proof = item.get('proof')
        if role in {'SOURCE_NOTE','UNQUANTIFIED_RECIPE_LABEL'}:
            if not isinstance(cell['rawValue'], str) or re.fullmatch(NUMBER, cell['rawValue'].strip()) or cell.get('formula'):
                raise ValueError('不能将数字或公式观测作为文件文字说明')
        else:
            from openpyxl.utils.cell import coordinate_from_string, range_boundaries, get_column_letter
            sheet, coordinate = canonical.rsplit('!', 1)
            col, row = coordinate_from_string(coordinate)
            if (source.path.suffix.lower() != '.xlsx' or not cell.get('formula')
                    or cell.get('formulaCacheMissing') or isinstance(cell['rawValue'], bool)
                    or not (cell['rawValue'] == 0 or isinstance(cell['rawValue'], str) and cell['rawValue'].startswith('#'))):
                raise ValueError('模板诊断仅接受已有缓存的零值/错误公式，不接受真实观测常量')
            if not isinstance(proof, dict) or set(proof) != {'identityRef','inputRange','observationRange'}:
                raise ValueError('模板诊断缺少空白身份、输入和观测范围证明')
            identity = proof['identityRef']
            if not isinstance(identity, str) or not identity.startswith(sheet+'!') or source.has(identity):
                raise ValueError('模板诊断列已有实验身份或证明来自其他表')
            identity_col, identity_row = coordinate_from_string(identity.rsplit('!',1)[1])
            if identity_col != col:raise ValueError('模板诊断证明须来自同列')
            ranges=[]
            for label in ['inputRange','observationRange']:
                left, top, right, bottom = range_boundaries(proof[label])
                if left != right or get_column_letter(left) != col or bottom-top > 2000:
                    raise ValueError('模板诊断证明范围须为同列有界区域')
                ranges.append((top,bottom))
                for n in range(top,bottom+1):
                    anchor = sheet+'!'+col+str(n)
                    if not source.has(anchor):continue
                    anchor = source.aliases.get(anchor,anchor)
                    if label == 'inputRange' and anchor in material_refs and isinstance(source.cells[anchor]['rawValue'],str):continue
                    raise ValueError('模板诊断范围存在真实输入或观测，不能排除')
            if not (identity_row < ranges[0][0] <= ranges[0][1] < row < ranges[1][0]):
                raise ValueError('模板诊断证明范围不完整或与目标重叠')
        field = compiler.field(spec)
        result.append({'role':role,'reason':reason,'field':field,**({'proof':proof} if proof else {})})
    return result


def extract_history(root, plan_path, output=None):
    root = Path(root).resolve()
    plan_path = Path(plan_path).resolve()
    plan = read_json(plan_path)
    source = HistorySource(root, plan['source'], plan['source_sha256'])
    try:
        original = None
        if plan.get('original'):
            original = plan['original']
            if digest(contained(root, original['path'])) != original['sha256']:
                raise ValueError('历史原件指纹已变')
        engine = ''.join(digest(Path(__file__).with_name(name)) for name in
                         ('history_data.py','history_sources.py','execute_plan.py','workflow.py'))
        fingerprint = {'sourceSha256': source.sha, 'planSha256': digest(plan_path), 'engineSha256': engine}
        destination = contained(root, output or ('结构化结果/' + source.path.name + '.extracted'))
        if destination.exists():
            receipt_path = destination / 'verification.json'
            if receipt_path.is_file():
                receipt = read_json(receipt_path)
                if (all(receipt.get(k) == v for k, v in fingerprint.items()) and
                    all((destination / n).is_file() and digest(destination / n) == h for n, h in receipt.get('artifacts', {}).items())
                    and receipt.get('artifacts')):
                    return {'cache_hit': True, 'output': destination.relative_to(root).as_posix(), **receipt}
            raise ValueError('结果目录已存在且指纹不匹配；不覆盖旧结果，请指定新的输出位置')
        compiler = Compiler(source)
        specs = plan.get('records', [])
        if len({r['key'] for r in specs}) != len(specs):
            raise ValueError('实验区块key重复')
        records = [compiler.compile_record(spec) for spec in specs]
        document_facts = compile_document_facts(compiler, plan)
        mapped_cells = set(source.used)
        for item in plan.get('structural', []):
            if not item.get('reason'):
                raise ValueError('结构标签需说明原因')
            value, _ = source.read(item['ref'])
            if not isinstance(value, str) or re.fullmatch(NUMBER, value.strip()):
                raise ValueError('不能把非文字或纯数字观测声明为结构标签')
        structural_cells = source.used - mapped_cells
        unplaced = []
        for item in plan.get('unplaced', []):
            if not item.get('reason'):
                raise ValueError('未落位数据需说明原因')
            value, ref = source.read(item['ref'])
            unplaced.append({'rawValue': value, 'reason': item['reason'], 'sourceRefs': [ref]})
        # Unknown facts are retained rather than silently discarded or guessed.
        for anchor in sorted(source.cells.keys() - source.used):
            value, ref = source.read(anchor)
            unplaced.append({'rawValue': value, 'reason': '尚无明确语义映射；保留原值及坐标', 'sourceRefs': [ref]})
        unread_images = [v for k, v in source.images.items() if k not in source.used_images]
        image_based = any(r.get('evidenceType') == 'IMAGE_TRANSCRIPTION'
                          for record in records for field in record.get('testResults', []) + record.get('formulaItems', [])
                          for r in field['sourceRefs']) or bool(source.used_images)
        status = 'PARTIAL' if unplaced or unread_images or compiler.issues or image_based else 'EXTRACTED'
        if not records:
            if not plan.get('archiveReason'):
                raise ValueError('不提取实验时必须明确archiveReason，不把空输出当成功')
            status = 'ARCHIVE_ONLY'
        data = {'schemaVersion': 2, 'source': {**source.overview(), 'original': original},
                'experiments': records, 'documentFacts': document_facts, 'unplaced': unplaced, 'unreadImages': unread_images,
                'issues': compiler.issues, 'extractionStatus': status,
                'scope': '本地提取草稿；未经现有人工核对和实验审核流程，不是已完成实验或训练授权'}
        assessment = plan.get('templateAssessment', {'decision':'NOT_REQUESTED','reason':'历史数据提取不自动要求标准模板输出'})
        if assessment.get('decision') not in {'NOT_REQUESTED','NOT_SUITABLE','POSSIBLE'} or not assessment.get('reason'):
            raise ValueError('模板适用性判断须给出明确决定及原因')
        data['templateAssessment'] = assessment
        if digest(source.path) != source.sha:
            raise ValueError('执行过程中工作副本发生变化')
        if original and digest(contained(root, original['path'])) != original['sha256']:
            raise ValueError('执行过程中原件发生变化')
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.history-', dir=destination.parent) as temp:
            stage = Path(temp)
            write_json(stage / 'history.json', data)
            write_json(stage / 'mapping.json', plan)
            with (stage / 'review.csv').open('w', encoding='utf-8-sig', newline='') as handle:
                writer = csv.DictWriter(handle, fieldnames=['record','group','domain','label','raw_value','normalized','unit','conditions','source_refs'])
                writer.writeheader()
                for record in records:
                    for label, item in record['metadata'].items():
                        writer.writerow({'record':record['recordKey'],'domain':'METADATA','label':label,
                            'raw_value':item['rawValue'],'normalized':json.dumps(item['normalized'],ensure_ascii=False),
                            'unit':item['unit'],'source_refs':json.dumps(item['sourceRefs'],ensure_ascii=False)})
                    for group in record['sourceGroups']:
                        writer.writerow({'record':record['recordKey'],'group':group['sourceGroupKey'],
                            'domain':'GROUP','label':group['sourceIdentityType'],'raw_value':group['sourceIdentity'],
                            'source_refs':json.dumps(group['sourceRefs'],ensure_ascii=False)})
                    for domain, label_key in [('formulaItems','materialName'),('processSteps','operation'),('testResults','testItem')]:
                        for item in record[domain]:
                            writer.writerow({'record': record['recordKey'], 'group': item['sourceGroupKey'],
                                'domain': domain, 'label': item[label_key], 'raw_value': item.get('rawValue'),
                                'normalized': json.dumps(item.get('normalized', item.get('parameters')), ensure_ascii=False),
                                'unit': item.get('unit',''), 'conditions': json.dumps(item.get('conditions',{}),ensure_ascii=False),
                                'source_refs': json.dumps(item['sourceRefs'],ensure_ascii=False)})
                for item in unplaced:
                    writer.writerow({'domain':'UNPLACED','raw_value':item['rawValue'],
                                     'label':item['reason'],'source_refs':json.dumps(item['sourceRefs'],ensure_ascii=False)})
                for item in document_facts:
                    value=item['field']
                    writer.writerow({'domain':item['role'],'label':item['reason'],'raw_value':value['rawValue'],
                        'normalized':json.dumps(value['normalized'],ensure_ascii=False),'unit':value['unit'],
                        'source_refs':json.dumps(value['sourceRefs'],ensure_ascii=False)})
            with zipfile.ZipFile(source.path) as archive:
                for image in source.images.values():
                    path = stage / 'source-images' / image['part']
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(archive.read(image['part']))
            artifacts = {p.relative_to(stage).as_posix(): digest(p) for p in stage.rglob('*') if p.is_file()}
            receipt = {**fingerprint, 'status': status, 'records': len(records),
                'groups': sum(len(r['sourceGroups']) for r in records),
                'formulaItems': sum(len(r['formulaItems']) for r in records),
                'observations': sum(len(r['testResults']) for r in records),
                'unplaced': len(unplaced), 'unreadImages': len(unread_images),
                'documentFacts':len(document_facts),
                'coverage': {'nonempty': len(source.cells), 'mapped': len(mapped_cells),
                    'structural': len(structural_cells), 'unplaced': len(source.cells.keys() - mapped_cells - structural_cells)},
                'imageTranscriptionsRequireReview': image_based, 'issues': compiler.issues,
                'templateAssessment': assessment,
                'artifacts': artifacts, 'approval': 'NOT_GRANTED',
                'limitations': '指纹及类型核对不证明语义映射或图片转录正确；未导入、审核、训练或发布'}
            write_json(stage / 'verification.json', receipt)
            # Rename within the same filesystem; no partial result directory.
            stage.rename(destination)
        return {'cache_hit': False, 'output': destination.relative_to(root).as_posix(), **receipt}
    finally:
        source.close()


def batch(root, manifest_path):
    root = Path(root).resolve()
    manifest = read_json(manifest_path)
    results = []
    for item in manifest['items']:
        try:
            result = extract_history(root, contained(root, item['plan']), item.get('output'))
            # Keep the manifest bounded; full evidence remains in each directory.
            results.append({k: v for k, v in result.items() if k != 'artifacts'})
        except Exception as error:
            results.append({'plan': item.get('plan'), 'status': 'BLOCKED', 'reason': str(error)})
    return {'items': results, 'counts': {s: sum(r['status'] == s for r in results)
                for s in ('EXTRACTED','PARTIAL','ARCHIVE_ONLY','BLOCKED')}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['inspect','execute','batch'])
    parser.add_argument('--root', required=True)
    parser.add_argument('--source')
    parser.add_argument('--plan')
    parser.add_argument('--manifest')
    parser.add_argument('--output')
    args = parser.parse_args()
    root = Path(args.root).resolve()
    try:
        if args.action == 'inspect':
            source = HistorySource(root, args.source)
            try:
                path = contained(root, args.output or ('.dsh/cache/jsd-experiment-converter/history/'+source.sha+'/source.json'))
                write_json(path, {**source.overview(), 'cells': source.cells})
                result = {**source.overview(), 'artifact': str(path)}
            finally:
                source.close()
        elif args.action == 'execute':
            result = extract_history(root, args.plan, args.output)
        else:
            result = batch(root, args.manifest)
            if args.output:
                write_json(contained(root, args.output), result)
        # Avoid dumping all artifact hashes or source values into model context.
        print(json.dumps({k:v for k,v in result.items() if k != 'artifacts'}, ensure_ascii=False))
        return 0
    except Exception as error:
        print(json.dumps({'status':'BLOCKED','reason':str(error)},ensure_ascii=False))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
