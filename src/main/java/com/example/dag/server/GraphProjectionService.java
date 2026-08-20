package com.example.dag.server;

import com.example.dag.export.CanonicalGraphExporter.CanonicalGraph;
import com.example.dag.export.CanonicalGraphExporter.EdgeRecord;
import com.example.dag.export.CanonicalGraphExporter.NodeRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Creates bounded visualization projections without changing the canonical graph. */
public final class GraphProjectionService {
  public record ClassItem(String id, String name, String file, int methodCount) {}
  public record MethodItem(String id, String name, String classId, String file, Integer line) {}
  public record Query(String file, String classId, String methodId, String nodeId,
                      String nodeType, String edgeType, int nodeLimit, int edgeLimit, int depth) {}
  public record Projection(List<NodeRecord> nodes, List<EdgeRecord> edges, int matchingNodes,
                           int matchingEdges, boolean truncated, String message) {}

  public List<ClassItem> classes(CanonicalGraph graph) {
    Map<String, NodeRecord> entries = new LinkedHashMap<>();
    for (NodeRecord node : graph.nodes()) {
      if ("CLASS_ENTRY".equals(node.type())) entries.put(key(node.file(), node.classId()), node);
    }
    List<ClassItem> result = new ArrayList<>();
    for (NodeRecord entry : entries.values()) {
      long methods = graph.nodes().stream().filter(node -> "METHOD_ENTRY".equals(node.type())
          && same(node.file(), entry.file()) && same(node.classId(), entry.classId())).count();
      result.add(new ClassItem(key(entry.file(), entry.classId()), entry.classId(), entry.file(), (int) methods));
    }
    result.sort(Comparator.comparing(ClassItem::file, Comparator.nullsFirst(String::compareTo))
        .thenComparing(ClassItem::name));
    return result;
  }

  public List<MethodItem> methods(CanonicalGraph graph, String file, String classId) {
    return graph.nodes().stream().filter(node -> "METHOD_ENTRY".equals(node.type()))
        .filter(node -> same(file, node.file()) && same(classId, node.classId()))
        .map(node -> new MethodItem(node.id(), node.methodId(), node.classId(), node.file(), node.line()))
        .sorted(Comparator.comparing(MethodItem::name)).toList();
  }

  public Projection project(CanonicalGraph graph, Query raw) {
    Query query = normalize(raw);
    Map<String, NodeRecord> nodesById = new LinkedHashMap<>();
    graph.nodes().forEach(node -> nodesById.put(node.id(), node));
    Set<String> eligible = query.nodeId() == null ? filterNodes(graph, query) : neighborhood(graph, query, nodesById);

    List<NodeRecord> allMatchingNodes = graph.nodes().stream().filter(node -> eligible.contains(node.id())).toList();
    LinkedHashSet<String> selectedIds = new LinkedHashSet<>();
    allMatchingNodes.stream().limit(query.nodeLimit()).forEach(node -> selectedIds.add(node.id()));
    List<NodeRecord> selectedNodes = selectedIds.stream().map(nodesById::get).toList();

    Predicate<EdgeRecord> edgeFilter = edge -> query.edgeType() == null || query.edgeType().equals(edge.type());
    List<EdgeRecord> allMatchingEdges = graph.edges().stream().filter(edgeFilter)
        .filter(edge -> eligible.contains(edge.source()) && eligible.contains(edge.target())).toList();
    List<EdgeRecord> selectedEdges = allMatchingEdges.stream()
        .filter(edge -> selectedIds.contains(edge.source()) && selectedIds.contains(edge.target()))
        .limit(query.edgeLimit()).toList();
    boolean truncated = selectedNodes.size() < allMatchingNodes.size() || selectedEdges.size() < allMatchingEdges.size();
    String message = truncated ? "Graph contains " + allMatchingNodes.size() + " nodes and "
        + allMatchingEdges.size() + " edges. Showing " + selectedNodes.size() + " nodes and "
        + selectedEdges.size() + " edges. Refine the view to explore more." : null;
    return new Projection(selectedNodes, selectedEdges, allMatchingNodes.size(), allMatchingEdges.size(), truncated, message);
  }

  private Set<String> filterNodes(CanonicalGraph graph, Query query) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    boolean classSummary = query.classId() != null && query.methodId() == null;
    for (NodeRecord node : graph.nodes()) {
      if (query.file() != null && !same(query.file(), node.file())) continue;
      if (query.classId() != null && !same(query.classId(), node.classId())) continue;
      if (query.methodId() != null && !same(query.methodId(), node.methodId())
          && !"CLASS_ENTRY".equals(node.type())) continue;
      if (classSummary && !("CLASS_ENTRY".equals(node.type()) || "METHOD_ENTRY".equals(node.type()))) continue;
      if (query.nodeType() != null && !query.nodeType().equals(node.type())) continue;
      ids.add(node.id());
    }
    return ids;
  }

  private Set<String> neighborhood(CanonicalGraph graph, Query query, Map<String, NodeRecord> nodesById) {
    String requested = query.nodeId();
    String canonical = nodesById.containsKey(requested) ? requested : graph.nodes().stream()
        .filter(node -> requested.equals(node.internalId())).map(NodeRecord::id).findFirst().orElse(requested);
    Map<String, List<String>> adjacent = new HashMap<>();
    for (EdgeRecord edge : graph.edges()) {
      if (query.edgeType() != null && !query.edgeType().equals(edge.type())) continue;
      adjacent.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge.target());
      adjacent.computeIfAbsent(edge.target(), ignored -> new ArrayList<>()).add(edge.source());
    }
    Set<String> visited = new LinkedHashSet<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    Map<String, Integer> levels = new HashMap<>();
    if (nodesById.containsKey(canonical)) { queue.add(canonical); levels.put(canonical, 0); }
    while (!queue.isEmpty()) {
      String id = queue.remove();
      if (!visited.add(id)) continue;
      int level = levels.get(id);
      if (level >= query.depth()) continue;
      for (String next : adjacent.getOrDefault(id, List.of())) {
        if (!levels.containsKey(next)) { levels.put(next, level + 1); queue.add(next); }
      }
    }
    return visited;
  }

  private Query normalize(Query query) {
    int nodes = Math.max(1, Math.min(query.nodeLimit() <= 0 ? 250 : query.nodeLimit(), 500));
    int edges = Math.max(1, Math.min(query.edgeLimit() <= 0 ? 750 : query.edgeLimit(), 1500));
    int depth = Math.max(0, Math.min(query.depth() <= 0 ? 1 : query.depth(), 3));
    return new Query(blank(query.file()), blank(query.classId()), blank(query.methodId()),
        blank(query.nodeId()), blank(query.nodeType()), blank(query.edgeType()), nodes, edges, depth);
  }

  private static String blank(String value) { return value == null || value.isBlank() ? null : value; }
  private static String key(String file, String name) { return String.valueOf(file) + "::" + name; }
  private static boolean same(String left, String right) { return java.util.Objects.equals(left, right); }
}
