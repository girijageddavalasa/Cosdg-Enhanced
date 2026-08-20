package com.example.dag;

import com.example.dag.coverage.CoverageAnalyzer;
import com.example.dag.coverage.CoverageAnalyzer.CoverageMarking;
import com.example.dag.coverage.CoverageAnalyzer.CoverageResult;
import com.example.dag.coverage.CoverageAnalyzer.MetricResult;
import com.example.dag.graph.Edge;
import com.example.dag.graph.Node;
import com.example.dag.visitor.ExceptionVisitor;
import junit.framework.TestCase;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class CoverageFormulaTest extends TestCase {
  private DAGBuilder build(String code) {
    JavaParser parser = new JavaParser(new CommonTokenStream(new JavaLexer(CharStreams.fromString(code))));
    DAGBuilder builder = new DAGBuilder();
    new ExceptionVisitor(builder, null).visit(parser.compilationUnit());
    assertEquals(0, parser.getNumberOfSyntaxErrors());
    return builder;
  }

  private CoverageResult analyze(DAGBuilder builder, Set<String> edges) {
    return CoverageAnalyzer.compute(Set.of(), edges, builder.getAllNodes(), builder.getAllEdges());
  }

  private Edge edge(DAGBuilder builder, String type) {
    return builder.getAllEdges().stream().filter(candidate -> type.equals(candidate.type)).findFirst().orElseThrow();
  }

  private MetricResult metric(CoverageResult result, String key) { return result.metric(key); }

  public void testOneMethodTwoStatementsUsesControlEdges() {
    DAGBuilder builder = build("class A { void m(){int x=1; int y=x;} }");
    Edge marked = builder.getAllEdges().stream().filter(e -> "CONTROL_DEPENDENCE".equals(e.type)
        && e.to.type.contains("VAR_DECL")).findFirst().orElseThrow();
    MetricResult statement = metric(analyze(builder, Set.of(marked.label)), "statement");
    assertEquals(1L, statement.numerator()); assertEquals(2L, statement.denominator());
    assertEquals(50.0, statement.percentage());
  }

  public void testIfElseStatementAndBranchPopulations() {
    DAGBuilder builder = build("class A { void m(int x){if(x>0){x=1;}else{x=2;}} }");
    Edge marked = builder.getAllEdges().stream().filter(e -> "true".equals(e.branch)).findFirst().orElseThrow();
    CoverageResult result = analyze(builder, Set.of(marked.label));
    assertEquals(1L, metric(result, "statement").numerator());
    assertEquals(3L, metric(result, "statement").denominator());
    assertEquals(2L, metric(result, "branch").denominator());
    assertFalse(metric(result, "branch").formulaImplemented());
  }

  public void testMethodAndMethodCallUseTheirOwnEdgeSets() {
    DAGBuilder builder = build("class A { void foo(){} void bar(){foo();} }");
    Edge member = edge(builder, "CLASS_MEMBER");
    Edge call = edge(builder, "SIMPLE_METHOD_CALL");
    CoverageResult result = analyze(builder, Set.of(member.label, call.label));
    assertEquals(1L, metric(result, "method").numerator()); assertEquals(2L, metric(result, "method").denominator());
    assertEquals(1L, metric(result, "methodCall").numerator()); assertEquals(1L, metric(result, "methodCall").denominator());
  }

  public void testInheritanceUsesVisibleMethodTagsAndTaggedMemberEdges() {
    DAGBuilder builder = build("class A { void foo(){} } class B extends A { void bar(){} }");
    Edge fooMember = builder.getAllEdges().stream().filter(e -> "CLASS_MEMBER".equals(e.type)
        && e.to.type.contains("A.foo")).findFirst().orElseThrow();
    CoverageMarking marking = new CoverageMarking(Set.of(), Set.of(), Set.of(fooMember.label), Map.of(), false);
    MetricResult inheritance = CoverageAnalyzer.compute(marking, builder.getAllNodes(), builder.getAllEdges()).metric("inheritance");
    assertEquals(1L, inheritance.numerator()); assertEquals(1L, inheritance.denominator());
    assertEquals(100.0, inheritance.percentage());
  }

  public void testPolymorphicCoverageUsesOnlyEm3() {
    DAGBuilder builder = build("class A { void foo(){} } class B extends A { void foo(){} } class T { void run(){A a=new B();a.foo();} }");
    List<Edge> polymorphic = builder.getAllEdges().stream().filter(e -> "POLYMORPHIC_METHOD_CALL".equals(e.type)).toList();
    MetricResult metric = metric(analyze(builder, Set.of(polymorphic.get(0).label)), "polymorphic");
    assertEquals(1L, metric.numerator()); assertEquals(2L, metric.denominator());
  }

  public void testThrowCatchPopulationsUseControlEe2AndEe1() {
    DAGBuilder builder = build("class A { void m(){try{throw new IOException();}catch(IOException e){}} }");
    Edge throwControl = builder.getAllEdges().stream().filter(e -> "CONTROL_DEPENDENCE".equals(e.type)
        && e.to.type.contains("THROW_STMT")).findFirst().orElseThrow();
    Edge catchEdge = edge(builder, "EXCEPTION_CATCH");
    Edge flowEdge = edge(builder, "EXCEPTION_THROW");
    Node throwNode = throwControl.to;
    CoverageMarking marking = new CoverageMarking(Set.of(throwNode.id),
        Set.of(throwControl.label, catchEdge.label, flowEdge.label), Set.of(),
        Map.of(throwNode.id, Set.of("IOException")), false);
    CoverageResult result = CoverageAnalyzer.compute(marking, builder.getAllNodes(), builder.getAllEdges());
    for (String key : List.of("throw", "catch", "exceptionType", "exceptionFlow")) {
      assertEquals(1L, metric(result, key).numerator()); assertEquals(1L, metric(result, key).denominator());
    }
  }

  public void testMultipleExceptionTypesCountPotentialTypesPerThrowVertex() {
    String code = "class MyException extends Exception{} class E1 extends MyException{} class E2 extends MyException{}"
        + " class A { void m(boolean f){try{MyException e=null;if(f){e=new E1();}else{e=new E2();}throw e;}catch(E1 e){}catch(E2 e){}} }";
    DAGBuilder builder = build(code);
    Node throwNode = builder.getAllNodes().stream().filter(n -> n.type.contains("THROW_STMT")).findFirst().orElseThrow();
    CoverageMarking marking = new CoverageMarking(Set.of(), Set.of(), Set.of(), Map.of(throwNode.id, Set.of("E1")), false);
    MetricResult result = CoverageAnalyzer.compute(marking, builder.getAllNodes(), builder.getAllEdges()).metric("exceptionType");
    assertEquals(1L, result.numerator()); assertEquals(2L, result.denominator());
  }

  public void testInterproceduralExceptionFlowUsesCalleeEe1() {
    DAGBuilder builder = build("class A { void foo(){throw new RuntimeException();} void bar(){try{foo();}catch(RuntimeException e){}} }");
    Edge flow = edge(builder, "EXCEPTION_THROW");
    MetricResult result = metric(analyze(builder, Set.of(flow.label)), "exceptionFlow");
    assertEquals(1L, result.numerator()); assertEquals(1L, result.denominator());
    assertTrue(flow.from.type.contains("THROW_STMT"));
  }

  public void testZeroPolymorphicPopulationIsNotApplicable() {
    DAGBuilder builder = build("class A { void m(){int x=1;} }");
    MetricResult result = metric(analyze(builder, Set.of()), "polymorphic");
    assertEquals(0L, result.denominator()); assertNull(result.percentage());
  }

  public void testZeroExceptionPopulationIsNotApplicable() {
    DAGBuilder builder = build("class A { void m(){int x=1;} }");
    CoverageMarking marking = new CoverageMarking(Set.of(), Set.of(), Set.of(), Map.of(), false);
    CoverageResult result = CoverageAnalyzer.compute(marking, builder.getAllNodes(), builder.getAllEdges());
    for (String key : List.of("throw", "catch", "exceptionType", "exceptionFlow")) {
      assertEquals(0L, metric(result, key).denominator()); assertNull(metric(result, key).percentage());
    }
  }
}
