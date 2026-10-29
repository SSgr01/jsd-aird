package com.jsd.aird.shared.resource;

import com.jsd.aird.shared.error.*;
import java.sql.*;
import org.springframework.dao.*;

public final class ResourceFailures {
    private ResourceFailures(){ }
    public static ApiErrorCode code(Throwable error){
        if(OperationBudget.remainingMillis()==0 || Thread.currentThread().isInterrupted())return ApiErrorCode.OPERATION_TIMEOUT;
        for(Throwable current=error;current!=null;current=current.getCause()){
            if(current instanceof java.util.concurrent.RejectedExecutionException)return ApiErrorCode.SERVER_BUSY;
            if(current instanceof InterruptedException || current instanceof java.net.SocketTimeoutException)return ApiErrorCode.OPERATION_TIMEOUT;
            if(current instanceof ApiException api)return api.errorCode();
            if(current instanceof SQLException sql){String state=sql.getSQLState();
                if("55P03".equals(state))return sql.getMessage()!=null && sql.getMessage().contains("aird:queue-capacity")?ApiErrorCode.SERVER_BUSY:ApiErrorCode.DATABASE_BUSY;
                if("57014".equals(state)||"25P03".equals(state)||"25P04".equals(state))return ApiErrorCode.OPERATION_TIMEOUT;
                if("40P01".equals(state)||"40001".equals(state))return ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT;
            }
            if(current instanceof SQLTimeoutException || current instanceof QueryTimeoutException || current instanceof org.springframework.transaction.TransactionTimedOutException)return ApiErrorCode.OPERATION_TIMEOUT;
            if(current instanceof SQLTransientConnectionException || current instanceof DataAccessResourceFailureException)return ApiErrorCode.DATABASE_BUSY;
        }
        return null;
    }
}
