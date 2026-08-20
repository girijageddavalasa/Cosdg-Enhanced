package com.example.dag.coverage;

import java.util.List;

public class CoverageUpdate {

    public String testcaseId;
    /** Project-defined diagnostic ratios, not COSDG/ACSD coverage formulas. */
    public Double nodeCoverage;
    public Double edgeCoverage;
    public Double overallCoverage;
    public String provenance = "PROJECT_DEFINED";
    public List<String> newNodes;
    public List<String> newEdges;

    public CoverageUpdate(
            String testcaseId,
            Double nodeCoverage,
            Double edgeCoverage,
            Double overallCoverage,
            List<String> newNodes,
            List<String> newEdges) {

        this.testcaseId = testcaseId;
        this.nodeCoverage = nodeCoverage;
        this.edgeCoverage = edgeCoverage;
        this.overallCoverage = overallCoverage;
        this.newNodes = newNodes;
        this.newEdges = newEdges;
    }
}
