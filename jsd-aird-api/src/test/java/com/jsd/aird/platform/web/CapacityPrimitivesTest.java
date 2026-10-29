package com.jsd.aird.platform.web;

import com.jsd.aird.shared.resource.*;
import com.jsd.aird.shared.error.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class CapacityPrimitivesTest {
    @Test void workingMemoryIsSharedUntilTheOutermostOperationExits()throws Exception{
        long baseline=WorkingMemory.reserved();
        try(var outer=OperationBudget.open(OperationBudget.Kind.READ,1000)){
            try(var inner=OperationBudget.open(OperationBudget.Kind.HEAVY,5000)){OperationBudget.hold(WorkingMemory.reserve(100));}
            assertThat(WorkingMemory.reserved()).isEqualTo(baseline+100);
        }
        assertThat(WorkingMemory.reserved()).isEqualTo(baseline);
        assertThatThrownBy(()->WorkingMemory.reserve(WorkingMemory.limit()+1)).isInstanceOf(ApiException.class);
    }
    @Test void preparedDownloadEnforcesItsLimitBeforeHeadersAndReleasesDiskOnFailure()throws Exception{
        var root=java.nio.file.Files.createTempDirectory("capacity-export-test-");
        try{
            var space=new com.jsd.aird.shared.resource.TemporarySpace(1024,root);
            assertThatThrownBy(()->com.jsd.aird.shared.resource.PreparedFile.create(space,16,out->out.write(new byte[17]))).isInstanceOf(ApiException.class);
            assertThat(space.reserved()).isZero();
            try(var prepared=com.jsd.aird.shared.resource.PreparedFile.create(space,16,out->out.write(new byte[16]))){assertThat(prepared.size()).isEqualTo(16);assertThat(space.reserved()).isEqualTo(16);}
            assertThat(space.reserved()).isZero();try(var paths=java.nio.file.Files.list(root)){assertThat(paths.count()).isZero();}
        }finally{java.nio.file.Files.deleteIfExists(root);}
    }
    @TempDir Path root;
    @Test void nestedBudgetCannotRenewAnExpiredCaller()throws Exception{
        try(var outer=OperationBudget.open(OperationBudget.Kind.WRITE,30)){
            Thread.sleep(50);
            try(var nested=OperationBudget.open(OperationBudget.Kind.HEAVY,120000)){
                assertThatThrownBy(OperationBudget::check).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.errorCode()).isEqualTo(ApiErrorCode.OPERATION_TIMEOUT));
            }
        }
        assertThatCode(OperationBudget::check).doesNotThrowAnyException();
    }
    @Test void boundedGateRejectsAndReleasesExactlyOnce(){
        var gate=new BoundedGate(1,0);var lease=gate.enter(0);
        assertThatThrownBy(()->gate.enter(0)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.errorCode()).isEqualTo(ApiErrorCode.SERVER_BUSY));
        lease.close();lease.close();assertThat(gate.active()).isZero();
        try(var next=gate.enter(0)){assertThat(gate.active()).isEqualTo(1);}
        assertThat(gate.rejections()).isEqualTo(1);
    }
    @Test void waitingCapacityIsBoundedAndInterruptedWaitersDoNotLeak()throws Exception{
        var gate=new BoundedGate(1,1);try(var held=gate.enter(0);var pool=Executors.newSingleThreadExecutor()){
            var waiting=pool.submit(()->{try(var ignored=gate.enter(10000)){return true;}});
            Thread.sleep(50);long started=System.nanoTime();assertThatThrownBy(()->gate.enter(500)).isInstanceOf(ApiException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isLessThan(300);
            waiting.cancel(true);pool.shutdown();assertThat(pool.awaitTermination(2,TimeUnit.SECONDS)).isTrue();
        }
        try(var again=gate.enter(0)){assertThat(gate.active()).isEqualTo(1);}
    }
    @Test void streamStopsAtRealLimitWithoutTrustingLength()throws Exception{
        var output=new ByteArrayOutputStream();var input=new ByteArrayInputStream(new byte[100000]);
        assertThatThrownBy(()->BoundedStreams.copy(input,output,70000,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.errorCode()).isEqualTo(ApiErrorCode.PAYLOAD_TOO_LARGE));
        assertThat(output.size()).isEqualTo(65536);assertThat(input.available()).isZero();
    }
    @Test void exactLimitAndDigestArePreserved()throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");var output=new ByteArrayOutputStream();var bytes="actual-file".getBytes();
        assertThat(BoundedStreams.copy(new ByteArrayInputStream(bytes),output,bytes.length,digest)).isEqualTo(bytes.length);
        assertThat(digest.digest()).isEqualTo(MessageDigest.getInstance("SHA-256").digest(bytes));assertThat(output.toByteArray()).isEqualTo(bytes);
    }
    @Test void diskReservationsHaveAnAggregateLimitAndCloseIsIdempotent(){
        var space=new TemporarySpace(100,root);var first=space.reserve(60);
        assertThatThrownBy(()->space.reserve(41)).isInstanceOf(ApiException.class);
        try(var second=space.reserve(40)){assertThat(space.reserved()).isEqualTo(100);}
        first.close();first.close();assertThat(space.reserved()).isZero();
    }
}
