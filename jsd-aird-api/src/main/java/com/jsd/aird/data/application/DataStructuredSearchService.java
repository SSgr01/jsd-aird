package com.jsd.aird.data.application;

import java.util.List;
import java.util.UUID;

import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionCheck;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DataStructuredSearchService {

    private final DataSourceFileSearchFacade search;
    private final AuthorizationService authorization;

    public DataStructuredSearchService(DataSourceFileSearchFacade search, AuthorizationService authorization) {
        this.search = search;
        this.authorization = authorization;
    }

    public DataSourceFileSearchFacade.DataDetailResult search(SearchCommand command) {
        var actor = ActorContext.required();
        var permission = authorization.resolveScope(new PermissionCheck(actor.organizationId(), actor.userId(),
                "data.view", "DATA", null, "READ"));
        if (!permission.allowed()) {
            throw new ApiException(ApiErrorCode.PERMISSION_DENIED, "当前用户没有数据中心查看权限");
        }
        var fileName = command.fileName() == null ? "" : command.fileName().strip();
        var recordTerms = terms(command.recordTerms());
        var fieldTerms = terms(command.fieldTerms());
        if (!StringUtils.hasText(fileName) && recordTerms.isEmpty() && fieldTerms.isEmpty()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "请输入文件名、记录标识或业务字段");
        }
        var mode = recordTerms.isEmpty() && fieldTerms.isEmpty()
                ? DataSourceFileSearchFacade.DataQueryMode.FILE_OVERVIEW
                : fieldTerms.isEmpty() ? DataSourceFileSearchFacade.DataQueryMode.RECORD_DETAIL
                : DataSourceFileSearchFacade.DataQueryMode.FIELD_LOOKUP;
        var request = new DataSourceFileSearchFacade.DataDetailQuery(mode, fileName,
                fileName.isBlank() ? DataSourceFileSearchFacade.DataFileScope.AUTO
                        : DataSourceFileSearchFacade.DataFileScope.EXPLICIT,
                recordTerms, fieldTerms, DataSourceFileSearchFacade.RecordTermMatch.ANY,
                DataSourceFileSearchFacade.EvidenceFilter.ANY, 20, 20, 50,
                DataSourceFileSearchFacade.ContextMode.MINIMAL, 16, 5, "VALUE");
        var categoryIds = command.uncategorized()
                ? List.of(DataSourceFileSearchFacade.UNCATEGORIZED_SCOPE_ID)
                : command.categoryId() == null ? List.<UUID>of() : List.of(command.categoryId());
        var scope = new DataSourceFileSearchFacade.AccessScope(permission.scopeType(), actor.userId(), permission.targetIds());
        return search.queryDetails(actor.organizationId(), request, categoryIds,
                new DataSourceFileSearchFacade.RetrievalAccessScope(scope, DataSourceFileSearchFacade.AccessScope.denied()));
    }

    private List<String> terms(List<String> values) {
        return values == null ? List.of() : values.stream().filter(StringUtils::hasText)
                .map(String::strip).distinct().limit(8).toList();
    }

    public record SearchCommand(String fileName, List<String> recordTerms, List<String> fieldTerms,
                                UUID categoryId, boolean uncategorized) { }
}
