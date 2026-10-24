package com.jsd.aird.tpl.application;

import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.*;
import com.jsd.aird.tpl.application.port.*;
import com.jsd.aird.tpl.domain.TemplateStatus;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TemplateReviewConsistencyTest {
    private final TemplateRepository templates=mock(TemplateRepository.class);
    private final TemplateVersionReviewRepository reviews=mock(TemplateVersionReviewRepository.class);
    private final TemplateVersionReviewService service=new TemplateVersionReviewService(templates,reviews);
    private final UUID org=UUID.randomUUID(), id=UUID.randomUUID(), actor=UUID.randomUUID();
    private final TemplateRepository.TemplateWorkspace workspace=mock(TemplateRepository.TemplateWorkspace.class);

    @BeforeEach void setup(){
        ActorContext.set(new Actor(org,actor,"reviewer"));
        when(templates.findWorkspace(org,id)).thenReturn(Optional.of(workspace));
        when(workspace.status()).thenReturn(TemplateStatus.DRAFT);
        when(workspace.lockVersion()).thenReturn(12L);
        when(reviews.ensure(org,id)).thenReturn(review("SUBMITTED",5));
        when(reviews.events(org,id)).thenReturn(List.of());
    }
    @AfterEach void cleanup(){ActorContext.clear();}

    @Test void refusesApprovalOfAnUnseenDocumentRevision(){
        assertConflict(()->service.approve(id,11,5,null));
        verify(reviews,never()).transition(any(),any(),anyLong(),anyString(),anyString(),any(),any());
        verify(templates).lockWorkspace(org,id);
    }
    @Test void refusesApprovalFromAnOlderReviewCycleEvenWhenStatusMatches(){
        assertConflict(()->service.approve(id,12,1,null));
        verify(reviews,never()).transition(any(),any(),anyLong(),anyString(),anyString(),any(),any());
    }
    @Test void passesBothValidatedRevisionsThroughTheSuccessfulApproval(){
        when(reviews.transition(org,id,5,"SUBMITTED","APPROVED",actor,null)).thenReturn(review("APPROVED",6));
        var view=service.approve(id,12,5,null);
        assertThat(view.status()).isEqualTo("APPROVED"); assertThat(view.lockVersion()).isEqualTo(6);
    }
    @Test void submitAlsoRejectsStaleWorkspaceOrReview(){
        when(reviews.ensure(org,id)).thenReturn(review("NOT_SUBMITTED",0));
        assertConflict(()->service.submit(id,11,0,false));
    }
    @Test void rejectionCannotApplyToANewerReviewCycle(){
        assertConflict(()->service.reject(id,12,4,"需要修改"));
    }
    @Test void pendingAndApprovedDraftsCannotBeSavedInPlace(){
        for(String status:List.of("SUBMITTED","APPROVED")){
            when(reviews.ensure(org,id)).thenReturn(review(status,5));
            assertThatThrownBy(()->service.requireEditable(org,id)).isInstanceOfSatisfying(ApiException.class,
                    error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.REVIEW_STATE_CONFLICT));
        }
    }
    @Test void rejectedDraftCanBeEditedAgain(){
        when(reviews.ensure(org,id)).thenReturn(review("REJECTED",6)); service.requireEditable(org,id);
        verify(templates).lockWorkspace(org,id);
    }
    @Test void publicationRechecksApprovalWhileHoldingTheWorkspaceLock(){
        when(reviews.ensure(org,id)).thenReturn(review("SUBMITTED",5));
        assertThatThrownBy(()->service.requireApproved(org,id)).isInstanceOfSatisfying(ApiException.class,
                error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.REVIEW_REQUIRED));
        verify(templates).lockWorkspace(org,id);
    }
    private TemplateVersionReviewRepository.Review review(String status,long revision){
        return new TemplateVersionReviewRepository.Review(id,status,null,null,null,null,null,revision);
    }
    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,
                error->assertThat(error.errorCode()).isEqualTo(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT));
    }
}
