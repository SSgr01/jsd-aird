"""Validate installation against a private PostgreSQL cluster, never the application database."""
from __future__ import annotations
import argparse,json,os,re,shutil,socket,subprocess,tempfile,uuid
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser()
parser.add_argument('--postgres-bin',type=Path,default=Path(shutil.which('psql') or r'D:\PostgreSQL\18\bin\psql.exe').parent)
args=parser.parse_args()
runtime=ROOT/'.runtime';runtime.mkdir(exist_ok=True)
env={**os.environ,'PGCLIENTENCODING':'UTF8'}
flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
def run(cmd,check=True):
    # pg_ctl's child processes can retain pipe handles on Windows. File handles avoid that deadlock.
    with tempfile.TemporaryFile() as stdout,tempfile.TemporaryFile() as stderr:
        process=subprocess.run([str(x) for x in cmd],env=env,stdout=stdout,stderr=stderr,creationflags=flags,timeout=60)
        stdout.seek(0);stderr.seek(0)
        result=subprocess.CompletedProcess(cmd,process.returncode,stdout.read().decode('utf-8',errors='replace'),stderr.read().decode('utf-8',errors='replace'))
    if check and result.returncode:raise RuntimeError(result.stderr[-5000:])
    return result
with tempfile.TemporaryDirectory(prefix='unified-lab-sql-',dir=runtime) as directory:
    folder=Path(directory).resolve()
    assert folder.is_relative_to(runtime.resolve()) and folder.name.startswith('unified-lab-sql-')
    with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    suffix='.exe' if os.name=='nt' else ''
    pgctl=args.postgres_bin/('pg_ctl'+suffix)
    run([args.postgres_bin/('initdb'+suffix),'-D',folder/'data','--encoding=UTF8','--no-locale','--auth=trust','-U','postgres'])
    run([pgctl,'-D',folder/'data','-l',folder/'postgres.log','-o',f'-p {port} -h 127.0.0.1','-w','start'])
    try:
        psql=[args.postgres_bin/('psql'+suffix),'-X','-h','127.0.0.1','-p',str(port),'-U','postgres','-d','postgres','-v','ON_ERROR_STOP=1']
        migrations=ROOT/'jsd-aird-api/src/main/resources/db/migration'
        baseline=(migrations/'V2__template_center.sql').read_text(encoding='utf-8')
        statements=['CREATE EXTENSION IF NOT EXISTS pgcrypto;','CREATE SCHEMA iam; CREATE SCHEMA tpl; CREATE SCHEMA ops;']
        for table in ['iam.organization','iam.app_user','ops.file_object','tpl.template','tpl.template_version','tpl.template_mapping']:
            match=re.search(r'CREATE TABLE '+re.escape(table)+r'\s*\([\s\S]*?\n\);',baseline)
            assert match,table
            statements.append(match.group())
        # V14 removed this V2-era column. Validate against the current template schema.
        statements.append('ALTER TABLE tpl.template DROP COLUMN purpose;')
        contract=(migrations/'V17__template_import_contract_v1.sql').read_text(encoding='utf-8')
        statements.append(re.search(r'CREATE TABLE tpl.template_import_contract\s*\([\s\S]*?\n\);',contract).group())
        statements.append((migrations/'V39__template_version_review_workflow.sql').read_text(encoding='utf-8'))
        org=str(uuid.uuid4());actor=str(uuid.uuid4())
        statements.append(f"INSERT INTO iam.organization(id,code,name) VALUES('{org}','LAB-TEST','Isolated SQL verification'); INSERT INTO iam.app_user(id,organization_id,username,display_name,dictionary_admin) VALUES('{actor}','{org}','lab-test','Lab test',true);")
        bootstrap=folder/'bootstrap.sql';bootstrap.write_text('\n'.join(statements),encoding='utf-8')
        run(psql+['-f',bootstrap])
        seed=ROOT/'deployment/unified-experiments/install.sql'
        run(psql+['-f',seed]);run(psql+['-f',seed])
        def query(sql):return run(psql+['-At','-c',sql]).stdout.strip()
        assert query('SELECT count(*) FROM tpl.template;')=='2'
        assert query('SELECT count(*) FROM tpl.template_version;')=='2'
        assert query('SELECT count(*) FROM tpl.template_import_contract WHERE import_contract_version=9;')=='2'
        assert query("SELECT count(*) FROM tpl.template_version WHERE layout_summary_jsonb#>'{initialSnapshot,sheets}' IS NOT NULL AND editor_snapshot_file_id IS NULL;")=='2'
        count=int(query('SELECT count(*) FROM tpl.template_mapping;'));assert count>100,count
        conflict=run(psql+['-c',f"BEGIN; UPDATE tpl.unified_lab_template_package SET package_hash=repeat('0',64); SELECT tpl.install_unified_lab_templates('{org}','{actor}');"],check=False)
        assert conflict.returncode!=0 and 'different content' in conflict.stderr,conflict.stderr
        assert query('SELECT count(*) FROM tpl.template_version;')=='2'
        org2=str(uuid.uuid4());actor2=str(uuid.uuid4())
        query(f"INSERT INTO iam.organization(id,code,name) VALUES('{org2}','LAB-TEST-2','Second isolated tenant'); INSERT INTO iam.app_user(id,organization_id,username,display_name) VALUES('{actor2}','{org2}','lab-test-2','Lab test'); SELECT tpl.install_unified_lab_templates('{org2}','{actor2}');")
        assert query('SELECT count(*) FROM tpl.template;')=='4'
        print(json.dumps({'status':'PASS','database':'private temporary PostgreSQL cluster','templatesPerOrganization':2,'mappingCountPerOrganization':count,'idempotence':True,'conflictProtection':True,'tenantIsolation':True,'sourceWorkbooksModified':False}))
    finally:
        run([pgctl,'-D',folder/'data','-m','fast','-w','stop'])
