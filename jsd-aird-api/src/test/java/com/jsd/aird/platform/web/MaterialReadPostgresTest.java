package com.jsd.aird.platform.web;

import com.jsd.aird.mdm.infrastructure.persistence.MaterialMapper;
import com.jsd.aird.shared.UuidTypeHandler;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Read the actual mapper into its record constructor, including joined selections. */
@EnabledIfEnvironmentVariable(named="JSD_AIRD_CONSISTENCY_PG_TEST", matches="true")
class MaterialReadPostgresTest {
    @Test void listLookupAndProjectSelectionRetainAllConstructorColumns() {
        try (var db = new IsolatedPostgres(Map.of("mdm.material", "material", "mdm.project_material", "project_material"))) {
            db.table("material", "id uuid PRIMARY KEY,code text,name text,category text,source_category text,source_module text,"
                + "stage text,contact_person text,status text,description text,version bigint,created_at timestamptz,updated_at timestamptz");
            db.table("project_material", "id uuid PRIMARY KEY,project_id uuid,material_id uuid,version bigint,created_at timestamptz,updated_at timestamptz");
            var id=UUID.randomUUID(); var project=UUID.randomUUID();
            db.jdbc.update("INSERT INTO mdm.material VALUES(?, 'UA-1117', '透明涂层树脂', NULL,NULL,NULL,NULL,NULL,'DRAFT',NULL,1,now(),now())", id);
            db.jdbc.update("INSERT INTO mdm.project_material(id,project_id,material_id) VALUES(?,?,?)", UUID.randomUUID(),project,id);
            var config=new Configuration(); config.setMapUnderscoreToCamelCase(true);
            config.setEnvironment(new Environment("isolated",new SpringManagedTransactionFactory(),db.source));
            config.getTypeHandlerRegistry().register(UUID.class,UuidTypeHandler.class);
            config.addMapper(MaterialMapper.class);
            var mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(MaterialMapper.class);
            assertThat(mapper.list(0,20)).singleElement().satisfies(row -> {
                assertThat(row.id()).isEqualTo(id); assertThat(row.linked()).isFalse();
            });
            assertThat(mapper.findById(id).name()).isEqualTo("透明涂层树脂");
            assertThat(mapper.findByCode("UA-1117").linked()).isFalse();
            assertThat(mapper.listByProject(project,"UA-1117",null,null,0,20)).singleElement()
                .satisfies(row -> assertThat(row.linked()).isTrue());
        }
    }
}
