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

    // =====================================================
    // TOTAL COUNTS
    // =====================================================
    private final int totalNodes;
    private final int totalEdges;

    // =====================================================
    // CONSTRUCTOR
    // =====================================================
    public CoverageTracker(int totalNodes, int totalEdges) {
        this.totalNodes = totalNodes == 0 ? 1 : totalNodes;
        this.totalEdges = totalEdges == 0 ? 1 : totalEdges;
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
        double nodeCoverage = (coveredNodes.size() * 100.0) / totalNodes;
        double edgeCoverage = (coveredEdges.size() * 100.0) / totalEdges;
        double overallCoverage = (nodeCoverage + edgeCoverage) / 2.0;

        // ================================================
        // RETURN UPDATE OBJECT
        // ================================================
        return new CoverageUpdate(
                tc.id,
                round(nodeCoverage),
                round(edgeCoverage),
                round(overallCoverage),
                newNodes,
                newEdges
        );
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public Set<String> getCoveredEdges() { return coveredEdges; }
    public Set<String> getCoveredNodes() { return coveredNodes; }
    public Set<String> getTrackedTestcases() { return trackedTestcases; }
}