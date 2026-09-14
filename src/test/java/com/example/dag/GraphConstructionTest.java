package com.example.dag;

import com.example.dag.graph.Edge;
import com.example.dag.graph.Node;
import com.example.dag.visitor.ExceptionVisitor;
import junit.framework.TestCase;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.util.List;

public class GraphConstructionTest extends TestCase {

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

  public void testSequentialStatementsDependOnMethodEntry() {
    DAGBuilder b = build("class A { void m(){ int x=1; x=2; } }");
    assertEquals(1, nodes(b, "CLASS_ENTRY"));
    assertEquals(1, nodes(b, "METHOD_ENTRY"));
    assertEquals(2, nodes(b, "VAR_DECL") + nodes(b, "STMT"));
    assertEquals(2, edges(b, "CONTROL_DEPENDENCE").size());
    assertFalse(edges(b, "CONTROL_DEPENDENCE").stream()
        .anyMatch(e -> e.from.type.contains("VAR_DECL") || e.from.type.contains("STMT")));
  }

  public void testIfElseControlDependence() {
    DAGBuilder b = build("class A { void m(int x){ if(x>0){x=1;}else{x=2;} x=3; } }");
    Node predicate = b.getAllNodes().stream().filter(n -> n.type.contains("IF_PREDICATE")).findFirst().orElseThrow();
    assertEquals(2, b.getAllEdges().stream().filter(e -> e.from == predicate).count());
    assertTrue(b.getAllEdges().stream().anyMatch(e -> e.from == predicate && "true".equals(e.branch)));
    assertTrue(b.getAllEdges().stream().anyMatch(e -> e.from == predicate && "false".equals(e.branch)));
    assertEquals(0, nodes(b, "END_IF"));
  }

  public void testNestedIfPreservesBothPredicates() {
    DAGBuilder b = build("class A { void m(int x,int y){ if(x>0){if(y>0){x=1;}} } }");
    assertEquals(2, nodes(b, "IF_PREDICATE"));
    List<Node> predicates = b.getAllNodes().stream().filter(n -> n.type.contains("IF_PREDICATE")).toList();
    assertTrue(b.getAllEdges().stream().anyMatch(e -> e.from == predicates.get(0) && e.to == predicates.get(1)));
  }

  public void testWhilePredicateAndBody() {
    DAGBuilder b = build("class A { void m(){ int i=0; while(i<2){i=i+1;} int z=i; } }");
    Node predicate = b.getAllNodes().stream().filter(n -> n.type.contains("LOOP_PREDICATE")).findFirst().orElseThrow();
    assertTrue(b.getAllEdges().stream().anyMatch(e -> e.from == predicate && "true".equals(e.branch)));
    assertTrue(edges(b, "DATA_DEPENDENCE").size() >= 2);
  }

  public void testLocalDefinitionUse() {
    DAGBuilder b = build("class A { void m(){ int x=1; int y=x; } }");
    assertEquals(1, edges(b, "DATA_DEPENDENCE").size());
    assertTrue(edges(b, "DATA_DEPENDENCE").get(0).label.startsWith("ed."));
  }

  public void testMethodsAreIsolated() {
    DAGBuilder b = build("class A { void a(){int x=1;} void b(){int y=2;} }");
    assertEquals(2, nodes(b, "METHOD_ENTRY"));
    assertEquals(2, edges(b, "CLASS_MEMBER").size());
    assertFalse(b.getAllEdges().stream().anyMatch(e ->
        e.from.type.contains("VAR_DECL") && e.to.type.contains("VAR_DECL")));
  }

  public void testClassesAreIsolated() {
    DAGBuilder b = build("class A { void a(){int x=1;} } class B { void b(){int y=2;} }");
    assertEquals(2, nodes(b, "CLASS_ENTRY"));
    assertEquals(2, nodes(b, "METHOD_ENTRY"));
    assertEquals(2, edges(b, "CLASS_MEMBER").size());
    assertFalse(b.getAllEdges().stream().anyMatch(e ->
        e.from.type.contains("VAR_DECL") && e.to.type.contains("VAR_DECL")));
  }

  public void testVarDeclarationUsesGrammarAlternativeSafely() {
    DAGBuilder b = build("class A { void m(){ var x = 1; int y=x; } }");
    assertEquals(2, nodes(b, "VAR_DECL"));
    assertEquals(1, edges(b, "DATA_DEPENDENCE").size());
  }

  public void testReturnVariableHasDefinitionUseDependence() {
    DAGBuilder b = build("class A { int m(int input){ int x=input+1; int y=x*2; return y; } }");
    Node yDefinition = b.getAllNodes().stream().filter(n -> n.sourceLine == 1 && n.type.contains("VAR_DECL"))
        .reduce((first, second) -> second).orElseThrow();
    Node returnStatement = b.getAllNodes().stream().filter(n -> n.type.contains("STMT")).findFirst().orElseThrow();
    assertTrue(edges(b, "DATA_DEPENDENCE").stream().anyMatch(e -> e.from == yDefinition && e.to == returnStatement));
    assertTrue(edges(b, "DATA_DEPENDENCE").stream().anyMatch(e -> e.from == returnStatement && e.to.type.contains("FORMAL_OUT")));
  }

  public void testBranchDefinitionsMergeAtFollowingUse() {
    DAGBuilder b = build("""
        class A {
          int m(int x) {
            int r = 0;
            if (x > 0) { r = 1; } else { r = 2; }
            return r;
          }
        }
        """);
    Node returnNode = b.getAllNodes().stream()
        .filter(n -> n.sourceLine == 5 && n.type.contains("STMT")).findFirst().orElseThrow();
    assertEquals(2, edges(b, "DATA_DEPENDENCE").stream()
        .filter(e -> e.to == returnNode && e.from.sourceLine == 4).count());
  }

  public void testLoopUpdateReachesPredicateAndExitUse() {
    DAGBuilder b = build("""
        class A {
          int m(int x) {
            int r = x;
            while (r < 3) { r++; }
            return r;
          }
        }
        """);
    Node predicate = b.getAllNodes().stream()
        .filter(n -> n.type.contains("LOOP_PREDICATE")).findFirst().orElseThrow();
    Node update = b.getAllNodes().stream()
        .filter(n -> n.sourceLine == 4 && n.type.contains("STMT")).findFirst().orElseThrow();
    Node returnNode = b.getAllNodes().stream()
        .filter(n -> n.sourceLine == 5 && n.type.contains("STMT")).findFirst().orElseThrow();
    assertTrue(edges(b, "DATA_DEPENDENCE").stream().anyMatch(e -> e.from == update && e.to == predicate));
    assertTrue(edges(b, "DATA_DEPENDENCE").stream().anyMatch(e -> e.from == update && e.to == returnNode));
    assertTrue(edges(b, "CONTROL_DEPENDENCE").stream()
        .anyMatch(e -> e.from == predicate && e.to == returnNode && "false".equals(e.branch)));
  }
}
