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

  public void testInterfaceImplementationAndPolymorphicCall() {
    DAGBuilder b=build("interface P{int value();}class A implements P{public int value(){return 1;}}class B implements P{public int value(){return 2;}}class T{static int run(P p){return p.value();}}");
    assertEquals(4,nodes(b,"CLASS_ENTRY"));
    assertEquals(4,nodes(b,"METHOD_ENTRY"));
    assertEquals(2,edges(b,"INHERITANCE").size());
    assertEquals(2,edges(b,"POLYMORPHIC_METHOD_CALL").size());
    assertTrue(edges(b,"POLYMORPHIC_METHOD_CALL").stream().anyMatch(e->"A".equals(e.to.classId)));
    assertTrue(edges(b,"POLYMORPHIC_METHOD_CALL").stream().anyMatch(e->"B".equals(e.to.classId)));
  }

  public void testReturnValueParametersAndSummary() {
    DAGBuilder b = build("class A { int id(int x){return x;} void run(){int y=id(10);} }");
    assertEquals(1, nodes(b, "FORMAL_OUT"));
    assertEquals(1, nodes(b, "ACTUAL_OUT"));
    assertEquals(1, edges(b, "PARAMETER_OUT").size());
    assertEquals(1, edges(b, "SUMMARY").size());
    assertTrue(edges(b, "SUMMARY").get(0).label.startsWith("es."));
  }

  public void testNestedInternalCallIsRepresentedSeparately() {
    DAGBuilder b = build("class A { static int calculate(int x){return x+1;} void run(){System.out.println(calculate(2));} }");
    assertEquals(2, nodes(b, "CALL"));
    assertEquals(1, edges(b, "SIMPLE_METHOD_CALL").size());
    assertEquals(1, edges(b, "PARAMETER_IN").size());
    assertEquals(1, edges(b, "SUMMARY").size());
    assertTrue(edges(b, "DATA_DEPENDENCE").stream().anyMatch(e ->
        e.from.type.contains("ACTUAL_OUT") && e.to.type.contains("ACTUAL_IN")));
  }

  public void testSummaryTracksTransitiveLocalDefinitions() {
    DAGBuilder b = build("class A { static int calculate(int input){int x=input+1;int y=x*2;return y;} void run(){int result=calculate(2);} }");
    assertEquals(1, edges(b, "SUMMARY").size());
    assertTrue(edges(b, "SUMMARY").stream().anyMatch(e ->
        e.from.type.contains("ACTUAL_IN") && e.to.type.contains("ACTUAL_OUT")));
  }

  public void testSummaryTracksControlDependentReturnValue() {
    DAGBuilder b = build("class A { static int classify(int x){int r=0;if(x>0){r=1;}else{r=2;}return r;} void run(){int y=classify(1);} }");
    assertEquals(1, edges(b, "SUMMARY").size());
    assertTrue(edges(b, "SUMMARY").stream().anyMatch(e ->
        e.from.type.contains("ACTUAL_IN") && e.to.type.contains("ACTUAL_OUT")));
  }

  public void testActualOutDefinesVariableInitializedByCall() {
    DAGBuilder b = build("class A { static int twice(int x){return x*2;} static int calc(int x){int doubled=twice(x);return doubled+1;} void run(){int result=calc(4);System.out.println(result);} }");
    List<com.example.dag.graph.Node> outputs = b.getAllNodes().stream()
        .filter(n -> n.type.contains("ACTUAL_OUT")).toList();
    assertEquals(2, outputs.size());
    assertTrue(edges(b, "DATA_DEPENDENCE").stream().anyMatch(e ->
        e.from == outputs.get(0) && e.to.type.contains("STMT") && "calc".equals(e.to.methodId)));
    assertTrue(edges(b, "DATA_DEPENDENCE").stream().anyMatch(e ->
        e.from == outputs.get(1) && e.to.type.contains("ACTUAL_IN") && "run".equals(e.to.methodId)));
    assertFalse(edges(b, "DATA_DEPENDENCE").stream().anyMatch(e ->
        e.from.type.contains("CALL") && e.to.type.contains("ACTUAL_IN")));
    assertFalse(edges(b, "DATA_DEPENDENCE").stream().anyMatch(e ->
        e.to.type.contains("CALL")));
  }

  public void testPolymorphicCallReturnAndNestedArgumentDoNotBypassActualOut() {
    DAGBuilder b = build("class P { int value(){return 1;} } class C extends P { int value(){return 2;} } class T { static int invoke(P receiver){return receiver.value();} void run(){P receiver=new C();System.out.println(invoke(receiver));} }");
    com.example.dag.graph.Node invokeOutput = b.getAllNodes().stream()
        .filter(n -> n.type.contains("ACTUAL_OUT") && "invoke".equals(n.methodId)).findFirst().orElseThrow();
    com.example.dag.graph.Node invokeFormalOut = b.getAllNodes().stream()
        .filter(n -> n.type.contains("FORMAL_OUT") && "invoke".equals(n.methodId)).findFirst().orElseThrow();
    com.example.dag.graph.Node printlnInput = b.getAllNodes().stream()
        .filter(n -> n.type.contains("ACTUAL_IN") && "run".equals(n.methodId))
        .reduce((first, second) -> second).orElseThrow();
    com.example.dag.graph.Node receiverDefinition = b.getAllNodes().stream()
        .filter(n -> n.type.contains("VAR_DECL") && "run".equals(n.methodId)).findFirst().orElseThrow();
    assertTrue(edges(b, "DATA_DEPENDENCE").stream()
        .anyMatch(e -> e.from == invokeOutput && e.to == invokeFormalOut));
    assertTrue(edges(b, "DATA_DEPENDENCE").stream()
        .anyMatch(e -> e.from.type.contains("ACTUAL_OUT") && e.to == printlnInput));
    assertFalse(edges(b, "DATA_DEPENDENCE").stream()
        .anyMatch(e -> e.from == receiverDefinition && e.to == printlnInput));
  }
}
