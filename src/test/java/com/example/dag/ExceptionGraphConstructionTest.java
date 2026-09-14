package com.example.dag;

import com.example.dag.graph.Edge;
import com.example.dag.graph.Node;
import com.example.dag.visitor.ExceptionVisitor;
import junit.framework.TestCase;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.util.List;

public class ExceptionGraphConstructionTest extends TestCase {

  private DAGBuilder build(String code) {
    JavaParser parser = new JavaParser(new CommonTokenStream(new JavaLexer(CharStreams.fromString(code))));
    DAGBuilder builder = new DAGBuilder();
    new ExceptionVisitor(builder, null).visit(parser.compilationUnit());
    assertEquals(0, parser.getNumberOfSyntaxErrors());
    return builder;
  }

  private List<Node> nodes(DAGBuilder b, String type) {
    return b.getAllNodes().stream().filter(n -> n.type.contains(type)).toList();
  }

  private List<Edge> edges(DAGBuilder b, String type) {
    return b.getAllEdges().stream().filter(e -> type.equals(e.type)).toList();
  }

  private Node catchFor(DAGBuilder b, String type) {
    return nodes(b, "CATCH_START").stream().filter(n -> n.exceptionTypes.contains(type)).findFirst().orElseThrow();
  }

  public void testLocalThrowCatch() {
    DAGBuilder b = build("class A { void m(){try{throw new IOException();}catch(IOException e){int x=1;}} }");
    assertEquals(1, nodes(b, "TRY_BLOCK_START").size());
    assertEquals(1, nodes(b, "CATCH_START").size());
    assertEquals(1, edges(b, "EXCEPTION_CATCH").size());
    assertEquals(1, edges(b, "EXCEPTION_THROW").size());
    Edge edge = edges(b, "EXCEPTION_THROW").get(0);
    assertTrue(edge.from.type.contains("THROW_STMT"));
    assertSame(catchFor(b, "IOException"), edge.to);
    assertEquals("IOException", edge.exceptionType);
  }

  public void testMultipleTypedCatches() {
    DAGBuilder b = build("class A { void m(){try{throw new IOException();}catch(IOException e){}catch(NullPointerException e){}} }");
    assertEquals(2, edges(b, "EXCEPTION_CATCH").size());
    assertEquals(1, edges(b, "EXCEPTION_THROW").size());
    assertSame(catchFor(b, "IOException"), edges(b, "EXCEPTION_THROW").get(0).to);
  }

  public void testCompatibleSuperclassCatch() {
    DAGBuilder b = build("class A { void m(){try{throw new IOException();}catch(Exception e){}} }");
    assertEquals(1, edges(b, "EXCEPTION_THROW").size());
    assertEquals("IOException", edges(b, "EXCEPTION_THROW").get(0).exceptionType);
  }

  public void testIncompatibleCatchDoesNotCreateThrowEdge() {
    DAGBuilder b = build("class A { void m(){try{throw new IOException();}catch(NullPointerException e){}} }");
    assertEquals(0, edges(b, "EXCEPTION_THROW").size());
    assertTrue(nodes(b, "METHOD_ENTRY").get(0).exceptionTypes.contains("IOException"));
  }

  public void testNestedTryUsesNearestCompatibleCatch() {
    DAGBuilder b = build("class A { void m(){try{try{throw new IOException();}catch(IOException e){}}catch(Exception e){}} }");
    assertEquals(2, edges(b, "EXCEPTION_CATCH").size());
    assertEquals(1, edges(b, "EXCEPTION_THROW").size());
    assertSame(catchFor(b, "IOException"), edges(b, "EXCEPTION_THROW").get(0).to);
  }

  public void testCalleeThrowConnectsToCallerCatch() {
    DAGBuilder b = build("class A { void foo(){throw new RuntimeException();} void bar(){try{foo();}catch(RuntimeException e){}} }");
    assertEquals(1, edges(b, "EXCEPTION_THROW").size());
    Edge edge = edges(b, "EXCEPTION_THROW").get(0);
    assertTrue(edge.from.type.contains("THROW_STMT"));
    assertFalse(edge.from.type.contains("CALL"));
    assertSame(catchFor(b, "RuntimeException"), edge.to);
  }

  public void testCalleeThrowWithIncompatibleCallerHandlerEscapes() {
    DAGBuilder b = build("class A { void foo(){throw new IOException();} void bar(){try{foo();}catch(NullPointerException e){}} }");
    assertEquals(0, edges(b, "EXCEPTION_THROW").size());
    Node bar = nodes(b, "METHOD_ENTRY").stream().filter(n -> n.type.contains("A.bar")).findFirst().orElseThrow();
    assertTrue(bar.exceptionTypes.contains("IOException"));
  }

  public void testMultiplePossibleExceptionTypes() {
    String code = "class MyException extends Exception{} class E1 extends MyException{} class E2 extends MyException{}"
        + " class A { void m(boolean flag){try{MyException e=null;if(flag){e=new E1();}else{e=new E2();}throw e;}"
        + "catch(E1 e){}catch(E2 e){}} }";
    DAGBuilder b = build(code);
    assertEquals(2, edges(b, "EXCEPTION_THROW").size());
    assertTrue(edges(b, "EXCEPTION_THROW").stream().anyMatch(e -> "E1".equals(e.exceptionType) && e.to == catchFor(b, "E1")));
    assertTrue(edges(b, "EXCEPTION_THROW").stream().anyMatch(e -> "E2".equals(e.exceptionType) && e.to == catchFor(b, "E2")));
  }

  public void testTryFinallyAddsNoInventedExceptionVertex() {
    DAGBuilder b = build("class A { void m(){try{int x=1;}finally{int y=2;}} }");
    assertEquals(1, nodes(b, "TRY_BLOCK_START").size());
    assertEquals(0, nodes(b, "CATCH_START").size());
    assertEquals(0, nodes(b, "FINALLY_START").size());
    assertEquals(0, edges(b, "EXCEPTION_CATCH").size());
  }

  public void testExceptionPropagatesAcrossNestedCalls() {
    DAGBuilder b = build("class A { void leaf(){throw new IOException();} void middle(){leaf();} void top(){try{middle();}catch(Exception e){}} }");
    assertEquals(1, edges(b, "EXCEPTION_THROW").size());
    Edge edge = edges(b, "EXCEPTION_THROW").get(0);
    assertTrue(edge.from.type.contains("THROW_STMT"));
    assertSame(catchFor(b, "Exception"), edge.to);
  }

  public void testLiteralTextDoesNotCreateFalseDataDependenceAndCatchLineIsAccurate() {
    DAGBuilder b = build("""
        class A {
          int validate(int value) {
            try {
              if (value < 0) throw new IllegalArgumentException("negative value");
              return value;
            } catch (IllegalArgumentException exception) {
              return -1;
            }
          }
        }
        """);
    Node throwNode = nodes(b, "THROW_STMT").get(0);
    Node formalIn = nodes(b, "FORMAL_IN").get(0);
    assertFalse(edges(b, "DATA_DEPENDENCE").stream()
        .anyMatch(e -> e.from == formalIn && e.to == throwNode));
    assertEquals(6, catchFor(b, "IllegalArgumentException").sourceLine);
  }
}
