package com.example.dag.coverage;

import java.util.List;

public class CoverageUpdate {

    public String testcaseId;
    public double nodeCoverage;
    public double edgeCoverage;
    public double overallCoverage;
    public List<String> newNodes;
    public List<String> newEdges;

    public CoverageUpdate(
            String testcaseId,
            double nodeCoverage,
            double edgeCoverage,
            double overallCoverage,
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