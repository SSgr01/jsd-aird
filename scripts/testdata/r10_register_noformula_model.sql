BEGIN;
DO $$
DECLARE
  org uuid := '00000000-0000-0000-0000-000000000001';
  actor uuid := '00000000-0000-0000-0000-000000000002';
  target uuid := '90000000-0000-0000-0000-000000000001';
  target_version uuid := '90000000-0000-0000-0000-000000000002';
  scheme uuid := '90000000-0000-0000-0000-000000000003';
  train_policy uuid := '90000000-0000-0000-0000-000000000004';
  domain_policy uuid := '90000000-0000-0000-0000-000000000005';
  quality_policy uuid := '90000000-0000-0000-0000-000000000006';
  dc_mapping uuid := '90000000-0000-0000-0000-000000000007';
  exp_mapping uuid := '90000000-0000-0000-0000-000000000008';
  snapshot uuid := '90000000-0000-0000-0000-000000000009';
  job uuid := '90000000-0000-0000-0000-00000000000a';
  artifact uuid := '90000000-0000-0000-0000-00000000000b';
  model uuid := '90000000-0000-0000-0000-00000000000c';
  temperature_version uuid := '05060000-0000-0000-0000-000000000011';
  dictionary uuid := '05060000-0000-0000-0000-000000000020';
  bundle_sha text := '613bcdde9aa0f27136233962e1dcec2dfb7fad2d47fd741d6f540ca4491aef46';
  empty_obj jsonb := '{}'::jsonb;
BEGIN
  INSERT INTO ai.prediction_target(id,organization_id,target_code,name,performance_project,value_type,status,created_by,updated_by)
  VALUES(target,org,'R10_NO_FORMULA_GLOSS','R10 无配方光泽','R10 隔离验收','CONTINUOUS','DRAFT',actor,actor)
  ON CONFLICT (organization_id,target_code) DO NOTHING;
  INSERT INTO ai.target_version(id,organization_id,target_id,version_no,status,value_type,unit,classes_jsonb,definition_jsonb,observation_semantics_jsonb,config_hash,published_at,created_by)
  VALUES(target_version,org,target,1,'PUBLISHED','CONTINUOUS','GU','[]'::jsonb,
         '{"testMethod":"R10 no-formula test","sopCode":"R10-SYNTHETIC","testStage":"UV固化后","pretreatment":"none","optimizationDirection":"MAXIMIZE","valueDomain":{"minimum":0,"maximum":200}}'::jsonb,
         '{"observationType":"EXACT"}'::jsonb,repeat('c',64),now(),actor)
  ON CONFLICT (organization_id,target_id,version_no) DO NOTHING;
  INSERT INTO ai.input_scheme(id,organization_id,target_id,target_version_id,material_dictionary_version_id,scheme_code,name,version_no,status,preprocessing_jsonb,config_hash,revision,frozen_at,created_by,updated_by)
  VALUES(scheme,org,target,target_version,dictionary,'R10_NO_FORMULA_SCHEME','R10 无配方输入方案',1,'DRAFT','{}'::jsonb,repeat('b',64),0,NULL,actor,actor)
  ON CONFLICT (organization_id,target_id,scheme_code,version_no) DO NOTHING;
  INSERT INTO ai.input_scheme_field(organization_id,input_scheme_id,input_field_version_id,required,ordinal,override_jsonb)
  VALUES(org,scheme,temperature_version,true,0,'{}'::jsonb)
  ON CONFLICT (input_scheme_id,input_field_version_id) DO NOTHING;
  UPDATE ai.input_scheme SET status='FROZEN',frozen_at=now() WHERE organization_id=org AND id=scheme;
  INSERT INTO ai.modeling_policy_version(id,organization_id,target_id,version_no,kind,status,qualification_jsonb,validation_jsonb,training_jsonb,configuration_jsonb,policy_hash,published_at,created_by,published_by)
  VALUES
    (train_policy,org,target,1,'TRAINING','PUBLISHED','{}'::jsonb,'{}'::jsonb,'{"candidateAlgorithms":["RANDOM_FOREST"],"replicateHandling":"KEEP_GROUPED"}'::jsonb,'{}'::jsonb,repeat('1',64),now(),actor,actor),
    (domain_policy,org,target,1,'DOMAIN','PUBLISHED','{}'::jsonb,'{}'::jsonb,'{}'::jsonb,'{"applicabilityDomain":{"features":["input:temperature"],"minimum":[20],"maximum":[24]}}'::jsonb,repeat('2',64),now(),actor,actor),
    (quality_policy,org,target,1,'QUALITY','PUBLISHED','{}'::jsonb,'{}'::jsonb,'{}'::jsonb,'{"defaultTrustLevel":"MEDIUM"}'::jsonb,repeat('3',64),now(),actor,actor)
  ON CONFLICT (organization_id,target_id,kind,version_no) DO NOTHING;
  INSERT INTO ai.source_mapping_version(id,organization_id,target_id,target_version_id,source_type,version_no,status,mapping_jsonb,mapping_hash,published_at,created_by)
  VALUES
    (dc_mapping,org,target,target_version,'DATA_CENTER',1,'PUBLISHED','{"targetFieldCode":"TEST.RESULT.VALUE","sourceFieldCode":"R10_NO_FORMULA_GLOSS"}'::jsonb,repeat('4',64),now(),actor),
    (exp_mapping,org,target,target_version,'EXPERIMENT',1,'PUBLISHED','{"targetFieldCode":"TEST.RESULT.VALUE","sourceFieldCode":"R10_NO_FORMULA_GLOSS"}'::jsonb,repeat('5',64),now(),actor)
  ON CONFLICT (organization_id,target_version_id,source_type,version_no) DO NOTHING;
  INSERT INTO ai.training_snapshot(id,organization_id,target_version_id,input_scheme_id,material_dictionary_version_id,modeling_policy_version_id,status,sample_count,validation_groups_jsonb,manifest_jsonb,snapshot_hash,object_prefix,frozen_by,frozen_at,target_id,source_mapping_versions_jsonb,target_definition_jsonb,input_scheme_jsonb,material_dictionary_jsonb,preprocessing_jsonb,training_policy_jsonb,authorization_scope_jsonb,fact_high_watermark,data_nature,business_fingerprint)
  SELECT snapshot,org,target_version,scheme,dictionary,train_policy,'FROZEN',36,
         '{"formulaLineage":"r10-noformula","sourceContext":"r10-noformula"}'::jsonb,
         '{"snapshotHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'::jsonb,
         repeat('a',64),'r10-noformula',actor,now(),target,
         jsonb_build_array(dc_mapping::text,exp_mapping::text),
         '{"id":"noformula-target-v1","version":1,"code":"R10_NO_FORMULA_GLOSS","valueType":"CONTINUOUS","unit":"GU","classes":[],"observationSemantics":{}}'::jsonb,
         '{"id":"noformula-scheme-v1","version":1,"sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","targetVersionId":"noformula-target-v1","fields":[{"fieldVersionId":"temperature-v1","code":"temperature","valueType":"NUMBER","required":true,"acquisitionTiming":"PRE_EXPERIMENT","encoding":{}}]}'::jsonb,
         jsonb_build_object('id',d.id::text,'version',d.version_no,'sha256',d.dictionary_hash,'materials',d.vocabulary_jsonb,'encoder',d.encoder_jsonb),'{}'::jsonb,'{"candidateAlgorithms":["RANDOM_FOREST"],"replicateHandling":"KEEP_GROUPED"}'::jsonb,
         '{"organizationId":"00000000-0000-0000-0000-000000000001","dataNature":"SYNTHETIC","synthetic":true}'::jsonb,0,'SYNTHETIC',repeat('6',64)
  FROM ai.material_dictionary_version d WHERE d.organization_id=org AND d.id=dictionary
  ON CONFLICT (organization_id,id) DO NOTHING;
  INSERT INTO ai.training_job(id,organization_id,training_snapshot_id,status,idempotency_key,request_hash,priority,max_attempts,attempt_count,next_attempt_at,revision,requested_by,created_at,started_at,finished_at,target_id,target_version_id,modeling_policy_version_id,seed,current_stage,progress,fencing_generation,error_jsonb,business_key)
  VALUES(job,org,snapshot,'SUCCEEDED','r10-noformula',repeat('7',64),100,3,1,now(),0,actor,now(),now(),now(),target,target_version,train_policy,37,'SUCCEEDED',100,0,'{}'::jsonb,repeat('7',64))
  ON CONFLICT (organization_id,id) DO NOTHING;
  INSERT INTO ai.artifact(id,organization_id,artifact_type,training_snapshot_id,object_key,sha256,size_bytes,metadata_jsonb,media_type,content_addressed)
  VALUES(artifact,org,'MODEL_BUNDLE',snapshot,'ai/r10-noformula/model.zip',bundle_sha,0,'{"synthetic":true,"marker":"R10_NO_FORMULA"}'::jsonb,'application/zip',true)
  ON CONFLICT (organization_id,object_key) DO NOTHING;
  INSERT INTO ai.model_version(id,organization_id,target_id,target_version_id,input_scheme_id,training_snapshot_id,training_job_id,model_artifact_id,model_type,status,metrics_jsonb,applicability_domain_jsonb,model_card_jsonb,contract_version,model_hash,revision,created_by,version_no,data_nature,production_eligible,comparison_status,rejection_reasons_jsonb,domain_policy_version_id)
  VALUES(model,org,target,target_version,scheme,snapshot,job,artifact,'RANDOM_FOREST','ACTIVE',
         '{"selectedAlgorithm":"RANDOM_FOREST","mae":8.104169206372335}'::jsonb,
         '{"method":"ROBUST_FEATURE_RANGE","featureCount":1,"minimum":[20],"maximum":[24],"features":["input:temperature"]}'::jsonb,
         '{"synthetic":true,"syntheticMarker":"R10_NO_FORMULA","target":{"code":"R10_NO_FORMULA_GLOSS","valueType":"CONTINUOUS","unit":"GU"}}'::jsonb,
         'formula-model.v2',bundle_sha,0,actor,1,'SYNTHETIC',false,'NOT_COMPARABLE','[]'::jsonb,domain_policy)
  ON CONFLICT (organization_id,id) DO NOTHING;
  UPDATE ai.prediction_target SET current_version_id=target_version,current_input_scheme_id=scheme,current_quality_policy_version_id=quality_policy,current_domain_policy_version_id=domain_policy,status='ACTIVE',revision=revision+1,updated_by=actor,updated_at=now() WHERE organization_id=org AND id=target;
END $$;
COMMIT;
