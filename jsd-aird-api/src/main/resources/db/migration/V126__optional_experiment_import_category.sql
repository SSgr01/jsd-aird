ALTER TABLE data.experiment_conversion ALTER COLUMN category_id DROP NOT NULL;

-- V125 initializes upload-history department scope. Index those scoped lists.
DO $$
DECLARE
    upload_table text;
BEGIN
    FOREACH upload_table IN ARRAY ARRAY[
        'rnd.experiment_import_job', 'tpl.template_import_job',
        'rnd.research_test_upload', 'quality.upload_file',
        'spc.chart_upload_attempt', 'data.import_job', 'mfg.production_upload'
    ] LOOP
        EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %s (organization_id, department_id, created_at DESC)', replace(upload_table, '.', '_') || '_department_history_idx', upload_table);
    END LOOP;
END $$;

CREATE TABLE spc.method_preset (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES iam.organization(id),
    name varchar(160) NOT NULL,
    method_type varchar(40) NOT NULL CHECK (method_type IN ('IR','UV','HPLC_GPC','PARTICLE_SIZE','GC','MECHANICAL')),
    version integer NOT NULL DEFAULT 1,
    active boolean NOT NULL DEFAULT true,
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot)='object'),
    created_by uuid REFERENCES iam.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX method_preset_organization_idx ON spc.method_preset(organization_id,method_type,active);

-- Only supplied facts are seeded. Unverified measurements remain explicit notes.
INSERT INTO spc.method_preset(id,organization_id,name,method_type,snapshot)
SELECT gen_random_uuid(),o.id,s.name,s.code,s.snapshot::jsonb
FROM iam.organization o CROSS JOIN (VALUES
 ('IR','IRXross · QATR 10 金刚石 ATR','{"schemaVersion":1,"methodType":"IR","instrument":{"model":"IRXross","accessory":"QATR 10","crystal":"金刚石"},"conditions":{"samplingMode":"ATR","resolution":4,"scans":20},"notes":"扫描范围 400～4000 cm⁻¹ 待原始报告核对，暂未登记为实际范围。溴化钾光学部件的具体类型待配置单核对，不代表溴化钾压片法。"}'),
 ('UV','UV-1900i · 紫外可见','{"schemaVersion":1,"methodType":"UV","instrument":{"model":"UV-1900i"},"conditions":{},"notes":"110～500 nm 中的起始波长待仪器方法核对；PM 的化学全名待补。光程和比色皿材质待核实，不自动采用常见值。"}'),
 ('HPLC_GPC','LC-40D · RID-20A · KF-804L','{"schemaVersion":1,"methodType":"HPLC_GPC","instrument":{"detector":"RID-20A","pump":"LC-40D","columnOven":"CTO-40S"},"conditions":{"column":"KF-804L","mobilePhase":"四氢呋喃（THF）","columnTemperature":40,"flowRate":1,"runTime":15,"calibrant":"Shodex SM-105"},"notes":"测试用途、柱数量／串联、RID 温度和校准方法待核对。1.3 MPa 仅为提供的参考运行压力，不代表当次实测。"}'),
 ('PARTICLE_SIZE','纳米粒径 · 待补仪器方法','{"schemaVersion":1,"methodType":"PARTICLE_SIZE","instrument":{},"conditions":{},"notes":"仪器型号及原理待补，不预设为 DLS。介质黏度及光学参数按实际方法补充。"}'),
 ('GC','气相 · 待补完整温程','{"schemaVersion":1,"methodType":"GC","instrument":{},"conditions":{},"notes":"已提供：初温40℃、终温180℃、时间22分钟。保持时间、升温速率和22分钟的含义尚未确认，不能据此推算完整温程。"}'),
 ('MECHANICAL','GB/T 528 · III型试样','{"schemaVersion":1,"methodType":"MECHANICAL","instrument":{},"conditions":{"standard":"GB/T 528（年份待核对）","specimenType":"III型（尺寸待标准核对）"},"notes":"提供的名义尺寸：总长50 mm；端宽8.5±0.5 mm；窄部长16±1.0 mm；窄部宽4.0±0.1 mm；外半径7.5±0.5 mm；内半径10.0±0.5 mm；试验长度10.0±0.5 mm。试验长度是否为标距待确认；名义尺寸不能替代实测截面积。"}')
) AS s(code,name,snapshot);
