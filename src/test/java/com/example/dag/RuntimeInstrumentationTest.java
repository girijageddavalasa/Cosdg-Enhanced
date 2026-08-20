package com.example.dag;

import com.example.dag.graph.Edge;
import com.example.dag.graph.Node;
import com.example.dag.runtime.RuntimeExecutionService;
import com.example.dag.server.GraphWorkspace;
import junit.framework.TestCase;

import java.util.List;

public class RuntimeInstrumentationTest extends TestCase {
  private final RuntimeExecutionService service = new RuntimeExecutionService();

  public void testStatementsAndMethodEntryAreObserved() {
    Run run = run("""
        class Test { public static void main(String[] args) { int x = 1; int y = x + 1; } }
        """, List.of());
    assertEquals(run.result.stderr(), "PASSED", run.result.status());
    assertEquals(2, countCovered(run, "VAR_DECL"));
    assertEquals(1, countCovered(run, "METHOD_ENTRY"));
    assertEquals("RUNTIME_OBSERVED", run.result.coverage().measurementKind());
    assertEquals(2L, run.result.coverage().metric("statement").numerator());
    assertEquals(2L, run.result.coverage().metric("statement").denominator());
    assertEquals(1L, run.result.coverage().metric("method").numerator());
    assertEquals(1L, run.result.coverage().metric("method").denominator());
    assertTrue(run.result.instrumentationMap().keySet().stream().anyMatch(id -> id.startsWith("N:")));
    assertTrue(run.result.instrumentationMap().keySet().stream().anyMatch(id -> id.startsWith("E:")));
    assertFalse(run.workspace.current().sources().get(0).source().contains("__CosdgRuntime"));
  }

  public void testOppositeIfBranchesProduceDifferentDeterministicEdges() {
    String source = """
        class Test { public static void main(String[] args) {
          int x = Integer.parseInt(args[0]);
          if (x > 0) { x++; } else { x--; }
        } }
        """;
    Run positive = run(source, List.of("1"));
    Run repeat = run(source, List.of("1"));
    Run negative = run(source, List.of("-1"));
    assertEquals(positive.result.stderr(), "PASSED", positive.result.status());
    assertEquals(positive.result.visitedEdges(), repeat.result.visitedEdges());
    Edge yes = edge(positive, "CONTROL_DEPENDENCE", "true");
    Edge no = edge(positive, "CONTROL_DEPENDENCE", "false");
    assertTrue(positive.result.visitedEdges().contains(yes.label));
    assertFalse(positive.result.visitedEdges().contains(no.label));
    assertTrue(negative.result.visitedEdges().contains(no.label));
    assertFalse(negative.result.visitedEdges().contains(yes.label));
    assertEquals(3L, positive.result.coverage().metric("statement").numerator());
    assertEquals(4L, positive.result.coverage().metric("statement").denominator());
    assertEquals(3L, negative.result.coverage().metric("statement").numerator());
    assertEquals(4L, negative.result.coverage().metric("statement").denominator());
  }

  public void testCallEdgeAndCalleeEntryAreObserved() {
    Run run = run("""
        class Test {
          static void foo() { int x = 1; }
          public static void main(String[] args) { foo(); }
        }
        """, List.of());
    assertEquals(run.result.stderr(), "PASSED", run.result.status());
    Edge call = edge(run, "SIMPLE_METHOD_CALL", null);
    assertTrue(run.result.visitedEdges().contains(call.label));
    assertEquals(2, countCovered(run, "METHOD_ENTRY"));
    assertEquals(1L, run.result.coverage().metric("methodCall").numerator());
    assertEquals(1L, run.result.coverage().metric("methodCall").denominator());
  }

  public void testLocalThrowCatchMarksEe1AndEe2() {
    Run run = run("""
        class Test { public static void main(String[] args) {
          try { throw new IllegalArgumentException(); }
          catch (IllegalArgumentException e) { System.out.println("caught"); }
        } }
        """, List.of());
    assertEquals(run.result.stderr(), "PASSED", run.result.status());
    assertEquals("caught", run.result.stdout());
    assertTrue(run.result.visitedEdges().contains(edge(run, "EXCEPTION_THROW", null).label));
    assertTrue(run.result.visitedEdges().contains(edge(run, "EXCEPTION_CATCH", null).label));
    for (String key : List.of("throw", "catch", "exceptionType", "exceptionFlow")) {
      assertEquals(key, 1L, run.result.coverage().metric(key).numerator());
      assertEquals(key, 1L, run.result.coverage().metric(key).denominator());
    }
  }

  public void testInterproceduralThrowMarksCalleeToCallerHandler() {
    Run run = run("""
        class Test {
          static void foo() { throw new RuntimeException(); }
          public static void main(String[] args) {
            try { foo(); } catch (RuntimeException e) { System.out.println("caught"); }
          }
        }
        """, List.of());
    assertEquals(run.result.stderr(), "PASSED", run.result.status());
    Edge exceptional = edge(run, "EXCEPTION_THROW", null);
    assertTrue(run.result.visitedEdges().contains(exceptional.label));
    assertTrue(run.result.visitedEdges().contains(edge(run, "EXCEPTION_CATCH", null).label));
    assertEquals(1L, run.result.coverage().metric("exceptionFlow").numerator());
    assertEquals(1L, run.result.coverage().metric("exceptionFlow").denominator());
  }

  public void testCompileFailureDoesNotCreateCoverage() {
    Run run = run("class Test { public static void main(String[] args) { unknown(); } }", List.of());
    assertEquals("COMPILE_ERROR", run.result.status());
    assertTrue(run.result.visitedNodes().isEmpty());
    assertTrue(run.result.visitedEdges().isEmpty());
  }

  public void testTimeoutStopsChildProcess() {
    GraphWorkspace workspace = workspace("class Test { public static void main(String[] args) { while (true) {} } }");
    RuntimeExecutionService.Result result = service.execute(workspace.current(),
        new RuntimeExecutionService.Request("Test", List.of(), 150, "timeout"));
    assertEquals("TIMEOUT", result.status());
    assertTrue(result.timedOut());
  }

  private Run run(String source, List<String> args) {
    GraphWorkspace workspace = workspace(source);
    RuntimeExecutionService.Result result = service.execute(workspace.current(),
        new RuntimeExecutionService.Request("Test", args, 5_000, "test"));
    return new Run(workspace, result);
  }
  private GraphWorkspace workspace(String source) {
    return new GraphWorkspace(List.of(new GraphWorkspace.SourceFile("Test.java", source)));
  }
  private long countCovered(Run run, String type) {
    return run.workspace.current().builders().get(0).getAllNodes().stream()
        .filter(node -> semantic(node).equals(type) && run.result.visitedNodes().contains(node.id)).count();
  }
  private Edge edge(Run run, String type, String branch) {
    return run.workspace.current().builders().get(0).getAllEdges().stream()
        .filter(e -> type.equals(e.type) && (branch == null || branch.equals(e.branch))).findFirst().orElseThrow();
  }
  private String semantic(Node node) { String[] parts = node.type.split("<br/?>"); return parts.length > 1 ? parts[1] : node.type; }
  private record Run(GraphWorkspace workspace, RuntimeExecutionService.Result result) {}
}
