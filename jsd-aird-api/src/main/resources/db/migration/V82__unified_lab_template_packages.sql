-- Immutable, portable packages; workbook JSON is inline so installation needs no object store.
CREATE TABLE IF NOT EXISTS tpl.unified_lab_template_package (
    template_code varchar(80) PRIMARY KEY,
    package_hash char(64) NOT NULL,
    payload_jsonb jsonb NOT NULL,
    installed_at timestamptz NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION tpl.install_unified_lab_templates(target_org uuid,target_actor uuid)
RETURNS integer LANGUAGE plpgsql AS $install$
DECLARE p record; new_template_id uuid; new_version_id uuid; b jsonb; n integer:=0;
BEGIN
    IF NOT EXISTS(SELECT 1 FROM iam.app_user WHERE id=target_actor AND organization_id=target_org) THEN
        RAISE EXCEPTION 'Template installer actor must belong to target organization';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('unified-lab:'||target_org::text,0));
    FOR p IN SELECT * FROM tpl.unified_lab_template_package ORDER BY template_code LOOP
        INSERT INTO tpl.template(id,organization_id,template_code,name,format,created_by)
        VALUES(gen_random_uuid(),target_org,p.template_code,p.payload_jsonb->>'name','XLSX',target_actor)
        ON CONFLICT(organization_id,template_code) DO NOTHING;
        SELECT id INTO new_template_id FROM tpl.template WHERE organization_id=target_org AND template_code=p.template_code;
        SELECT id INTO new_version_id FROM tpl.template_version WHERE template_id=new_template_id AND version_no=1;
        IF new_version_id IS NOT NULL THEN
            IF (SELECT layout_summary_jsonb->>'seedPackageHash' FROM tpl.template_version WHERE id=new_version_id) IS DISTINCT FROM p.package_hash::text THEN
                RAISE EXCEPTION 'Unified template % version 1 has different content; publish a new version instead',p.template_code;
            END IF;
            CONTINUE;
        END IF;
        new_version_id:=gen_random_uuid();
        INSERT INTO tpl.template_version(id,template_id,version_no,status,schema_jsonb,layout_summary_jsonb,
          editor_snapshot_hash,snapshot_kind,editor_app_version,plugin_manifest_hash,snapshot_format_version,
          schema_hash,mapping_hash,data_hash,workspace_hash,created_by,published_at)
        VALUES(new_version_id,new_template_id,1,'PUBLISHED',p.payload_jsonb->'schema',
          (p.payload_jsonb->'layout')||jsonb_build_object('seedPackageHash',p.package_hash),
          p.payload_jsonb->>'snapshotHash','UNIVER_WORKBOOK','univer-1.2.0',p.payload_jsonb->>'pluginHash',3,
          p.payload_jsonb->>'schemaHash',p.payload_jsonb->>'mappingHash',p.payload_jsonb->>'dataHash',p.payload_jsonb->>'workspaceHash',target_actor,now());
        FOR b IN SELECT value FROM jsonb_array_elements(p.payload_jsonb->'mapping') LOOP
            INSERT INTO tpl.template_mapping(id,template_version_id,binding_id,parent_binding_id,format,field_code,data_path,
              binding_role,mapping_kind,repeat_axis,record_height,record_width,record_stride,locator_type,locator_jsonb,
              sync_direction,primary_binding,binding_status,diagnostic_jsonb,termination_jsonb,last_validated_at)
            VALUES(gen_random_uuid(),new_version_id,b->>'bindingId',b->>'parentBindingId','XLSX',b->>'fieldCode',b->>'dataPath',
              b->>'role',b->>'mappingKind',b->>'repeatAxis',coalesce((b->>'recordHeight')::int,1),coalesce((b->>'recordWidth')::int,1),coalesce((b->>'recordStride')::int,1),
              b->>'locatorType',b->'locator',b->>'syncDirection',true,'VALID',
              coalesce(b->'diagnostic','{}'::jsonb)||(b-'locator'-'diagnostic'),coalesce(b->'termination','{}'::jsonb),now());
        END LOOP;
        INSERT INTO tpl.template_import_contract(template_version_id,import_contract_version,layout_structure_version,contract_hash,contract_jsonb,created_by)
        VALUES(new_version_id,9,(p.payload_jsonb#>>'{contract,layoutStructureVersion}')::int,p.payload_jsonb#>>'{contract,contractHash}',p.payload_jsonb->'contract',target_actor);
        INSERT INTO tpl.template_version_review(version_id,organization_id,review_status,reviewed_by,reviewed_at,review_comment)
        VALUES(new_version_id,target_org,'APPROVED',target_actor,now(),'Reviewed unified laboratory template package');
        UPDATE tpl.template SET current_published_version_id=new_version_id,updated_at=now() WHERE id=new_template_id;
        n:=n+1;
    END LOOP;
    RETURN n;
END $install$;
