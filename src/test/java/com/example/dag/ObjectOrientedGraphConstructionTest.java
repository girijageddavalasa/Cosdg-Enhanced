package com.example.dag;

import com.example.dag.graph.Edge;
import com.example.dag.visitor.ExceptionVisitor;
import junit.framework.TestCase;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.util.List;

public class ObjectOrientedGraphConstructionTest extends TestCase {

  private DAGBuilder build(String code) {
    JavaParser parser = new JavaParser(new CommonTokenStream(new JavaLexer(CharStreams.fromString(code))));
    DAGBuilder builder = new DAGBuilder();
    new ExceptionVisitor(builder, null).visit(parser.compilationUnit());
    assertEquals(0, parser.getNumberOfSyntaxErrors());
    return builder;
  }

  private long nodes(DAGBuilder b, String kind) {
    return b.getAllNodes().stream().filter(n -> n.type.contains(kind)).count();
  }

  private List<Edge> edges(DAGBuilder b, String kind) {
    return b.getAllEdges().stream().filter(e -> kind.equals(e.type)).toList();
  }

  public void testSimpleSameClassMethodCall() {
    DAGBuilder b = build("class A { void foo(){} void bar(){foo();} }");
    assertEquals(1, nodes(b, "CALL"));
    assertEquals(1, edges(b, "SIMPLE_METHOD_CALL").size());
    assertTrue(edges(b, "SIMPLE_METHOD_CALL").get(0).label.startsWith("em1."));
  }

  public void testMethodCallBetweenClasses() {
    DAGBuilder b = build("class A { void foo(){} } class T { void run(){A a=new A();a.foo();} }");
    assertEquals(1, nodes(b, "CALL"));
    assertEquals(1, edges(b, "SIMPLE_METHOD_CALL").size());
  }

  public void testMethodParameters() {
    DAGBuilder b = build("class A { void foo(int x){} void bar(){foo(10);} }");
    assertEquals(1, nodes(b, "FORMAL_IN"));
    assertEquals(1, nodes(b, "ACTUAL_IN"));
    assertEquals(1, edges(b, "PARAMETER_IN").size());
    assertTrue(edges(b, "PARAMETER_IN").get(0).label.startsWith("ep1."));
  }

  public void testInheritedMethodCall() {
    DAGBuilder b = build("class A { void foo(){} } class B extends A { void bar(){foo();} }");
    assertEquals(1, edges(b, "INHERITANCE").size());
    assertEquals(1, edges(b, "INHERITED_METHOD_CALL").size());
    assertTrue(edges(b, "INHERITED_METHOD_CALL").get(0).label.startsWith("em2."));
  }

  public void testMethodOverriding() {
    DAGBuilder b = build("class A { void foo(){} } class B extends A { void foo(){} void bar(){foo();} }");
    assertEquals(1, edges(b, "INHERITANCE").size());
    assertEquals(1, edges(b, "SIMPLE_METHOD_CALL").size());
    assertFalse(edges(b, "INHERITANCE").get(0).tags.contains("foo/0"));
  }

  public void testBaseTypedReferencePolymorphicCall() {
    DAGBuilder b = build("class A { void foo(){} } class B extends A { void foo(){} } class T { void run(){A a=new B();a.foo();} }");
    assertEquals(1, nodes(b, "CALL"));
    assertEquals(2, edges(b, "POLYMORPHIC_METHOD_CALL").size());
    assertTrue(edges(b, "POLYMORPHIC_METHOD_CALL").stream().allMatch(e -> e.label.startsWith("em3.")));
  }

  public void testReturnValueParametersAndSummary() {
    DAGBuilder b = build("class A { int id(int x){return x;} void run(){int y=id(10);} }");
    assertEquals(1, nodes(b, "FORMAL_OUT"));
    assertEquals(1, nodes(b, "ACTUAL_OUT"));
    assertEquals(1, edges(b, "PARAMETER_OUT").size());
    assertEquals(1, edges(b, "SUMMARY").size());
    assertTrue(edges(b, "SUMMARY").get(0).label.startsWith("es."));
  }
}
