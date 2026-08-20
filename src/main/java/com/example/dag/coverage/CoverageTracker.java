package com.example.dag.coverage;

import com.example.dag.testcase.GeneratedTestCase;
import java.util.*;

public class CoverageTracker {

    // =====================================================
    // GLOBAL COVERAGE STORAGE
    // =====================================================
    private final Set<String> coveredNodes = new LinkedHashSet<>();
    private final Set<String> coveredEdges = new LinkedHashSet<>();
    private final Set<String> trackedTestcases = new LinkedHashSet<>();
    private final Map<String, Set<String>> markedExceptionTypes = new LinkedHashMap<>();
    private final Set<String> taggedClassMemberEdges = new LinkedHashSet<>();
    private boolean runtimeObserved;

    // =====================================================
    // TOTAL COUNTS
    // =====================================================
    private final int totalNodes;
    private final int totalEdges;

    // =====================================================
    // CONSTRUCTOR
    // =====================================================
    public CoverageTracker(int totalNodes, int totalEdges) {
        this.totalNodes = totalNodes;
        this.totalEdges = totalEdges;
    }

    // =====================================================
    // MAIN TRACK METHOD
    // =====================================================
    public CoverageUpdate track(GeneratedTestCase tc) {
        // ================================================
        // FIND NEW NODES
        // ================================================
        List<String> newNodes = new ArrayList<>();
        if (tc.dag_path != null) {
            for (String n : tc.dag_path) {
                String normalId = n.replace('.', '_');
                if (!coveredNodes.contains(normalId)) {
                    newNodes.add(n);
                }
            }
        }

        // ================================================
        // FIND NEW EDGES
        // ================================================
        List<String> newEdges = new ArrayList<>();
        if (tc.dag_edges != null) {
            for (String e : tc.dag_edges) {
                if (!coveredEdges.contains(e)) {
                    newEdges.add(e);
                }
            }
        }

        // ================================================
        // UPDATE GLOBAL STATE
        // ================================================
        if (tc.dag_path != null) {
            for (String n : tc.dag_path) {
                coveredNodes.add(n.replace('.', '_'));
            }
        }
        if (tc.dag_edges != null) {
            coveredEdges.addAll(tc.dag_edges);
        }
        trackedTestcases.add(tc.id);

        // ================================================
        // CALCULATE COVERAGE
        // ================================================
        Double nodeCoverage = totalNodes == 0 ? null : (coveredNodes.size() * 100.0) / totalNodes;
        Double edgeCoverage = totalEdges == 0 ? null : (coveredEdges.size() * 100.0) / totalEdges;
        // There is no paper-defined arithmetic mean of node and edge coverage.
        Double overallCoverage = null;

        // ================================================
        // RETURN UPDATE OBJECT
        // ================================================
        return new CoverageUpdate(
                tc.id,
                round(nodeCoverage),
                round(edgeCoverage),
                overallCoverage,
                newNodes,
                newEdges
        );
    }

    private Double round(Double v) {
        return v == null ? null : Math.round(v * 100.0) / 100.0;
    }

    public Set<String> getCoveredEdges() { return coveredEdges; }
    public Set<String> getCoveredNodes() { return coveredNodes; }
    public Set<String> getTrackedTestcases() { return trackedTestcases; }

    public void markRuntime(String testCase, Collection<String> nodes, Collection<String> edges,
                            Collection<String> taggedMembers, Map<String, Set<String>> exceptionTypes) {
        runtimeObserved = true;
        if (nodes != null) nodes.forEach(node -> coveredNodes.add(node.replace('.', '_')));
        if (edges != null) coveredEdges.addAll(edges);
        if (taggedMembers != null) taggedClassMemberEdges.addAll(taggedMembers);
        if (exceptionTypes != null) exceptionTypes.forEach((node, types) ->
            markedExceptionTypes.computeIfAbsent(node.replace('.', '_'), ignored -> new LinkedHashSet<>()).addAll(types));
        trackedTestcases.add(testCase == null ? "runtime-test" : testCase);
    }

    public boolean isRuntimeObserved() { return runtimeObserved; }

    public CoverageAnalyzer.CoverageMarking toMarking() {
        return runtimeObserved
            ? new CoverageAnalyzer.CoverageMarking(coveredNodes, coveredEdges,
                taggedClassMemberEdges.isEmpty() ? null : taggedClassMemberEdges,
                markedExceptionTypes, true,
                Set.of("statement", "method", "polymorphic", "throw", "catch", "exceptionType", "exceptionFlow"))
            : CoverageAnalyzer.CoverageMarking.staticPrediction(coveredNodes, coveredEdges);
    }
}
