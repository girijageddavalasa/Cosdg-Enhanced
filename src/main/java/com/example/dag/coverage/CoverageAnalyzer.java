package com.example.dag.coverage;

import com.example.dag.graph.Edge;
import com.example.dag.graph.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Computes paper-defined graph populations from supplied edge/type markings. */
public final class CoverageAnalyzer {
  public static final String STATIC_PREDICTED = "STATIC_PREDICTED";
  public static final String RUNTIME_OBSERVED = "RUNTIME_OBSERVED";

  public record MetricResult(String key, String label, long numerator, long denominator,
                             Double percentage, long applicableElements, String definitionId,
                             String definition, String provenance, boolean formulaImplemented,
                             boolean markingAvailable,
                             String measurementKind) {}

  public record CoverageResult(Map<String, MetricResult> traditional,
                               Map<String, MetricResult> objectOriented,
                               Map<String, MetricResult> exception,
                               String measurementKind,
                               String limitation) {
    public MetricResult metric(String key) {
      if (traditional.containsKey(key)) return traditional.get(key);
      if (objectOriented.containsKey(key)) return objectOriented.get(key);
      return exception.get(key);
    }
  }

  /** Optional markings that require information beyond a plain visited-edge list. */
  public record CoverageMarking(Set<String> visitedNodeIds, Set<String> visitedEdgeLabels,
                                Set<String> taggedClassMemberEdgeLabels,
                                Map<String, Set<String>> markedExceptionTypesByThrowNode,
                                boolean runtimeObserved,
                                Set<String> availableRuntimeMetrics) {
    public CoverageMarking(Set<String> nodes, Set<String> edges, Set<String> tagged,
                           Map<String, Set<String>> exceptionTypes, boolean runtimeObserved) {
      this(nodes, edges, tagged, exceptionTypes, runtimeObserved, null);
    }
    public CoverageMarking {
      visitedNodeIds = normalizedNodes(visitedNodeIds);
      visitedEdgeLabels = normalizedEdges(visitedEdgeLabels);
      if (taggedClassMemberEdgeLabels != null) taggedClassMemberEdgeLabels = normalizedEdges(taggedClassMemberEdgeLabels);
      if (markedExceptionTypesByThrowNode != null) {
        Map<String, Set<String>> normalized = new HashMap<>();
        markedExceptionTypesByThrowNode.forEach((node, types) -> normalized.put(normalizeNode(node), Set.copyOf(types)));
        markedExceptionTypesByThrowNode = Map.copyOf(normalized);
      }
      if (availableRuntimeMetrics != null) availableRuntimeMetrics = Set.copyOf(availableRuntimeMetrics);
    }

    public static CoverageMarking staticPrediction(Set<String> nodes, Set<String> edges) {
      return new CoverageMarking(nodes, edges, null, null, false, null);
    }
  }

  private CoverageAnalyzer() {}

  /** Compatibility entry point: supplied paths are static/predicted, never runtime-observed. */
  public static CoverageResult compute(Set<String> visitedNodeIds, Set<String> visitedEdgeLabels,
                                       List<Node> allNodes, List<Edge> allEdges) {
    return compute(CoverageMarking.staticPrediction(visitedNodeIds, visitedEdgeLabels), allNodes, allEdges);
  }

  public static CoverageResult compute(CoverageMarking marking, List<Node> allNodes, List<Edge> allEdges) {
    String kind = marking.runtimeObserved() ? RUNTIME_OBSERVED : STATIC_PREDICTED;
    Map<String, MetricResult> traditional = new LinkedHashMap<>();
    Map<String, MetricResult> objectOriented = new LinkedHashMap<>();
    Map<String, MetricResult> exception = new LinkedHashMap<>();

    List<Edge> statementEdges = edges(allEdges, CoverageAnalyzer::isStatementControlEdge);
    traditional.put("statement", edgeMetric("statement", "Statement", statementEdges, marking,
        "COSDG-2011-Statement", "marked Type-1/Type-2 control-dependence edges / all Type-1/Type-2 edges",
        "EQUIVALENT_INTERPRETATION", kind));

    List<Edge> branchEdges = edges(allEdges, edge -> "CONTROL_DEPENDENCE".equals(edge.type) && edge.branch != null
        && ("true".equals(edge.branch) || "false".equals(edge.branch)));
    traditional.put("branch", unsupported("branch", "Branch", branchEdges.size(),
        "COSDG-2011-Branch", "Paper states control-edge analysis but does not specify the detailed formula here", kind));
    traditional.put("condition", unsupported("condition", "Condition", 0,
        "COSDG-2011-Condition", "Condition-atom representation/marking is absent", kind));
    traditional.put("path", unsupported("path", "Path", 0,
        "COSDG-2011-Path", "Feasible runtime path marking is absent", kind));
    long dataElements = allEdges.stream().filter(edge -> "DATA_DEPENDENCE".equals(edge.type)
        || "SUMMARY".equals(edge.type)).count();
    traditional.put("allDefs", unsupported("allDefs", "All-Defs", dataElements,
        "COSDG-2011-AllDefs", "Definition obligations are not classified", kind));
    traditional.put("allUses", unsupported("allUses", "All-Uses", dataElements,
        "COSDG-2011-AllUses", "Use obligations are not classified", kind));
    traditional.put("allDefUses", unsupported("allDefUses", "All-Def-Uses", dataElements,
        "COSDG-2011-AllDefUses", "Def-use paths and runtime markings are absent", kind));

    objectOriented.put("method", edgeMetric("method", "Method", edges(allEdges, edge -> "CLASS_MEMBER".equals(edge.type)), marking,
        "COSDG-2011-Method", "marked class-member edges / all class-member edges", "PAPER", kind));
    List<Edge> polymorphicEdges = edges(allEdges, edge -> "POLYMORPHIC_METHOD_CALL".equals(edge.type));
    objectOriented.put("polymorphic", edgeMetric("polymorphic", "Polymorphic",
        polymorphicEdges, marking, runtimeAvailable(marking, "polymorphic"),
        "COSDG-2011-Polymorphic", "marked Em3 edges / all Em3 edges", "PAPER", kind));
    boolean methodCallAvailable = polymorphicEdges.isEmpty() || runtimeAvailable(marking, "polymorphic");
    objectOriented.put("methodCall", edgeMetric("methodCall", "Total Method Call",
        edges(allEdges, CoverageAnalyzer::isMethodCall), marking, methodCallAvailable,
        "COSDG-2011-MethodCall", "marked Em1+Em2+Em3 edges / all Em1+Em2+Em3 edges", "PAPER", kind));
    objectOriented.put("inheritance", inheritanceMetric(allEdges, marking, kind));

    exception.put("throw", edgeMetric("throw", "Throw",
        edges(allEdges, edge -> "CONTROL_DEPENDENCE".equals(edge.type) && semanticType(edge.to).equals("THROW_STMT")), marking,
        "ACSD-2019-Throw", "marked control-dependence edges ending at throw vertices / all such edges", "PAPER", kind));
    exception.put("catch", edgeMetric("catch", "Catch", edges(allEdges, edge -> "EXCEPTION_CATCH".equals(edge.type)), marking,
        "ACSD-2019-Catch", "marked Ee2 edges / all Ee2 edges", "PAPER", kind));
    exception.put("exceptionType", exceptionTypeMetric(allNodes, marking, kind));
    exception.put("exceptionFlow", edgeMetric("exceptionFlow", "Exception Flow",
        edges(allEdges, edge -> "EXCEPTION_THROW".equals(edge.type)), marking,
        "ACSD-2019-ExceptionFlow", "marked Ee1 edges / all Ee1 edges", "PAPER", kind));

    String limitation = marking.runtimeObserved()
        ? "Values use supplied runtime markings."
        : "Values use static/predicted path markings; no runtime execution has been supplied for this result.";
    return new CoverageResult(Map.copyOf(traditional), Map.copyOf(objectOriented), Map.copyOf(exception), kind, limitation);
  }

  private static MetricResult inheritanceMetric(List<Edge> allEdges, CoverageMarking marking, String kind) {
    List<Edge> inheritance = edges(allEdges, edge -> "INHERITANCE".equals(edge.type));
    long denominator = inheritance.stream().mapToLong(edge -> edge.tags.size()).sum();
    Set<String> tagged = marking.taggedClassMemberEdgeLabels();
    long numerator = tagged == null ? 0 : allEdges.stream().filter(edge -> "CLASS_MEMBER".equals(edge.type))
        .filter(edge -> tagged.contains(edge.label)).count();
    return metric("inheritance", "Inheritance", numerator, denominator, true, tagged != null,
        "COSDG-2011-Inheritance", "tagged class-member edges / visible methods in inheritance-edge tags",
        "PAPER", kind);
  }

  private static MetricResult exceptionTypeMetric(List<Node> nodes, CoverageMarking marking, String kind) {
    List<Node> throwsNodes = nodes.stream().filter(node -> "THROW_STMT".equals(semanticType(node))).toList();
    long denominator = throwsNodes.stream().mapToLong(node -> node.exceptionTypes.size()).sum();
    Map<String, Set<String>> marked = marking.markedExceptionTypesByThrowNode();
    long numerator = 0;
    if (marked != null) {
      for (Node node : throwsNodes) {
        Set<String> observed = marked.getOrDefault(normalizeNode(node.id), Set.of());
        numerator += node.exceptionTypes.stream().filter(observed::contains).count();
      }
    }
    return metric("exceptionType", "Exception Type", numerator, denominator, true, marked != null,
        "ACSD-2019-ExceptionType", "marked potentially-thrown types at throw vertices / all potentially-thrown types",
        "PAPER", kind);
  }

  private static MetricResult edgeMetric(String key, String label, List<Edge> population,
                                         CoverageMarking marking, String definitionId,
                                         String definition, String provenance, String kind) {
    return edgeMetric(key, label, population, marking, true, definitionId, definition, provenance, kind);
  }

  private static MetricResult edgeMetric(String key, String label, List<Edge> population,
                                         CoverageMarking marking, boolean markingAvailable, String definitionId,
                                         String definition, String provenance, String kind) {
    long marked = population.stream().filter(edge -> marking.visitedEdgeLabels().contains(edge.label)).count();
    return metric(key, label, marked, population.size(), true, markingAvailable, definitionId, definition, provenance, kind);
  }

  private static boolean runtimeAvailable(CoverageMarking marking, String metric) {
    return !marking.runtimeObserved() || marking.availableRuntimeMetrics() == null
        || marking.availableRuntimeMetrics().contains(metric);
  }

  private static MetricResult metric(String key, String label, long numerator, long denominator,
                                     boolean formulaImplemented, boolean markingAvailable,
                                     String definitionId, String definition,
                                     String provenance, String kind) {
    Double percentage = formulaImplemented && markingAvailable && denominator > 0
        ? Math.round(numerator * 10000.0 / denominator) / 100.0 : null;
    return new MetricResult(key, label, numerator, denominator, percentage, denominator,
        definitionId, definition, provenance, formulaImplemented, markingAvailable, kind);
  }

  private static MetricResult unsupported(String key, String label, long applicable,
                                          String id, String definition, String kind) {
    return metric(key, label, 0, applicable, false, false, id, definition, "UNSUPPORTED_UNCERTAIN", kind);
  }

  private static List<Edge> edges(List<Edge> edges, Predicate<Edge> predicate) {
    return edges.stream().filter(predicate).toList();
  }
  private static boolean isMethodCall(Edge edge) {
    return "SIMPLE_METHOD_CALL".equals(edge.type) || "INHERITED_METHOD_CALL".equals(edge.type)
        || "POLYMORPHIC_METHOD_CALL".equals(edge.type);
  }
  private static boolean isStatementControlEdge(Edge edge) {
    if (!"CONTROL_DEPENDENCE".equals(edge.type) || !isStatementVertex(edge.to)) return false;
    String from = semanticType(edge.from);
    return "METHOD_ENTRY".equals(from) || "IF_PREDICATE".equals(from) || "LOOP_PREDICATE".equals(from);
  }
  private static boolean isStatementVertex(Node node) {
    String type = semanticType(node);
    return !(type.equals("CLASS_ENTRY") || type.equals("METHOD_ENTRY") || type.startsWith("FORMAL_")
        || type.startsWith("ACTUAL_") || type.equals("CATCH_START") || type.equals("TRY_BLOCK_START"));
  }
  private static String semanticType(Node node) {
    if (node == null || node.type == null) return "";
    String[] parts = node.type.split("<br/?>");
    return parts.length > 1 ? parts[1] : node.type;
  }
  private static Set<String> normalizedNodes(Set<String> values) {
    Set<String> result = new LinkedHashSet<>();
    if (values != null) values.forEach(value -> result.add(normalizeNode(value)));
    return Set.copyOf(result);
  }
  private static Set<String> normalizedEdges(Set<String> values) {
    Set<String> result = new HashSet<>();
    if (values != null) values.forEach(value -> result.add(value.trim()));
    return Set.copyOf(result);
  }
  private static String normalizeNode(String value) { return value == null ? "" : value.replace('.', '_').trim(); }
}
