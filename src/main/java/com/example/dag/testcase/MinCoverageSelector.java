package com.example.dag.testcase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Greedy set-cover algorithm.
 *
 * Given a list of generated test cases (each covering a set of edges),
 * returns the MINIMUM subset of test cases that covers ALL edges
 * seen across all paths.
 *
 * This is purely deterministic — no LLM involved.
 */
public class MinCoverageSelector {

    // ============================================================
    // INPUT: a generated test case + the edges it covers
    // ============================================================

    public static class ScoredTestCase {

        public final GeneratedTestCase testCase;
        public final Set<String> coveredEdges;

        public ScoredTestCase(
                GeneratedTestCase testCase,
                Set<String> coveredEdges) {

            this.testCase = testCase;
            this.coveredEdges = new HashSet<>(coveredEdges);
        }
    }

    // ============================================================
    // GREEDY SET COVER
    // ============================================================

    /**
     * Returns the minimum list of ScoredTestCase entries that
     * collectively cover every edge appearing in any test case.
     *
     * Greedy strategy: at each step pick the test case that
     * covers the most currently-uncovered edges.
     */
    public static List<ScoredTestCase> selectMinimum(
            List<ScoredTestCase> all) {

        // Universe = all edges across all test cases
        Set<String> universe = new LinkedHashSet<>();

        for (ScoredTestCase s : all) {
            universe.addAll(s.coveredEdges);
        }

        Set<String> covered = new HashSet<>();
        List<ScoredTestCase> selected = new ArrayList<>();
        List<ScoredTestCase> remaining = new ArrayList<>(all);

        while (!covered.containsAll(universe) && !remaining.isEmpty()) {

            // Pick the candidate that covers the most new edges
            ScoredTestCase best = null;
            int bestNew = -1;

            for (ScoredTestCase candidate : remaining) {

                long newEdges = candidate.coveredEdges.stream()
                        .filter(e -> !covered.contains(e))
                        .count();

                if (newEdges > bestNew) {
                    bestNew = (int) newEdges;
                    best = candidate;
                }
            }

            if (best == null || bestNew == 0) break;

            selected.add(best);
            covered.addAll(best.coveredEdges);
            remaining.remove(best);
        }

        return selected;
    }
}
