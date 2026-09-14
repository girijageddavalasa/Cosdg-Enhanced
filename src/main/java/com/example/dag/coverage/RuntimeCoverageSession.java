package com.example.dag.coverage;

import com.example.dag.graph.Edge;
import com.example.dag.graph.Node;
import com.example.dag.runtime.RuntimeExecutionService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit union of runtime markings produced by multiple test executions. */
public final class RuntimeCoverageSession {
  private static final AtomicLong IDS = new AtomicLong();
  public record Run(String testCase, String status, int filesExecuted, double compileMs,
                    double executionMs, double totalRuntimeMs, int markedNodes, int markedEdges,
                    java.util.Set<String> runtimeTargets, java.util.Set<String> runtimeTargetMethods,
                    java.util.Set<String> visitedNodes, java.util.Set<String> visitedEdges,
                    java.util.Set<String> taggedClassMemberEdges,
                    java.util.Map<String, java.util.Set<String>> exceptionTypes) {}
  public record Summary(String id, int testCount, List<Run> tests,
                        CoverageAnalyzer.CoverageResult coverage) {}

  private final String id = "coverage-session-" + IDS.incrementAndGet();
  private final CoverageTracker tracker;
  private final List<Run> runs = new ArrayList<>();

  public RuntimeCoverageSession(int totalNodes, int totalEdges) {
    tracker = new CoverageTracker(totalNodes, totalEdges);
  }

  public synchronized void add(String testCase, RuntimeExecutionService.Result result) {
    if (result == null || !result.compilationSuccess()) return;
    tracker.markRuntime(testCase, result.visitedNodes(), result.visitedEdges(),
        result.taggedClassMemberEdges(), result.exceptionTypes());
    runs.add(new Run(testCase == null || testCase.isBlank() ? "runtime-test" : testCase,
        result.status(), result.filesExecuted(), result.compileMs(), result.executionMs(), result.totalRuntimeMs(),
        result.visitedNodes().size(), result.visitedEdges().size(), result.runtimeTargets(), result.runtimeTargetMethods(),
        result.visitedNodes(), result.visitedEdges(), result.taggedClassMemberEdges(), result.exceptionTypes()));
  }

  public synchronized Summary summarize(List<Node> nodes, List<Edge> edges) {
    return new Summary(id, runs.size(), List.copyOf(runs),
        CoverageAnalyzer.compute(tracker.toMarking(), nodes, edges));
  }

  public CoverageAnalyzer.CoverageMarking marking() { return tracker.toMarking(); }
  public synchronized CoverageAnalyzer.CoverageMarking markingForTests(java.util.Set<String> testCases) {
    java.util.LinkedHashSet<String> nodes = new java.util.LinkedHashSet<>();
    java.util.LinkedHashSet<String> edges = new java.util.LinkedHashSet<>();
    java.util.LinkedHashSet<String> members = new java.util.LinkedHashSet<>();
    java.util.LinkedHashMap<String, java.util.Set<String>> exceptionTypes = new java.util.LinkedHashMap<>();
    for (Run run : runs) {
      if (testCases == null || !testCases.contains(run.testCase())) continue;
      nodes.addAll(run.visitedNodes()); edges.addAll(run.visitedEdges()); members.addAll(run.taggedClassMemberEdges());
      run.exceptionTypes().forEach((node, types) -> exceptionTypes.computeIfAbsent(node,
          ignored -> new java.util.LinkedHashSet<>()).addAll(types));
    }
    return new CoverageAnalyzer.CoverageMarking(java.util.Set.copyOf(nodes), java.util.Set.copyOf(edges),
        java.util.Set.copyOf(members), java.util.Map.copyOf(exceptionTypes), true,
        java.util.Set.of("statement", "method", "polymorphic", "throw", "catch", "exceptionType", "exceptionFlow"));
  }
  public synchronized Run run(String testCase) {
    return runs.stream().filter(run -> run.testCase().equals(testCase)).reduce((first, last) -> last).orElse(null);
  }
  public String id() { return id; }
}
