package com.jsd.aird.shared.resource;

import com.jsd.aird.shared.error.ApiException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;

@Timeout(5)
class OperationBudgetAsyncTest {
    @Test void queuedWorkCannotRenewAnExpiredDeadline() throws Exception {
        try(var executor=Executors.newSingleThreadExecutor()) {
            var blocker=new CountDownLatch(1);
            executor.submit(()->{blocker.await();return null;});
            var called=new AtomicBoolean();
            BudgetedTask<Boolean> task;
            try(var parent=OperationBudget.open(OperationBudget.Kind.READ,30)) {
                task=BudgetedTask.submit(executor,10000,()->{called.set(true);return true;});
            }
            Thread.sleep(70);
            blocker.countDown();
            assertThatThrownBy(task::await).isInstanceOf(ApiException.class);
            executor.submit(()->{}).get();
            assertThat(called).isFalse();
        }
    }

    @Test void childReservationsCannotReleaseTheParentsReservations() throws Exception {
        long baseline=WorkingMemory.reserved();
        var childStarted=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            BudgetedTask<Boolean> child;
            try(var parent=OperationBudget.open(OperationBudget.Kind.READ,2000)) {
                OperationBudget.hold(WorkingMemory.reserve(100));
                child=BudgetedTask.submit(executor,1500,()->{
                    assertThat(OperationBudget.kind()).isEqualTo(OperationBudget.Kind.READ);
                    assertThat(OperationBudget.remainingMillis()).isLessThanOrEqualTo(1500);
                    OperationBudget.hold(WorkingMemory.reserve(200));
                    childStarted.countDown();release.await();return true;
                });
                assertThat(childStarted.await(1,TimeUnit.SECONDS)).isTrue();
                assertThat(WorkingMemory.reserved()).isEqualTo(baseline+300);
            }
            assertThat(WorkingMemory.reserved()).isEqualTo(baseline+200);
            release.countDown();assertThat(child.await()).isTrue();
            assertThat(WorkingMemory.reserved()).isEqualTo(baseline);
            assertThat(executor.submit(OperationBudget::active).get()).isFalse();
        } finally {release.countDown();}
    }

    @Test void cancellationInterruptsTheWorkerAndReleasesItsReservation() throws Exception {
        long baseline=WorkingMemory.reserved();var started=new CountDownLatch(1);var exited=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var task=BudgetedTask.submit(executor,2000,()->{
                OperationBudget.hold(WorkingMemory.reserve(200));started.countDown();
                try{new CountDownLatch(1).await();return true;}finally{exited.countDown();}
            });
            assertThat(started.await(1,TimeUnit.SECONDS)).isTrue();task.cancel();
            assertThat(exited.await(1,TimeUnit.SECONDS)).isTrue();executor.submit(()->{}).get();
            assertThat(WorkingMemory.reserved()).isEqualTo(baseline);
        }
    }
}
