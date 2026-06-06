package com.example.dag.testcase;

import com.example.dag.graph.Edge;
import com.example.dag.graph.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded path enumerator to prevent exponential path explosion
 * and OutOfMemoryError in large/looping programs.
 */
public class PathEnumerator {

    // Global threshold guard to protect JVM heap memory limits
    private static final int PATH_COUNT_HARD_LIMIT = 5000;

    public static class EnumeratedPath {
        public final List<String> nodeIds;
        public final List<String> edgeLabels;

        public EnumeratedPath(List<String> nodeIds, List<String> edgeLabels) {
            this.nodeIds = new ArrayList<>(nodeIds);
            this.edgeLabels = new ArrayList<>(edgeLabels);
        }

        public String toPathString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < nodeIds.size(); i++) {
                sb.append(nodeIds.get(i));
                if (i < edgeLabels.size()) {
                    sb.append(" --(").append(edgeLabels.get(i)).append(")--> ");
                }
            }
            return sb.toString();
        }
    }

    public static List<EnumeratedPath> enumerate(Node startNode) {
        List<EnumeratedPath> results = new ArrayList<>();
        if (startNode == null) return results;

        Map<String, Integer> visitCountsOnCurrentStack = new HashMap<>();

        dfsBounded(
            startNode,
            new ArrayList<>(),
            new ArrayList<>(),
            visitCountsOnCurrentStack,
            results
        );

        return results;
    }

    private static void dfsBounded(
            Node current,
            List<String> pathNodes,
            List<String> pathEdges,
            Map<String, Integer> visitCounts,
            List<EnumeratedPath> results) {

        // Hard stop if paths hit safety limits
        if (results.size() >= PATH_COUNT_HARD_LIMIT) {
            return;
        }

        String dotId = current.id.replace('_', '.');
        pathNodes.add(dotId);
        
        // Track how many times this node appears on the current path branch
        int currentCount = visitCounts.getOrDefault(current.id, 0) + 1;
        visitCounts.put(current.id, currentCount);

        List<Edge> edges = current.edges;

        // LOOP PRUNING ENFORCEMENT: If we enter a loop block a second time (currentCount > 1), 
        // we treat it as a leaf node and stop going deeper. This kills the OutOfMemory crash.
        if (edges == null || edges.isEmpty() || currentCount > 1) {
            results.add(new EnumeratedPath(pathNodes, pathEdges));
        } else {
            for (Edge e : edges) {
                Node next = e.to;

                pathEdges.add(e.label);
                dfsBounded(next, pathNodes, pathEdges, visitCounts, results);
                pathEdges.remove(pathEdges.size() - 1);
            }
        }

        // Backtrack safely
        visitCounts.put(current.id, currentCount - 1);
        pathNodes.remove(pathNodes.size() - 1);
    }
}