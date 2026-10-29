package com.jsd.aird.rnd.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ops.application.port.*;
import com.jsd.aird.rnd.application.port.ExperimentImportRepository;
import com.jsd.aird.rnd.domain.ExperimentModels.*;
import com.jsd.aird.shared.security.*;
import com.jsd.aird.tpl.application.port.OfficeStructureParser;
import com.jsd.aird.tpl.domain.TemplateFormat;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExperimentImportResourceTest {
    private final UUID org=UUID.randomUUID(),user=UUID.randomUUID(),job=UUID.randomUUID(),file=UUID.randomUUID();
    private final AtomicBoolean committing=new AtomicBoolean();
    private final ExperimentImportRepository imports=mock(ExperimentImportRepository.class);
    private final ExperimentService experiments=mock(ExperimentService.class);
    private ExperimentImportService service;
    private final Actor actor=new Actor(org,user,"capacity-test");
    @BeforeEach void setup(){
        var json=new ObjectMapper();var parser=mock(OfficeStructureParser.class);var files=mock(FileStorageFacade.class);
        when(parser.format()).thenReturn(TemplateFormat.DOCX);
        when(parser.parse(any())).thenAnswer(call->{assertThat(committing.get()).isFalse();return new OfficeStructureParser.ParseResult(json.createObjectNode(),json.createObjectNode().put("id","isolated"),List.of());});
        when(files.open(org,file)).thenReturn(new FileStorageFacade.StoredFile(file,"fixture.docx","application/octet-stream",1,"hash",new ByteArrayInputStream(new byte[]{1})));
        when(imports.isParsing(org,job)).thenReturn(true);
        when(imports.find(org,job)).thenReturn(Optional.empty());
        var summary=mock(Summary.class);when(summary.id()).thenReturn(UUID.randomUUID());
        when(experiments.create(any())).thenAnswer(call->{assertThat(committing.get()).isTrue();return summary;});
        var detail=mock(Detail.class);when(detail.currentVersionId()).thenReturn(UUID.randomUUID());when(experiments.detail(summary.id())).thenReturn(detail);
        service=new ExperimentImportService(imports,experiments,files,List.of(parser),List.of(),mock(com.jsd.aird.kb.domain.DocumentParser.class),json,mock(OpsAsyncFacade.class),mock(TemplateOfficeNormalizationFacade.class),mock(com.jsd.aird.tpl.api.TemplateOfficeFacade.class));
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call->{committing.set(true);return new SimpleTransactionStatus();});
        doAnswer(call->{committing.set(false);return null;}).when(manager).commit(any());
        doAnswer(call->{committing.set(false);return null;}).when(manager).rollback(any());
        service.configureTransactions(manager);ActorContext.set(actor);
        com.jsd.aird.iam.api.AuthorizationService authorization=check->{assertThat(committing.get()).isTrue();assertThat(com.jsd.aird.iam.application.IamPermissionCatalog.definitions().stream().anyMatch(permission->permission.code().equals(check.permissionCode()))).isTrue();return new com.jsd.aird.iam.api.PermissionDecision(true,check.permissionCode(),"ALLOW","ALL","TEST");};
        ReflectionTestUtils.setField(service,"authorization",authorization);
    }
    @AfterEach void clear(){ActorContext.clear();}
    private ExperimentImportService.Command command(){return new ExperimentImportService.Command(file,"fixture.docx","hash",TemplateFormat.DOCX,null,null,null,null,null,LocalDate.now(),"ALL");}
    @Test void preparationDoesNotOwnTheCommitTransaction(){
        assertThat(service.processQueued(job,command(),actor)).isNotNull();assertThat(committing.get()).isFalse();
        verify(imports).complete(eq(job),any(),any());
    }
    @Test void cancellationImmediatelyBeforeCommitDoesNotCreateAnExperiment(){
        when(imports.isParsing(org,job)).thenReturn(true,true,false);
        assertThat(service.processQueued(job,command(),actor)).isNull();verify(experiments,never()).create(any());verify(imports,never()).complete(any(),any(),any());
    }
}
