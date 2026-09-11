package com.jsd.aird.ai.application;

import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.Actor;
import org.springframework.stereotype.Service;

@Service
public class DataSearchAuthorizationService {

    private final AuthorizationService authorization;

    public DataSearchAuthorizationService(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    public DataSourceFileSearchFacade.AccessScope requireDataView(Actor actor) {
        var scope = authorization.resolveScope(new PermissionCheck(actor.organizationId(), actor.userId(),
                "data.view", "DATA", null, "READ"));
        if (!scope.allowed()) {
            throw new ApiException(ApiErrorCode.PERMISSION_DENIED, "当前用户没有数据中心查看权限");
        }
        return new DataSourceFileSearchFacade.AccessScope(scope.scopeType(), actor.userId(), scope.targetIds());
    }
}
