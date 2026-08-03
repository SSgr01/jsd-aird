package com.jsd.aird.shared.security;

import java.util.UUID;

import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;

public final class ActorContext {

    public static final String ORGANIZATION_HEADER = "X-Organization-Id";
    public static final String USER_HEADER = "X-User-Id";
    public static final String USERNAME_HEADER = "X-Username";

    private static final ThreadLocal<Actor> ACTOR = new ThreadLocal<>();

    private ActorContext() {
    }

    public static void set(Actor actor) {
        ACTOR.set(actor);
    }

    public static Actor required() {
        var actor = ACTOR.get();
        if (actor == null) {
            throw new ApiException(ApiErrorCode.OPERATION_FORBIDDEN, "请求缺少有效的开发身份");
        }
        return actor;
    }

    public static void clear() {
        ACTOR.remove();
    }

    public static Actor developmentDefault() {
        return new Actor(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "developer"
        );
    }
}
