package com.example.dag;

import com.example.dag.coverage.CoverageAnalyzer.MetricResult;
import com.example.dag.coverage.RuntimeCoverageSession;
import com.example.dag.runtime.RuntimeExecutionService;
import com.example.dag.server.GraphWorkspace;
import junit.framework.TestCase;

import java.util.List;

public class RuntimeCoverageIntegrationTest extends TestCase {
  private final RuntimeExecutionService service = new RuntimeExecutionService();

  public void testBothBranchesAccumulateInOneSession() {
    GraphWorkspace workspace = graph("""
        class Test { public static void main(String[] args) {
          int x=Integer.parseInt(args[0]); if(x>0){x++;}else{x--;}
        }}
        """);
    RuntimeCoverageSession session = session(workspace);
    session.add("positive", execute(workspace, "1"));
    MetricResult first = metric(session, workspace, "statement");
    session.add("negative", execute(workspace, "-1"));
    MetricResult both = metric(session, workspace, "statement");
    assertEquals(3L, first.numerator()); assertEquals(4L, first.denominator());
    assertEquals(4L, both.numerator()); assertEquals(4L, both.denominator());
    assertEquals(2, session.summarize(nodes(workspace), edges(workspace)).testCount());
  }

  public void testInheritedCallMarksMethodCallAndInheritanceObligations() {
    GraphWorkspace workspace = graph("""
        class A { void foo(){int x=1;} } class B extends A {}
        class Test { public static void main(String[] args){B b=new B();b.foo();} }
        """);
    RuntimeCoverageSession session = session(workspace); session.add("inherited", execute(workspace));
    MetricResult calls = metric(session, workspace, "methodCall");
    MetricResult inheritance = metric(session, workspace, "inheritance");
    assertEquals(1L, calls.numerator()); assertEquals(1L, calls.denominator());
    assertEquals(1L, inheritance.numerator()); assertEquals(1L, inheritance.denominator());
  }

  public void testPolymorphicRuntimeMetricMarksOnlyActualTarget() {
    GraphWorkspace workspace = graph("""
        class A { void foo(){} } class B extends A { void foo(){} }
        class Test { public static void main(String[] args){A a=new B();a.foo();} }
        """);
    RuntimeCoverageSession session = session(workspace); session.add("polymorphic", execute(workspace));
    MetricResult polymorphic = metric(session, workspace, "polymorphic");
    assertEquals(1L, polymorphic.numerator()); assertEquals(2L, polymorphic.denominator());
    assertTrue(polymorphic.markingAvailable()); assertEquals(50.0, polymorphic.percentage());
    assertTrue(metric(session, workspace, "methodCall").markingAvailable());
  }

  public void testInterfacePolymorphicRuntimeMarksConcreteImplementation() {
    GraphWorkspace workspace=graph("interface P{void f();}class A implements P{public void f(){int x=1;}}class B implements P{public void f(){int x=2;}}class Test{public static void main(String[]a){P p=new B();p.f();}}");
    RuntimeCoverageSession session=session(workspace);RuntimeExecutionService.Result run=execute(workspace);session.add("interface-poly",run);
    MetricResult polymorphic=metric(session,workspace,"polymorphic");
    assertEquals(1L,polymorphic.numerator());assertEquals(2L,polymorphic.denominator());
    assertTrue(run.runtimeTargetMethods().stream().anyMatch(target->target.contains("B")||target.contains("ve2_")));
  }

  public void testNestedInternalCallMarksMethodCallEdge() {
    GraphWorkspace workspace=graph("class Test{static int value(){return 1;}public static void main(String[]a){System.out.println(value());}}");
    RuntimeCoverageSession session=session(workspace);session.add("nested",execute(workspace));
    MetricResult calls=metric(session,workspace,"methodCall");assertEquals(1L,calls.numerator());assertEquals(1L,calls.denominator());
  }

  public void testOneOfMultipleCatchTypesUsesEe2Population() {
    GraphWorkspace workspace = graph("""
        import java.io.IOException;
        class Test { public static void main(String[] args){
          try{throw new IOException();}catch(IOException e){}catch(RuntimeException e){}
        }}
        """);
    RuntimeCoverageSession session = session(workspace); session.add("io", execute(workspace));
    MetricResult catches = metric(session, workspace, "catch");
    assertEquals(1L, catches.numerator()); assertEquals(2L, catches.denominator());
    assertEquals(50.0, catches.percentage());
  }

  public void testTwoExceptionRunsAccumulateEe1Ee2AndTypes() {
    GraphWorkspace workspace = graph("""
        import java.io.IOException;
        class Test {
          static void io() throws IOException {throw new IOException();}
          static void runtime(){throw new RuntimeException();}
          public static void main(String[] args){if(args[0].equals("io")){try{io();}catch(IOException e){}}
            else{try{runtime();}catch(RuntimeException e){}}}
        }
        """);
    RuntimeCoverageSession session = session(workspace);
    session.add("io", execute(workspace, "io"));
    assertEquals(1L, metric(session, workspace, "exceptionFlow").numerator());
    session.add("runtime", execute(workspace, "runtime"));
    for (String key : List.of("catch", "exceptionType", "exceptionFlow")) {
      MetricResult metric = metric(session, workspace, key);
      assertEquals(key, 2L, metric.numerator()); assertEquals(key, 2L, metric.denominator());
    }
  }

  public void testZeroDenominatorsRemainNotApplicableAtRuntime() {
    GraphWorkspace workspace = graph("class Test{public static void main(String[]a){int x=1;}}");
    RuntimeCoverageSession session = session(workspace); session.add("plain", execute(workspace));
    for (String key : List.of("polymorphic", "inheritance", "catch", "exceptionType", "exceptionFlow")) {
      MetricResult metric = metric(session, workspace, key);
      assertEquals(key, 0L, metric.denominator()); assertNull(key, metric.percentage());
    }
  }

  private GraphWorkspace graph(String source) { return new GraphWorkspace(List.of(new GraphWorkspace.SourceFile("Test.java", source))); }
  private RuntimeCoverageSession session(GraphWorkspace workspace) { return new RuntimeCoverageSession(nodes(workspace).size(), edges(workspace).size()); }
  private RuntimeExecutionService.Result execute(GraphWorkspace workspace, String... args) {
    RuntimeExecutionService.Result result = service.execute(workspace.current(),
        new RuntimeExecutionService.Request("Test", List.of(args), 5_000, "test"));
    assertEquals(result.stderr(), "PASSED", result.status()); return result;
  }
  private MetricResult metric(RuntimeCoverageSession session, GraphWorkspace workspace, String key) {
    return session.summarize(nodes(workspace), edges(workspace)).coverage().metric(key);
  }
  private List<com.example.dag.graph.Node> nodes(GraphWorkspace workspace) { return workspace.current().builders().stream().flatMap(b -> b.getAllNodes().stream()).toList(); }
  private List<com.example.dag.graph.Edge> edges(GraphWorkspace workspace) { return workspace.current().builders().stream().flatMap(b -> b.getAllEdges().stream()).toList(); }
}
