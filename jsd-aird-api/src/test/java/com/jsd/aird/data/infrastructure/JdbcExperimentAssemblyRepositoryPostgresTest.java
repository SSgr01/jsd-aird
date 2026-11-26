package com.jsd.aird.data.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.platform.web.IsolatedPostgres;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_DATASOURCE_URL",matches="jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/.*")
class JdbcExperimentAssemblyRepositoryPostgresTest {
    @Test void loadsSourceWithoutARevisionColumnAndDetectsSubsequentSourceWrites() throws Exception {
        // Load the driver and establish the first connection before the small test pool's timeout starts.
        var env=System.getenv();
        try(var connection=java.sql.DriverManager.getConnection(env.get("JSD_AIRD_DATASOURCE_URL"),
                env.get("JSD_AIRD_DATASOURCE_USERNAME"),env.get("JSD_AIRD_DATASOURCE_PASSWORD"))) {
            assertThat(connection.isValid(5)).isTrue();
        }
        try(var db=new IsolatedPostgres(Map.of("data.import_job","job","data.source_anchor","anchor","data.data_record","record"))) {
            db.table("job","id uuid PRIMARY KEY,organization_id uuid,source_file_id uuid,source_file_name text,source_sha256 text,template_version_id uuid,import_contract_version int,contract_hash text,status text,import_purpose text,target_experiment_category_id uuid,created_by uuid,created_at timestamptz");
            db.table("anchor","record_id uuid,import_job_id uuid,field_code text,binding_id text,value_path text,label_path text,sheet_id text,sheet_name text,row_number int,column_number int,column_name text,cell_address text,raw_value_jsonb jsonb");
            db.table("record","id uuid,organization_id uuid,import_job_id uuid,record_key text,sheet_id text,sheet_name text,source_row_number int,effective_data_jsonb jsonb,record_index int");
            var org=UUID.randomUUID();var id=UUID.randomUUID();
            db.jdbc.update("INSERT INTO data.import_job VALUES(?,?,?,'UA-3160B.xlsx','sha',?,9,'contract','COMPLETED','EXPERIMENT_DRAFT',NULL,?,now())",id,org,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
            var repository=new JdbcExperimentAssemblyRepository(db.jdbc,new ObjectMapper());
            var original=repository.load(org,id);
            assertThat(original.revision()).isNotBlank();
            assertThat(repository.load(org,id)).isEqualTo(original);
            db.jdbc.update("UPDATE data.import_job SET contract_hash='changed' WHERE id=?",id);
            assertThat(repository.load(org,id).revision()).isNotEqualTo(original.revision());
        }
    }
}
