package com.jsd.aird.platform.web;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Aggregate database time for agent requests. Never retain SQL or parameter values. */
@Component
public final class AgentQueryTiming implements BeanPostProcessor {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    @Override public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof JdbcTemplate)) return bean;
        var factory = new ProxyFactory(bean);
        factory.setProxyTargetClass(true);
        factory.addAdvice((MethodInterceptor) call -> {
            String method = call.getMethod().getName();
            if (!RequestTimingHolder.active() || !(method.startsWith("query") || method.startsWith("update")
                    || method.startsWith("batchUpdate") || "execute".equals(method))) return call.proceed();
            long started = System.nanoTime();
            int depth = DEPTH.get();
            DEPTH.set(depth + 1);
            try { return call.proceed(); }
            finally {
                if (depth == 0) {
                    RequestTimingHolder.add("db_query_ns", System.nanoTime() - started);
                    DEPTH.remove();
                } else DEPTH.set(depth);
                RequestTimingHolder.add("db_query_count", 1);
            }
        });
        return factory.getProxy();
    }
}
