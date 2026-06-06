package com.example.dag.coverage;

import com.example.dag.graph.*;
import java.util.*;

/**
 * Computes all 4 Call Coverage metrics and 4 Exception Flow metrics:
 *
 * COSDG CALL COVERAGE:
 * Covmd  — Method Coverage
 * Covpc  — Polymorphic Call Coverage
 * Covic  — Inheritance Coverage
 * Covmc  — Total Method Call Coverage
 *
 * EXCEPTION FLOW COVERAGE:
 * Covet  — Throw Coverage
 * Covec  — Catch Coverage
 * Covety — Exception Type Coverage
 * Covef  — Exception Flow Coverage
 */
public class CoverageAnalyzer {

    // ============================================================
    // INNER RESULT CLASS
    // ============================================================
    public static class CoverageResult {
        // Call Coverage
        public double covmd;
        public double covpc;
        public double covic;
        public double covmc;

        // Exception Coverage
        public double covet;
        public double covec;
        public double covety;
        public double covef;

        public double overall;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("covmd", round(covmd));
            m.put("covpc", round(covpc));
            m.put("covic", round(covic));
            m.put("covmc", round(covmc));
            m.put("covet", round(covet));
            m.put("covec", round(covec));
            m.put("covety", round(covety));
            m.put("covef", round(covef));
            m.put("overall", round(overall));
            return m;
        }

        private double round(double v) {
            return Math.round(v * 100.0) / 100.0;
        }
    }

    // ============================================================
    // MAIN COMPUTE
    // ============================================================
    public static CoverageResult compute(
            Set<String> visitedNodeIds,
            Set<String> visitedEdgeLabels,
            List<Node> allNodes,
            List<Edge> allEdges) {

        CoverageResult result = new CoverageResult();

        if (allNodes.isEmpty()) {
            return result;
        }

        // Normalize sets to guarantee match configurations across platforms
        Set<String> normVisitedNodes = new HashSet<>();
        for (String id : visitedNodeIds) {
            normVisitedNodes.add(id.replace('.', '_').trim());
        }

        Set<String> normVisitedEdges = new HashSet<>();
        for (String label : visitedEdgeLabels) {
            normVisitedEdges.add(label.trim());
        }

        // ========================================================
        // 1. Covmd — Method Coverage
        // ========================================================
        long markedNodes = allNodes.stream()
                .filter(n -> normVisitedNodes.contains(n.id.replace('.', '_').trim()))
                .count();
        result.covmd = (markedNodes * 100.0) / allNodes.size();

        // ========================================================
        // Edge Collections Setup
        // ========================================================
        List<Edge> em1 = new ArrayList<>(); // Simple calls
        List<Edge> em2 = new ArrayList<>(); // Inherited calls
        List<Edge> em3 = new ArrayList<>(); // Polymorphic calls
        
        List<Edge> ect = new ArrayList<>(); // Throw control dependence edges
        List<Edge> ee2 = new ArrayList<>(); // Catch edges
        List<Edge> eef = new ArrayList<>(); // Exception flow edges (ex.xx)

        Set<String> totalExceptionTypes = new HashSet<>();
        Set<String> visitedExceptionTypes = new HashSet<>();

        // Process Nodes for Type Tracking by cleanly decomposing the markup description string
        for (Node n : allNodes) {
            String rawText = n.type == null ? "" : n.type;
            if (rawText.contains("CATCH_START")) {
                String exceptionClassName = "Exception";
                int startIdx = rawText.indexOf('[');
                int endIdx = rawText.indexOf(']');
                if (startIdx != -1 && endIdx > startIdx) {
                    exceptionClassName = rawText.substring(startIdx + 1, endIdx).trim();
                } else {
                    exceptionClassName = n.id; // Fallback to unique structural grouping
                }

                totalExceptionTypes.add(exceptionClassName);
                if (normVisitedNodes.contains(n.id.replace('.', '_').trim())) {
                    visitedExceptionTypes.add(exceptionClassName);
                }
            }
        }

        // Process Edges
        for (Edge e : allEdges) {
            String label = e.label == null ? "" : e.label.trim();
            String fromRaw = e.from.type == null ? "" : e.from.type.toUpperCase();
            String toRaw = e.to.type == null ? "" : e.to.type.toUpperCase();

            // Check Exception Flow Edges
            if (label.startsWith("ex.")) {
                eef.add(e);
                if (toRaw.contains("CATCH_START")) {
                    ee2.add(e);
                }
                continue;
            }

            // Check Throw Statement Control Dependence Edges
            if (toRaw.contains("THROW_STMT")) {
                ect.add(e);
                continue;
            }

            // Classify Method Call Edges
            if (fromRaw.contains("IF_EVAL") || fromRaw.contains("POLYMORPHIC")) {
                em3.add(e);
            } else if (fromRaw.contains("INHERIT") || label.toLowerCase().contains("inherit")) {
                em2.add(e);
            } else {
                em1.add(e);
            }
        }

        // ========================================================
        // 2. Covpc — Polymorphic Coverage
        // ========================================================
        if (!em3.isEmpty()) {
            long coveredEm3 = em3.stream().filter(e -> normVisitedEdges.contains(e.label.trim())).count();
            result.covpc = (coveredEm3 * 100.0) / em3.size();
        } else {
            result.covpc = 100.0;
        }

        // ========================================================
        // 3. Covic — Inheritance Coverage
        // ========================================================
        if (!em2.isEmpty()) {
            long coveredEm2 = em2.stream().filter(e -> normVisitedEdges.contains(e.label.trim())).count();
            result.covic = (coveredEm2 * 100.0) / em2.size();
        } else {
            result.covic = 100.0;
        }

        // ========================================================
        // 4. Covmc — Total Method Call Coverage
        // ========================================================
        List<Edge> allMethodCallEdges = new ArrayList<>();
        allMethodCallEdges.addAll(em1);
        allMethodCallEdges.addAll(em2);
        allMethodCallEdges.addAll(em3);

        if (!allMethodCallEdges.isEmpty()) {
            long coveredTotal = allMethodCallEdges.stream().filter(e -> normVisitedEdges.contains(e.label.trim())).count();
            result.covmc = (coveredTotal * 100.0) / allMethodCallEdges.size();
        } else {
            result.covmc = 0.0;
        }

        // ========================================================
        // 5. Covet — Throw Coverage
        // ========================================================
        if (!ect.isEmpty()) {
            long coveredEct = ect.stream().filter(e -> normVisitedEdges.contains(e.label.trim())).count();
            result.covet = (coveredEct * 100.0) / ect.size();
        } else {
            result.covet = 100.0; 
        }

        // ========================================================
        // 6. Covec — Catch Coverage
        // ========================================================
        if (!ee2.isEmpty()) {
            long coveredEe2 = ee2.stream().filter(e -> normVisitedEdges.contains(e.label.trim())).count();
            result.covec = (coveredEe2 * 100.0) / ee2.size();
        } else {
            result.covec = 100.0;
        }

        // ========================================================
        // 7. Covety — Exception Type Coverage
        // ========================================================
        if (!totalExceptionTypes.isEmpty()) {
            result.covety = (visitedExceptionTypes.size() * 100.0) / totalExceptionTypes.size();
        } else {
            result.covety = 100.0;
        }

        // ========================================================
        // 8. Covef — Exception Flow Coverage
        // ========================================================
        if (!eef.isEmpty()) {
            long coveredEef = eef.stream().filter(e -> normVisitedEdges.contains(e.label.trim())).count();
            result.covef = (coveredEef * 100.0) / eef.size();
        } else {
            result.covef = 100.0;
        }

        // ========================================================
        // 9. Overall Structural Suite Progress
        // ========================================================
        result.overall = (result.covmd + result.covpc + result.covic + result.covmc
                + result.covet + result.covec + result.covety + result.covef) / 8.0;

        return result;
    }

    public static double computeCoverage(Set<String> visitedNodes, Set<String> visitedEdges, List<Node> allNodes, List<Edge> allEdges) {
        CoverageResult res = compute(visitedNodes, visitedEdges, allNodes, allEdges);
        return res.overall;
    }
}