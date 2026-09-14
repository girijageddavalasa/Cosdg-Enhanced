package com.example.dag;

import com.example.dag.server.GraphProjectionService;
import com.example.dag.server.GraphProjectionService.Projection;
import com.example.dag.server.GraphProjectionService.Query;
import com.example.dag.server.GraphWorkspace;
import com.example.dag.server.GraphWorkspace.SourceFile;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class GraphWorkspaceTest extends TestCase {
  private final GraphProjectionService projections = new GraphProjectionService();

  private GraphWorkspace workspace(String source) {
    return new GraphWorkspace(List.of(new SourceFile("Sample.java", source)));
  }

  public void testSourceSubmissionReplacesSnapshotAndPreservesSource() {
    GraphWorkspace workspace = workspace("class A { void a(){} }");
    workspace.submit(List.of(new SourceFile("B.java", "class B { void b(){ int x=1; } }")));
    assertEquals("B.java", workspace.current().sources().get(0).name());
    assertTrue(workspace.current().sources().get(0).source().contains("class B"));
    assertEquals(1, workspace.current().statistics().statements());
  }

  public void testStatisticsMatchCanonicalGraph() {
    GraphWorkspace workspace = workspace("class A { void a(){int x=1; int y=x;} }");
    assertEquals(workspace.current().graph().nodes().size(), workspace.current().statistics().nodes());
    assertEquals(workspace.current().graph().edges().size(), workspace.current().statistics().edges());
    assertEquals(1, workspace.current().statistics().classes());
    assertEquals(1, workspace.current().statistics().methods());
  }

  public void testCompleteCanonicalJsonExport() {
    GraphWorkspace workspace = workspace("class A { void a(){int x=1;} } class B { void b(){int y=2;} }");
    JsonObject json = JsonParser.parseString(workspace.current().canonicalJson()).getAsJsonObject();
    assertEquals(workspace.current().statistics().nodes(), json.getAsJsonArray("nodes").size());
    assertEquals(workspace.current().statistics().edges(), json.getAsJsonArray("edges").size());
  }

  public void testDisconnectedClassListingIsComplete() {
    GraphWorkspace workspace = workspace("class A { void a(){} } class B { void b(){} }");
    var classes = projections.classes(workspace.current().graph());
    assertEquals(2, classes.size());
    assertEquals(List.of("A", "B"), classes.stream().map(GraphProjectionService.ClassItem::name).toList());
  }

  public void testMethodListingUsesCanonicalOwnership() {
    GraphWorkspace workspace = workspace("class A { void first(){} void second(){} }");
    var methods = projections.methods(workspace.current().graph(), "Sample.java", "A");
    assertEquals(List.of("first", "second"), methods.stream().map(GraphProjectionService.MethodItem::name).toList());
  }

  public void testMethodProjectionIsBoundedAndOwned() {
    GraphWorkspace workspace = workspace("class A { void first(){int x=1; int y=x;} void second(){int z=2;} }");
    Projection graph = projections.project(workspace.current().graph(),
        new Query("Sample.java", "A", "first", null, null, null, 100, 200, 1));
    assertTrue(graph.nodes().stream().anyMatch(node -> "METHOD_ENTRY".equals(node.type())));
    assertFalse(graph.nodes().stream().anyMatch(node -> "second".equals(node.methodId())));
  }

  public void testNodeLimitReportsExplicitTruncation() {
    GraphWorkspace workspace = workspace("class A { void a(){int a=1;int b=2;int c=3;int d=4;} }");
    Projection graph = projections.project(workspace.current().graph(),
        new Query(null, null, null, null, null, null, 2, 2, 1));
    assertEquals(2, graph.nodes().size());
    assertTrue(graph.truncated());
    assertTrue(graph.message().contains("Showing 2 nodes"));
  }

  public void testPerformanceMetadataSeparatesMeasuredAndTheoretical() {
    GraphWorkspace workspace = workspace("class A { void a(){int x=1;} }");
    var stats = workspace.current().statistics();
    assertTrue(stats.measured().parseMs() >= 0);
    assertTrue(stats.measured().graphMs() >= 0);
    assertTrue(stats.measured().jsonBytes() > 0);
    assertEquals("O(S)", stats.theoreticalTime());
    assertEquals("O(S)", stats.theoreticalSpace());
  }

  public void testResearchMetadataIsExplicitAndFingerprintScoped() {
    GraphWorkspace workspace = new GraphWorkspace();
    workspace.submit(List.of(new SourceFile("Sample.java", "class A { void a(){int x=1;} }")), 1, false, true);
    String fingerprint = com.example.dag.coverage.CoverageObligationEngine.fingerprint(workspace.current());
    var research = GraphWorkspace.researchMetadata(workspace.current(), fingerprint);
    assertEquals("COSDG_RESEARCH_METADATA_V1", research.schemaVersion());
    assertEquals(fingerprint, research.graphFingerprint());
    assertEquals(workspace.current().statistics(), research.statistics());
    assertNotNull(research.generatedAtUtc());
    assertTrue(research.environment().availableProcessors() > 0);
    assertTrue(research.environment().maximumJvmHeapBytes() > 0);
    assertTrue(research.statistics().measured().heapAfterParseBytes() > 0);
    assertTrue(research.statistics().measured().heapAfterGraphBytes() > 0);
    assertTrue(research.statistics().measured().memoryStabilized());
    assertTrue(research.methodology().heapApproximate());
    assertTrue(research.methodology().excluded().contains("browser layout"));
    JsonObject json = JsonParser.parseString(new com.google.gson.Gson().toJson(research)).getAsJsonObject();
    assertTrue(json.getAsJsonObject("statistics").getAsJsonObject("measured").has("graphHeapBytes"));
    assertTrue(json.getAsJsonObject("statistics").getAsJsonObject("measured").has("jsonExportMs"));
    assertEquals("O(S)", json.getAsJsonObject("statistics").get("theoreticalTime").getAsString());
  }

  public void testSynthetic5000QueryNeverReturnsWholeGraph() throws Exception {
    Path source = Path.of("benchmarks", "Synthetic5000.java");
    assertTrue(Files.exists(source));
    GraphWorkspace workspace = new GraphWorkspace(List.of(new SourceFile(source.toString(), Files.readString(source))));
    int authoritative = workspace.current().statistics().nodes();
    Projection graph = projections.project(workspace.current().graph(),
        new Query(null, null, null, null, null, null, 100, 200, 1));
    assertTrue(authoritative > 5000);
    assertEquals(100, graph.nodes().size());
    assertTrue(graph.nodes().size() < authoritative);
    assertTrue(graph.truncated());
    assertEquals(authoritative, graph.matchingNodes());
  }
}
