package com.example.dag;

import com.example.dag.coverage.ControlRequirementExtractor;
import com.example.dag.coverage.CoverageAnalyzer;
import com.example.dag.coverage.CoverageObligationEngine;
import com.example.dag.coverage.RuntimeCoverageSession;
import com.example.dag.server.GraphProjectionService;
import com.example.dag.server.GraphWorkspace;
import com.example.dag.testcase.DeterministicTestGenerationService;
import junit.framework.TestCase;

import java.util.List;
import java.util.Set;

public class ValidatedTestCaseUiSupportTest extends TestCase {
  public void testSelectedRuntimeTestProducesBoundedEvidenceProjection() {
    GraphWorkspace workspace = new GraphWorkspace(List.of(new GraphWorkspace.SourceFile("Branch.java",
        "class Branch{static void choose(int x){if(x>0){x++;}else{x--;}}}")));
    CoverageObligationEngine engine = new CoverageObligationEngine();
    var obligation = engine.build(workspace.current(),
        CoverageAnalyzer.CoverageMarking.staticPrediction(Set.of(), Set.of()), null).obligations().stream()
        .filter(value -> value.criterion() == CoverageObligationEngine.Criterion.STATEMENT_CONTROL)
        .filter(value -> "true".equals(value.details().requiredBranch())).findFirst().orElseThrow();
    RuntimeCoverageSession session = new RuntimeCoverageSession(nodes(workspace), edges(workspace));
    DeterministicTestGenerationService generation = new DeterministicTestGenerationService();
    var result = generation.generate(workspace.current(), obligation,
        new ControlRequirementExtractor().extract(workspace.current(), obligation), session,
        new DeterministicTestGenerationService.Options(12, 15_000, 5_000));
    assertEquals(result.reason(), DeterministicTestGenerationService.GenerationStatus.RETAINED, result.status());
    var test = result.retained();
    var selected = session.markingForTests(Set.of(test.testCaseId()));
    assertFalse(selected.visitedNodeIds().isEmpty());
    assertFalse(selected.visitedEdgeLabels().isEmpty());
    var projection = new GraphProjectionService().projectEvidence(workspace.current().graph(),
        test.runtimeNodes(), test.runtimeEdges(), 250, 750, 1);
    assertFalse(projection.nodes().isEmpty());
    assertTrue(projection.edges().stream().anyMatch(edge -> test.runtimeEdges().contains(edge.internalId())));
  }

  public void testUnknownTestSelectionHasNoRuntimeMarking() {
    RuntimeCoverageSession session = new RuntimeCoverageSession(10, 10);
    var marking = session.markingForTests(Set.of("missing"));
    assertTrue(marking.visitedNodeIds().isEmpty());
    assertTrue(marking.visitedEdgeLabels().isEmpty());
  }

  public void testGenerationRecordsCanBeClearedForNewGraph() {
    DeterministicTestGenerationService generation = new DeterministicTestGenerationService();
    generation.clearRecords();
    assertTrue(generation.records().isEmpty());
  }

  public void testTxtUploadCompilesThroughTemporaryJavaCopy() {
    GraphWorkspace workspace = new GraphWorkspace(List.of(new GraphWorkspace.SourceFile("Uploaded.txt",
        "class Uploaded{public static void main(String[] args){int x=1;}}")));
    var result = new com.example.dag.runtime.RuntimeExecutionService().execute(workspace.current(),
        new com.example.dag.runtime.RuntimeExecutionService.Request("Uploaded", List.of(), 5_000, "txt-upload"));
    assertTrue(result.stderr(), result.compilationSuccess());
    assertEquals("PASSED", result.status());
  }

  private int nodes(GraphWorkspace workspace) {
    return (int) workspace.current().builders().stream().flatMap(builder -> builder.getAllNodes().stream()).count();
  }

  private int edges(GraphWorkspace workspace) {
    return (int) workspace.current().builders().stream().flatMap(builder -> builder.getAllEdges().stream()).count();
  }
}
