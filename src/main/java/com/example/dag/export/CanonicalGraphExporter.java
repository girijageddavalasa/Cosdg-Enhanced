package com.example.dag.export;

import com.example.dag.DAGBuilder;
import com.example.dag.graph.Edge;
import com.example.dag.graph.Node;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Flat, complete serialization of the authoritative graph collections. */
public final class CanonicalGraphExporter {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private CanonicalGraphExporter() {}

  public static CanonicalGraph export(DAGBuilder builder, String program, int statementCount) {
    return export(List.of(new GraphInput(builder, builder.getSourceFile())), program, statementCount);
  }

  public static CanonicalGraph export(List<GraphInput> inputs, String program, int statementCount) {
    List<NodeRecord> nodes = new ArrayList<>();
    List<EdgeRecord> edges = new ArrayList<>();
    IdentityHashMap<Node, String> canonicalNodeIds = new IdentityHashMap<>();
    Set<String> usedNodeIds = new LinkedHashSet<>();
    Set<String> usedEdgeIds = new LinkedHashSet<>();

    for (int graphIndex = 0; graphIndex < inputs.size(); graphIndex++) {
      GraphInput input = inputs.get(graphIndex);
      String scope = scope(program, input.file(), graphIndex);
      for (Node node : input.builder().getAllNodes()) {
        String id = scope + "::" + node.id;
        if (!usedNodeIds.add(id)) throw new IllegalStateException("Duplicate canonical node id: " + id);
        canonicalNodeIds.put(node, id);
        nodes.add(new NodeRecord(id, node.id, semanticType(node), node.classId, node.methodId,
            node.sourceFile != null ? node.sourceFile : input.file(),
            node.sourceLine > 0 ? node.sourceLine : null,
            List.copyOf(node.exceptionTypes)));
      }
    }

    for (int graphIndex = 0; graphIndex < inputs.size(); graphIndex++) {
      GraphInput input = inputs.get(graphIndex);
      String scope = scope(program, input.file(), graphIndex);
      for (Edge edge : input.builder().getAllEdges()) {
        String source = canonicalNodeIds.get(edge.from);
        String target = canonicalNodeIds.get(edge.to);
        if (source == null || target == null) {
          throw new IllegalStateException("Edge endpoint is absent from authoritative nodes: " + edge.label);
        }
        String id = scope + "::" + edge.label;
        if (!usedEdgeIds.add(id)) throw new IllegalStateException("Duplicate canonical edge id: " + id);
        edges.add(new EdgeRecord(id, edge.label, edge.type, source, target, edge.branch,
            edge.exceptionType, List.copyOf(edge.tags)));
      }
    }

    return new CanonicalGraph(new Metadata(program, statementCount, nodes.size(), edges.size()), nodes, edges);
  }

  public static String toJson(CanonicalGraph graph) {
    return GSON.toJson(graph);
  }

  private static String scope(String program, String file, int graphIndex) {
    String programPart = program == null || program.isBlank() ? "program" : program;
    String filePart = file == null || file.isBlank() ? "graph-" + graphIndex : file.replace('\\', '/');
    return programPart + "::" + filePart;
  }

  private static String semanticType(Node node) {
    String[] parts = node.type.split("<br/?>");
    return parts.length > 1 ? parts[1] : node.type;
  }

  public record GraphInput(DAGBuilder builder, String file) {}
  public record Metadata(String program, int statementCount, int nodeCount, int edgeCount) {}
  public record NodeRecord(String id, String internalId, String type, String classId, String methodId,
                           String file, Integer line, List<String> exceptionTypes) {}
  public record EdgeRecord(String id, String internalId, String type, String source, String target,
                           String branch, String exceptionType, List<String> tags) {}
  public record CanonicalGraph(Metadata metadata, List<NodeRecord> nodes, List<EdgeRecord> edges) {}
}
