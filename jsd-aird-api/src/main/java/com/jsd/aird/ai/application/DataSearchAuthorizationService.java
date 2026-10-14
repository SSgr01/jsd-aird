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
        var scope = resolve(actor, "data.view", "DATA");
        if (!scope.allowed()) {
            throw new ApiException(ApiErrorCode.PERMISSION_DENIED, "当前用户没有数据中心查看权限");
        }
        return toAccessScope(actor, scope);
    }

    /**
     * Resolves the two independently owned structured retrieval channels. A user
     * may have either permission, so a denial on one channel is represented as a
     * repository DENIED scope instead of preventing the other channel from being
     * searched.
     */
    public DataSourceFileSearchFacade.RetrievalAccessScope requireRetrievalScopes(Actor actor) {
        var data = resolve(actor, "data.view", "DATA");
        var experiment = resolve(actor, "experiment.view", "EXPERIMENT");
        if (!data.allowed() && !experiment.allowed()) {
            throw new ApiException(ApiErrorCode.PERMISSION_DENIED, "当前用户没有数据中心或按模板实验查看权限");
        }
        return new DataSourceFileSearchFacade.RetrievalAccessScope(
                data.allowed() ? toAccessScope(actor, data) : DataSourceFileSearchFacade.AccessScope.denied(),
                experiment.allowed() ? toAccessScope(actor, experiment) : DataSourceFileSearchFacade.AccessScope.denied());
    }

    private com.jsd.aird.iam.api.DataScopeResolver.ResolvedDataScope resolve(Actor actor,
                                                                               String permission,
                                                                               String resourceType) {
        return authorization.resolveScope(new PermissionCheck(actor.organizationId(), actor.userId(),
                permission, resourceType, null, "READ"));
    }

    private DataSourceFileSearchFacade.AccessScope toAccessScope(Actor actor,
                                                                  com.jsd.aird.iam.api.DataScopeResolver.ResolvedDataScope scope) {
        return new DataSourceFileSearchFacade.AccessScope(scope.scopeType(), actor.userId(), scope.targetIds());
    }
}
