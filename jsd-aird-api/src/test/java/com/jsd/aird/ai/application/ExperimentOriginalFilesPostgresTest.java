package com.jsd.aird.ai.application;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.ops.api.FileStorageFacade;
import com.jsd.aird.platform.web.IsolatedPostgres;
import com.jsd.aird.shared.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="JSD_AIRD_CONSISTENCY_PG_TEST", matches="true")
class ExperimentOriginalFilesPostgresTest {
    private static UUID id(long n) { return new UUID(0, n); }
    private static ObjectNode ref(UUID experiment, UUID version) {
        return JsonNodeFactory.instance.objectNode().put("type", "EXPERIMENT")
            .put("id", experiment.toString()).put("version_id", version.toString());
    }

    @Test void sourcesAreVersionScopedAndAttachmentsAreCurrentOnlyWithTenantAndDeletionChecks() {
        try (var db = new IsolatedPostgres(Map.of("rnd.experiment", "experiment",
                "rnd.experiment_version", "experiment_version", "rnd.experiment_source_reference", "source_reference",
                "rnd.experiment_attachment", "attachment", "ops.file_object", "file_object"))) {
            db.table("experiment", "id uuid primary key, organization_id uuid, current_version_id uuid, source_file_id uuid, deleted boolean");
            db.table("experiment_version", "id uuid primary key, organization_id uuid, experiment_id uuid, edit_model_jsonb jsonb");
            db.table("source_reference", "organization_id uuid, experiment_id uuid, experiment_version_id uuid, source_file_id uuid");
            db.table("attachment", "experiment_id uuid, file_id uuid");
            db.table("file_object", "id uuid primary key, organization_id uuid, original_name text, content_type text, size_bytes bigint, sha256 text, status text");
            UUID org=id(1), foreign=id(2), experiment=id(3), old=id(4), current=id(5);
            ActorContext.set(new Actor(org,id(6),"reader"));
            try {
                for (int n=10;n<=16;n++) db.jdbc.update("INSERT INTO ops.file_object VALUES (?,?,?,'application/octet-stream',1,'hash',?)",
                    id(n),n==15?foreign:org,"file-"+n,n==16?"DELETED":"ACTIVE");
                db.jdbc.update("INSERT INTO rnd.experiment VALUES (?,?,?,?,false)",experiment,org,current,id(11));
                db.jdbc.update("INSERT INTO rnd.experiment_version VALUES (?,?,?,?::jsonb)",old,org,experiment,"{\"sourceFileId\":\""+id(10)+"\"}");
                db.jdbc.update("INSERT INTO rnd.experiment_version VALUES (?,?,?,?::jsonb)",current,org,experiment,"{}");
                // Duplicate source references must not duplicate files. Other-version/tenant references must not leak.
                for (int n : List.of(10,12,15,16)) db.jdbc.update("INSERT INTO rnd.experiment_source_reference VALUES (?,?,?,?)",org,experiment,old,id(n));
                db.jdbc.update("INSERT INTO rnd.experiment_source_reference VALUES (?,?,?,?)",foreign,experiment,old,id(14));
                db.jdbc.update("INSERT INTO rnd.experiment_source_reference VALUES (?,?,?,?)",org,experiment,current,id(13));
                db.jdbc.update("INSERT INTO rnd.experiment_attachment VALUES (?,?)",experiment,id(14));
                var storage=mock(FileStorageFacade.class);
                var service=new BusinessOriginalFiles(db.jdbc,storage);
                assertThat(service.list(ref(experiment,old))).extracting(BusinessOriginalFiles.Entry::file_id).containsExactly(id(10),id(12));
                assertThat(service.list(ref(experiment,current))).extracting(BusinessOriginalFiles.Entry::file_id).containsExactly(id(11),id(13),id(14));
                assertThatThrownBy(()->service.open(ref(experiment,old),id(14))).hasMessageContaining("不属于");
                assertThatThrownBy(()->service.open(ref(experiment,current),null)).hasMessageContaining("多个");
                verifyNoInteractions(storage);
                // A saved source is authoritative over the current relational fallback.
                db.jdbc.update("UPDATE rnd.experiment_version SET edit_model_jsonb=?::jsonb WHERE id=?","{\"sourceFileId\":\""+id(10)+"\"}",current);
                assertThat(service.list(ref(experiment,current))).extracting(BusinessOriginalFiles.Entry::file_id).containsExactly(id(10),id(13),id(14));
                assertThat(service.list(ref(experiment,id(99)))).isEmpty();
                ActorContext.set(new Actor(foreign,id(6),"reader"));
                assertThat(service.list(ref(experiment,current))).isEmpty();
                ActorContext.set(new Actor(org,id(6),"reader"));
                db.jdbc.update("UPDATE rnd.experiment SET deleted=true WHERE id=?",experiment);
                assertThat(service.list(ref(experiment,current))).isEmpty();
                assertThat(service.list(ref(experiment,old))).isEmpty();
            } finally { ActorContext.clear(); }
        }
    }
}
