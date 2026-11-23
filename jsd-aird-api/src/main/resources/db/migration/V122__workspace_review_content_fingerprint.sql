ALTER TABLE ops.workspace_review ADD COLUMN resource_content_hash text;

-- Shared by detail and list checks. Audit fields, lock counters and background
-- processing/publication states are not part of the submitted business content.
CREATE FUNCTION ops.workspace_review_content_hash(resource_type text, org uuid, resource uuid)
RETURNS text LANGUAGE plpgsql STABLE AS $$
DECLARE body jsonb;
BEGIN
    CASE resource_type
    WHEN 'PROJECT_DOCUMENT' THEN
        SELECT jsonb_build_array(d.title,d.format,d.file_object_id,
                   d.content_snapshot,d.content_schema,d.content_mapping,d.content_data)
          INTO body FROM mdm.project_document d JOIN mdm.project p ON p.id=d.project_id
         WHERE p.organization_id=org AND d.id=resource AND NOT d.deleted;
    WHEN 'QUALITY_RECORD' THEN
        SELECT jsonb_build_array(r.business_type,r.business_no,r.display_name,
                   r.data_jsonb - 'status',r.workbook_snapshot_jsonb,r.source_file_id,
                   r.project_id,r.stage_id,r.task_id)
          INTO body FROM quality.data_record r
         WHERE r.organization_id=org AND r.id=resource AND NOT r.deleted;
    WHEN 'PRODUCTION_ORDER' THEN
        SELECT jsonb_build_array(p.order_no,p.template_version_id,p.product_id,
                   p.quantity,p.unit_code,p.planned_date,p.owner_id,
                   p.instance_schema_jsonb,p.instance_mapping_jsonb,p.draft_data_jsonb,
                   p.draft_editor_snapshot_hash)
          INTO body FROM mfg.production_order p WHERE p.organization_id=org AND p.id=resource;
    WHEN 'PRODUCTION_UPLOAD' THEN
        SELECT jsonb_build_array(p.file_id,p.production_name,p.order_no,p.product_name,
                   p.category,p.manufacture_date,p.project_id,p.stage_id,p.task_id,p.workbook_snapshot_jsonb)
          INTO body FROM mfg.production_upload p WHERE p.organization_id=org AND p.id=resource;
    WHEN 'KNOWLEDGE_VERSION' THEN
        SELECT jsonb_build_array(d.title,d.library_scope,d.category_id,
                   (SELECT coalesce(jsonb_agg(t.display_name ORDER BY t.display_name),'[]'::jsonb)
                      FROM kb.document_tag dt JOIN kb.tag t ON t.id=dt.tag_id WHERE dt.document_id=d.id),
                   r.id,r.parse_run_id,r.confirmed_document_jsonb,r.excluded_review_node_ids,
                   (SELECT coalesce(jsonb_agg(jsonb_build_array(c.source_table_id,c.row_no,c.column_no,c.confirmed_value)
                                             ORDER BY c.source_table_id,c.row_no,c.column_no),'[]'::jsonb)
                      FROM kb.document_review_table_cell_patch c WHERE c.review_revision_id=r.id),
                   (SELECT coalesce(jsonb_agg(jsonb_build_array(s.source_table_id,s.row_no,s.excluded,s.header)
                                             ORDER BY s.source_table_id,s.row_no),'[]'::jsonb)
                      FROM kb.document_review_table_row_state s WHERE s.review_revision_id=r.id),
                   (SELECT coalesce(jsonb_agg(jsonb_build_array(i.parse_issue_id,i.status,i.resolution)
                                             ORDER BY i.parse_issue_id),'[]'::jsonb)
                      FROM kb.document_review_issue_state i WHERE i.review_revision_id=r.id))
          INTO body FROM kb.document_version v JOIN kb.document d ON d.id=v.document_id
          LEFT JOIN LATERAL (SELECT rr.* FROM kb.document_review_revision rr
                             WHERE rr.document_version_id=v.id ORDER BY rr.revision_no DESC LIMIT 1) r ON true
         WHERE d.organization_id=org AND v.id=resource;
    ELSE
        RAISE EXCEPTION 'Unknown review resource type: %',resource_type;
    END CASE;
    RETURN encode(sha256(convert_to(body::text,'UTF8')),'hex');
END;
$$;

-- No historical content snapshot exists for stale approvals. Only backfill
-- approvals still valid under the old rule; never resurrect stale approvals.
UPDATE ops.workspace_review w
   SET resource_content_hash=ops.workspace_review_content_hash(w.resource_type,w.organization_id,w.resource_id)
 WHERE w.resource_updated_at = CASE w.resource_type
    WHEN 'PROJECT_DOCUMENT' THEN (SELECT d.updated_at FROM mdm.project_document d JOIN mdm.project p ON p.id=d.project_id
                                  WHERE p.organization_id=w.organization_id AND d.id=w.resource_id AND NOT d.deleted)
    WHEN 'QUALITY_RECORD' THEN (SELECT r.updated_at FROM quality.data_record r
                                WHERE r.organization_id=w.organization_id AND r.id=w.resource_id AND NOT r.deleted)
    WHEN 'PRODUCTION_ORDER' THEN (SELECT p.updated_at FROM mfg.production_order p
                                  WHERE p.organization_id=w.organization_id AND p.id=w.resource_id)
    WHEN 'PRODUCTION_UPLOAD' THEN (SELECT p.updated_at FROM mfg.production_upload p
                                   WHERE p.organization_id=w.organization_id AND p.id=w.resource_id)
    WHEN 'KNOWLEDGE_VERSION' THEN (SELECT coalesce((SELECT r.updated_at FROM kb.document_review_revision r
                                                   WHERE r.document_version_id=v.id ORDER BY r.revision_no DESC LIMIT 1),v.updated_at)
                                   FROM kb.document_version v JOIN kb.document d ON d.id=v.document_id
                                  WHERE d.organization_id=w.organization_id AND v.id=w.resource_id)
 END;
