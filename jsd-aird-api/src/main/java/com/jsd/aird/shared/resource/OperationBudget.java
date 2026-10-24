package com.jsd.aird.shared.resource;

import com.jsd.aird.shared.error.*;
import java.util.concurrent.TimeUnit;

/** A nested operation can shorten a deadline, never extend its caller's deadline. */
public final class OperationBudget {
    public enum Kind { READ, WRITE, HEAVY, UPLOAD }
    private record State(Kind kind,long deadline,java.util.List<AutoCloseable> resources) { }
    private static final ThreadLocal<State> CURRENT=new ThreadLocal<>();
    private OperationBudget(){ }
    public static Scope open(Kind kind,long millis){
        var previous=CURRENT.get();long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        if(previous!=null){deadline=Math.min(deadline,previous.deadline());if(previous.kind().ordinal()<kind.ordinal())kind=previous.kind();}
        var resources=previous==null?new java.util.ArrayList<AutoCloseable>():previous.resources();
        CURRENT.set(new State(kind,deadline,resources));return new Scope(previous,resources);
    }
    public static Kind kind(){var state=CURRENT.get();return state==null?Kind.WRITE:state.kind();}
    public static boolean active(){return CURRENT.get()!=null;}
    public static void hold(AutoCloseable resource){var state=CURRENT.get();if(state==null)throw new IllegalStateException("No operation owns this reservation");state.resources().add(resource);}
    public static long remainingMillis(){var state=CURRENT.get();return state==null?Long.MAX_VALUE:Math.max(0,TimeUnit.NANOSECONDS.toMillis(state.deadline()-System.nanoTime()));}
    public static void check(){if(Thread.currentThread().isInterrupted() || remainingMillis()==0)throw new ApiException(ApiErrorCode.OPERATION_TIMEOUT);}
    public static int sqlMillis(CapacityProperties p){check();return (int)Math.max(1,Math.min(remainingMillis(),switch(kind()){case READ->p.readSqlMillis();case HEAVY,UPLOAD->p.heavySqlMillis();case WRITE->p.writeSqlMillis();}));}
    public static int transactionMillis(CapacityProperties p){check();return (int)Math.max(1,Math.min(remainingMillis(),switch(kind()){case READ->p.readTransactionMillis();case HEAVY,UPLOAD->p.heavyTransactionMillis();case WRITE->p.writeTransactionMillis();}));}
    public static final class Scope implements AutoCloseable {
        private final State previous;private boolean closed;
        private final java.util.List<AutoCloseable> resources;
        private Scope(State previous,java.util.List<AutoCloseable> resources){this.previous=previous;this.resources=resources;}
        @Override public void close(){if(!closed){closed=true;if(previous==null){CURRENT.remove();for(int i=resources.size()-1;i>=0;i--)try{resources.get(i).close();}catch(Exception ignored){ }}else CURRENT.set(previous);}}
    }
}
