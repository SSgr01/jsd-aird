package com.jsd.aird.shared.resource;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Per-instance bounds. Durations are milliseconds, sizes are real bytes. */
@ConfigurationProperties("app.capacity")
public record CapacityProperties(
        @DefaultValue("32") int requests, @DefaultValue("16") int requestQueue,
        @DefaultValue("4") int writes, @DefaultValue("2") int heavy,
        @DefaultValue("4") int heavyQueue, @DefaultValue("16") int streams,
        @DefaultValue("100") long admissionMillis, @DefaultValue("1000") int lockMillis,
        @DefaultValue("5000") int readSqlMillis, @DefaultValue("8000") int writeSqlMillis,
        @DefaultValue("20000") int heavySqlMillis, @DefaultValue("6000") int readTransactionMillis,
        @DefaultValue("10000") int writeTransactionMillis, @DefaultValue("25000") int heavyTransactionMillis,
        @DefaultValue("8000") long readMillis, @DefaultValue("12000") long writeMillis,
        @DefaultValue("120000") long heavyMillis, @DefaultValue("600000") long uploadMillis,
        @DefaultValue("33554432") long jsonBytes, @DefaultValue("67108864") long workspaceJsonBytes,
        @DefaultValue("536870912") long fileBytes, @DefaultValue("8589934592") long temporaryBytes,
        @DefaultValue("100") int pendingJobs, @DefaultValue("20") int organizationPendingJobs) {
    public CapacityProperties {
        if(requests<1 || requestQueue<0 || writes<1 || heavy<1 || heavyQueue<0 || streams<1
                || admissionMillis<0 || lockMillis<1 || readSqlMillis<1 || writeSqlMillis<1 || heavySqlMillis<1
                || readTransactionMillis<1 || writeTransactionMillis<1 || heavyTransactionMillis<1
                || readMillis<1 || writeMillis<1 || heavyMillis<1 || uploadMillis<1
                || jsonBytes<1 || workspaceJsonBytes<jsonBytes || fileBytes<1 || temporaryBytes<fileBytes
                || pendingJobs<1 || organizationPendingJobs<1)
            throw new IllegalArgumentException("Invalid capacity limits");
    }
    public static CapacityProperties defaults(){return new CapacityProperties(32,16,4,2,4,16,100,1000,5000,8000,20000,6000,10000,25000,8000,12000,120000,600000,33554432,67108864,536870912,8589934592L,100,20);}
}
