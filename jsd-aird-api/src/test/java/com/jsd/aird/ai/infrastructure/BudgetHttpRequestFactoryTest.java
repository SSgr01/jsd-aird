package com.jsd.aird.ai.infrastructure;

import com.jsd.aird.shared.resource.OperationBudget;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;

@Timeout(5)
class BudgetHttpRequestFactoryTest {
    @Test void retrievalHttpRequestHonorsRemainingBudgetInsteadOfProviderTimeout() throws Exception {
        var release=new CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/wait",exchange->{
            try{release.await(3,TimeUnit.SECONDS);exchange.sendResponseHeaders(200,-1);}
            catch(InterruptedException ignored){Thread.currentThread().interrupt();}finally{exchange.close();}
        });
        server.start();
        var client=RestClient.builder().requestFactory(new BudgetHttpRequestFactory(Duration.ofSeconds(30))).build();
        try {
            long started=System.nanoTime();
            try(var budget=OperationBudget.open(OperationBudget.Kind.READ,150)) {
                assertThatThrownBy(()->client.get().uri("http://127.0.0.1:"+server.getAddress().getPort()+"/wait").retrieve().toBodilessEntity())
                    .isInstanceOf(RuntimeException.class);
            }
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isLessThan(1800);
        } finally {release.countDown();server.stop(0);}
    }
}
