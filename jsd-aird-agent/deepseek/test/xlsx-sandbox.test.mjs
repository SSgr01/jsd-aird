import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';
import {workbookWorkflow, inspectWorkbook} from '../../enterprise-plugin/src/xlsx-runtime.mjs';

const sandboxUrl=process.env.JSD_TEST_SANDBOX_URL;
const sandboxToken=process.env.JSD_TEST_SANDBOX_TOKEN;

test('the shipped workbook example and inspector execute in the configured Linux sandbox',
  {skip:!sandboxUrl||!sandboxToken},async()=>{
  const generator=workbookWorkflow.match(/~~~python\n([\s\S]*?)\n~~~/)[1];
  const payload=JSON.stringify({generator,inspector:inspectWorkbook});
  const script=String.raw`import ast, datetime, hashlib, json, os, pathlib, subprocess, tempfile, zipfile
from unittest.mock import patch
from openpyxl import load_workbook

payload = json.loads(${JSON.stringify(payload)})
pathlib.Path("/work/scratch/native").mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix="xlsx-runtime-", dir="/work/scratch/native") as temporary:
    directory = pathlib.Path(temporary) / "客户's 台账 空间"
    directory.mkdir()
    os.chdir(directory)
    logical_directory = pathlib.Path('/workspaces') / directory.relative_to('/work/scratch/native')
    logical_env = dict(os.environ, PWD=str(logical_directory))
    shell = subprocess.run(['/bin/bash', '-c', 'pwd; printf retained > relative.txt'],
        cwd=directory, env=logical_env, capture_output=True, text=True)
    assert shell.returncode == 0, shell.stderr
    assert shell.stdout.strip() == str(logical_directory), shell.stdout
    assert (directory / 'relative.txt').read_text() == 'retained'
    source = {"headers": ["编号", "数量", "日期", "备注"], "rows": [
        ["0007", 0, "2026-11-12", ""], ["0008", 2.5, "2026-11-13", None]]}
    pathlib.Path("source_rows.json").write_text(json.dumps(source, ensure_ascii=False), encoding="utf-8")
    ast.parse(payload["generator"])
    exec(compile(payload["generator"], "build_workbook.py", "exec"), {})
    output = directory / "整理结果.xlsx"
    original = output.read_bytes()
    def inspect(path, expected_hash=None):
        return subprocess.run(["/usr/local/bin/python3", "-I", "-B", "-c", payload["inspector"],
            str(path), expected_hash or hashlib.sha256(path.read_bytes()).hexdigest()], capture_output=True, text=True)
    assert inspect(output).returncode == 0
    with patch("openpyxl.load_workbook", side_effect=ValueError("inspection failed")):
        try:
            exec(compile(payload["generator"], "build_workbook.py", "exec"), {})
            raise AssertionError("generation should have failed")
        except ValueError as error:
            assert str(error) == "inspection failed"
    assert output.read_bytes() == original
    assert not list(directory.glob(".xlsx-*.xlsx"))
    workbook = load_workbook(output, data_only=False)
    try:
        assert list(workbook.active.values) == [tuple(source["headers"]),
            ("0007", 0, "2026-11-12", None), ("0008", 2.5, "2026-11-13", None)]
        totals = workbook.create_sheet("汇总")
        totals["A1"] = "=SUM('原始记录'!B2:B3)"
        totals["B1"] = datetime.date(2026, 11, 12)
        totals["B1"].number_format = "yyyy-mm-dd"
        updated = directory / "补充汇总.xlsx"
        workbook.save(updated)
    finally:
        workbook.close()
    assert output.read_bytes() == original
    assert inspect(updated).returncode == 0
    reopened = load_workbook(updated, data_only=False)
    try:
        assert reopened.sheetnames == ["原始记录", "汇总"]
        assert reopened["汇总"]["A1"].value == "=SUM('原始记录'!B2:B3)"
        assert reopened["汇总"]["B1"].value == datetime.datetime(2026, 11, 12)
        assert reopened["原始记录"]["A1"].font.bold is True
    finally:
        reopened.close()
    broken = directory / "损坏.xlsx"
    broken.write_bytes(b"not an Office document")
    assert inspect(broken).returncode == 1
    damaged = directory / "缺少工作表.xlsx"
    with zipfile.ZipFile(output) as source_zip, zipfile.ZipFile(damaged, "w") as target_zip:
        for part in source_zip.infolist():
            if part.filename != "xl/worksheets/sheet1.xml":
                target_zip.writestr(part, source_zip.read(part.filename))
    assert inspect(damaged).returncode == 1
    assert inspect(output, "0" * 64).returncode == 1
    assert output.read_bytes() == original
    print("PASS: source values, multiple sheets, formulas, styles, failed-save preservation, corrupt packages and hash changes")
`;
  const executionId=randomUUID().replaceAll('-','');
  const response=await fetch(sandboxUrl+'/executions',{method:'POST',signal:AbortSignal.timeout(30000),
    headers:{authorization:'Bearer '+sandboxToken,'content-type':'application/json'},
    body:JSON.stringify({organization_id:randomUUID(),run_id:randomUUID(),execution_id:executionId,
      script,language:'python',timeout_ms:60000,files:[]})});
  let receipt=await response.json();
  assert.equal(response.ok,true,JSON.stringify(receipt));
  const deadline=Date.now()+120000;
  // Use the same durable path as enterprise commands. Even a cleanup delay
  // permits only original-receipt reads, never another command submission.
  while(!['SUCCEEDED','FAILED','CANCELLED'].includes(receipt.status) && Date.now()<deadline) {
    await delay(1000);
    const read=await fetch(sandboxUrl+'/executions/'+executionId,{signal:AbortSignal.timeout(30000),
      headers:{authorization:'Bearer '+sandboxToken}});
    assert.equal(read.ok,true);
    receipt=await read.json();
  }
  assert.equal(receipt.status,'SUCCEEDED',JSON.stringify({executionId,status:receipt.status,error:receipt.error}));
  const result=receipt.result;
  assert.equal(result.exit_code,0,result.output);
  assert.match(result.output,/PASS: source values/);
});
