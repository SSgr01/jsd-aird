package com.jsd.aird.ai.formula.application;

import com.jsd.aird.ai.formula.api.ExperimentAnalysisFacade;
import com.jsd.aird.rnd.api.CompletedExperimentFactsProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class ResearchCaseLoader {
    private final ExperimentAnalysisFacade analysis;
    private final CompletedExperimentFactsProvider facts;

    public ResearchCaseLoader(ExperimentAnalysisFacade analysis, CompletedExperimentFactsProvider facts) {
        this.analysis = analysis;
        this.facts = facts;
    }

    public CaseCollection load(String taskProfileCode, UUID projectId, UUID categoryId) {
        var analysisRows = new ArrayList<ExperimentAnalysisFacade.AnalysisRow>();
        String profileVersion = null;
        var page = 1;
        while (true) {
            var result = analysis.query(new ExperimentAnalysisFacade.ExperimentAnalysisQuery(
                    taskProfileCode, Set.of(), projectId, categoryId, page, 200));
            if (profileVersion == null) profileVersion = result.analysisProfileVersion();
            analysisRows.addAll(result.items());
            if (page >= result.totalPages() || result.items().isEmpty()) break;
            page++;
        }
        var metadata = new LinkedHashMap<UUID, CompletedExperimentFactsProvider.CompletedExperimentFacts>();
        page = 1;
        while (true) {
            var result = facts.query(new CompletedExperimentFactsProvider.CompletedExperimentFactsQuery(
                    Set.of(), projectId, categoryId, page, 200));
            result.items().forEach(item -> metadata.put(item.experimentVersionId(), item));
            if (page >= result.totalPages() || result.items().isEmpty()) break;
            page++;
        }
        var cases = analysisRows.stream().filter(row -> metadata.containsKey(row.experimentVersionId()))
                .map(row -> ResearchCaseView.of(row, metadata.get(row.experimentVersionId())))
                .toList();
        return new CaseCollection(taskProfileCode, profileVersion == null ? "" : profileVersion, cases);
    }

    public record CaseCollection(String taskProfileCode, String analysisProfileVersion,
                                 List<ResearchCaseView> cases) {
        public CaseCollection { cases = cases == null ? List.of() : List.copyOf(cases); }
    }

    public record ResearchCaseView(ExperimentAnalysisFacade.AnalysisRow analysisRow, UUID projectId, UUID stageId,
                                   UUID taskId, UUID categoryId, String categoryName, LocalDate experimentDate,
                                   String title) {
        static ResearchCaseView of(ExperimentAnalysisFacade.AnalysisRow row,
                                   CompletedExperimentFactsProvider.CompletedExperimentFacts fact) {
            return new ResearchCaseView(row, fact.projectId(), fact.stageId(), fact.taskId(), fact.categoryId(),
                    fact.categoryName(), fact.experimentDate(), fact.title());
        }
    }
}
