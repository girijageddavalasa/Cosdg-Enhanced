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
                    java.util.Set<String> runtimeTargets, java.util.Set<String> runtimeTargetMethods) {}
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
        result.visitedNodes().size(), result.visitedEdges().size(), result.runtimeTargets(), result.runtimeTargetMethods()));
  }

  public synchronized Summary summarize(List<Node> nodes, List<Edge> edges) {
    return new Summary(id, runs.size(), List.copyOf(runs),
        CoverageAnalyzer.compute(tracker.toMarking(), nodes, edges));
  }

  public CoverageAnalyzer.CoverageMarking marking() { return tracker.toMarking(); }
  public String id() { return id; }
}
