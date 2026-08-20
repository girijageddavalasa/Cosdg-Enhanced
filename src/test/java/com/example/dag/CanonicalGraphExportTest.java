package com.example.dag;

import com.example.dag.export.CanonicalGraphExporter;
import com.example.dag.export.CanonicalGraphExporter.CanonicalGraph;
import com.example.dag.graph.Edge;
import com.example.dag.visitor.ExceptionVisitor;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import junit.framework.TestCase;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CanonicalGraphExportTest extends TestCase {

  private DAGBuilder build(String code, String file) {
    JavaParser parser = new JavaParser(new CommonTokenStream(new JavaLexer(CharStreams.fromString(code))));
    DAGBuilder builder = new DAGBuilder();
    builder.setGraphScope("test-program", file);
    new ExceptionVisitor(builder, null).visit(parser.compilationUnit());
    assertEquals(0, parser.getNumberOfSyntaxErrors());
    return builder;
  }

  public void testOneEdgeInstanceBacksAdjacencyAndGlobalIndex() {
    DAGBuilder builder = build("class A { void m(){ int x=1; int y=x; } }", "EdgeIdentity.java");
    int edgeCount = builder.getAllEdges().size();
    assertEquals(List.of("eb.01", "ec.01", "ec.02", "ed.01"),
        builder.getAllEdges().stream().map(edge -> edge.label).toList());
    for (Edge edge : builder.getAllEdges()) {
      assertTrue(edge.from.edges.stream().anyMatch(candidate -> candidate == edge));
      assertSame(edge.from, edge.from.edges.stream()
          .filter(candidate -> candidate == edge).findFirst().orElseThrow().from);
      assertSame(edge.to, edge.from.edges.stream()
          .filter(candidate -> candidate == edge).findFirst().orElseThrow().to);
      assertNotNull(edge.label);
    }
    assertEquals(edgeCount, builder.getAllEdges().size());
    assertEquals(edgeCount, builder.getAllNodes().stream().mapToInt(node -> node.edges.size()).sum());
  }

  public void testCanonicalExportIncludesDisconnectedClassesAndMethods() {
    String code = "class A { void a(){int x=1;} void b(){int y=2;} }"
        + "class B { void c(){int z=3;} }";
    DAGBuilder builder = build(code, "Multiple.java");
    CanonicalGraph graph = CanonicalGraphExporter.export(builder, "multi", 3);
    assertEquals(builder.getAllNodes().size(), graph.nodes().size());
    assertEquals(builder.getAllEdges().size(), graph.edges().size());
    assertEquals(2, graph.nodes().stream().filter(node -> "CLASS_ENTRY".equals(node.type())).count());
    assertEquals(3, graph.nodes().stream().filter(node -> "METHOD_ENTRY".equals(node.type())).count());
    assertAllEndpointsExist(graph);
  }

  public void testSynthetic5000CanonicalExportIsComplete() throws Exception {
    Path source = Path.of("benchmarks", "Synthetic5000.java");
    assertTrue("Run Task 3 benchmark fixture generation first", Files.exists(source));
    DAGBuilder builder = build(Files.readString(source), source.toString());
    CanonicalGraph graph = CanonicalGraphExporter.export(builder, "synthetic-5000", 5000);
    assertEquals(builder.getAllNodes().size(), graph.metadata().nodeCount());
    assertEquals(builder.getAllEdges().size(), graph.metadata().edgeCount());
    assertEquals(builder.getAllNodes().size(), graph.nodes().size());
    assertEquals(builder.getAllEdges().size(), graph.edges().size());
    assertAllEndpointsExist(graph);
    JsonObject json = JsonParser.parseString(CanonicalGraphExporter.toJson(graph)).getAsJsonObject();
    assertEquals(builder.getAllNodes().size(), json.getAsJsonArray("nodes").size());
    assertEquals(builder.getAllEdges().size(), json.getAsJsonArray("edges").size());
  }

  private void assertAllEndpointsExist(CanonicalGraph graph) {
    Set<String> nodeIds = new HashSet<>();
    graph.nodes().forEach(node -> assertTrue(nodeIds.add(node.id())));
    Set<String> edgeIds = new HashSet<>();
    graph.edges().forEach(edge -> {
      assertTrue(edgeIds.add(edge.id()));
      assertTrue(nodeIds.contains(edge.source()));
      assertTrue(nodeIds.contains(edge.target()));
    });
  }
}
