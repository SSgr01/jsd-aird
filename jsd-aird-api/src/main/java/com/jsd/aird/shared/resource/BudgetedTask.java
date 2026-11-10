package com.jsd.aird.shared.resource;

import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import java.util.concurrent.*;

/** Queue time is part of the deadline; cancellation interrupts the actual worker. */
public final class BudgetedTask<T> {
    private final FutureTask<T> future;
    private final long deadline;
    private BudgetedTask(FutureTask<T> future, long deadline) { this.future=future; this.deadline=deadline; }
    public static <T> BudgetedTask<T> submit(Executor executor, long millis, Callable<T> work) {
        try (var budget=OperationBudget.open(OperationBudget.kind(),millis)) {
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(OperationBudget.remainingMillis());
            var future=new FutureTask<>(OperationBudget.propagate(work));
            executor.execute(future);
            return new BudgetedTask<>(future,deadline);
        }
    }
    public T await() {
        try {
            return future.get(Math.max(0,Math.min(deadline-System.nanoTime(),
                TimeUnit.MILLISECONDS.toNanos(Math.min(Integer.MAX_VALUE,OperationBudget.remainingMillis())))),TimeUnit.NANOSECONDS);
        } catch(InterruptedException error) {
            future.cancel(true);Thread.currentThread().interrupt();throw new ApiException(ApiErrorCode.OPERATION_TIMEOUT);
        } catch(TimeoutException error) {
            future.cancel(true);throw new ApiException(ApiErrorCode.OPERATION_TIMEOUT);
        } catch(ExecutionException error) {
            if(error.getCause() instanceof RuntimeException failure)throw failure;
            throw new IllegalStateException("Background operation failed",error.getCause());
        }
    }
    public void cancel(){future.cancel(true);}
}
