package com.example.dag;

import com.example.dag.coverage.RuntimeCoverageSession;
import com.example.dag.graph.Edge;
import com.example.dag.graph.Node;
import com.example.dag.runtime.RuntimeExecutionService;
import com.example.dag.server.GraphWorkspace;
import com.example.dag.server.GraphWorkspace.SourceFile;
import junit.framework.TestCase;

import java.util.List;
import java.util.Set;

public class MultiFileRuntimeTest extends TestCase {
  private final RuntimeExecutionService execution = new RuntimeExecutionService();

  public void testTwoFilesCompileExecuteAndPreserveOwnership() {
    GraphWorkspace graph = graph(file("Helper.java", "class Helper{static void work(){int x=1;}}"),
        file("Main.java", "class Main{public static void main(String[]a){Helper.work();}}"));
    var run = run(graph);
    assertEquals("PASSED", run.status()); assertEquals(2, run.filesExecuted());
    assertTrue(nodes(graph).stream().anyMatch(n -> "Helper.java".equals(n.sourceFile) && "Helper".equals(n.classId)));
    assertTrue(nodes(graph).stream().anyMatch(n -> "Main.java".equals(n.sourceFile) && "Main".equals(n.classId)));
  }

  public void testCrossFileCallAndNestedCallsUseActualEdges() {
    GraphWorkspace graph = graph(
        file("A.java", "class A{static void foo(){B.bar();}}"),
        file("B.java", "class B{static void bar(){C.baz();}}"),
        file("C.java", "class C{static void baz(){int z=1;}}"),
        file("Main.java", "class Main{public static void main(String[]a){A.foo();}}"));
    var run = run(graph);
    List<Edge> calls = edges(graph).stream().filter(e -> e.type.endsWith("METHOD_CALL")).toList();
    assertEquals(3, calls.size()); assertTrue(calls.stream().allMatch(e -> run.visitedEdges().contains(e.label)));
    assertEquals(3, run.runtimeTargets().size());
  }

  public void testThreeFileInheritedMethodWithoutOverride() {
    GraphWorkspace graph = graph(file("Parent.java", "class Parent{void foo(){int x=1;}}"),
        file("Child.java", "class Child extends Parent{}"),
        file("Main.java", "class Main{public static void main(String[]a){Child c=new Child();c.foo();}}"));
    var run = run(graph); Edge inherited = edge(graph, "INHERITED_METHOD_CALL");
    assertTrue(run.visitedEdges().contains(inherited.label));
    assertTrue(run.visitedNodes().contains(inherited.to.id));
    assertEquals(1, run.taggedClassMemberEdges().size());
  }

  public void testCrossFileExceptionMarksCalleeEe1AndCallerEe2() {
    GraphWorkspace graph = graph(file("Worker.java", "class Worker{static void fail(){throw new RuntimeException();}}"),
        file("Main.java", "class Main{public static void main(String[]a){try{Worker.fail();}catch(RuntimeException e){}}}"));
    var run = run(graph);
    assertTrue(run.visitedEdges().contains(edge(graph, "EXCEPTION_THROW").label));
    assertTrue(run.visitedEdges().contains(edge(graph, "EXCEPTION_CATCH").label));
  }

  public void testBaseReferenceMarksOnlyChildOverride() {
    GraphWorkspace graph = graph(file("Parent.java", "class Parent{void foo(){}}"),
        file("Child.java", "class Child extends Parent{void foo(){int child=1;}}"),
        file("Main.java", "class Main{public static void main(String[]a){Parent p=new Child();p.foo();}}"));
    var run = run(graph);
    Edge child = polymorphicTarget(graph, "Child"), parent = polymorphicTarget(graph, "Parent");
    assertTrue(run.visitedEdges().contains(child.label)); assertFalse(run.visitedEdges().contains(parent.label));
    assertTrue(run.visitedNodes().contains(child.to.id)); assertFalse(run.visitedNodes().contains(parent.to.id));
    assertEquals(1L, run.coverage().metric("polymorphic").numerator());
    assertEquals(2L, run.coverage().metric("polymorphic").denominator());
  }

  public void testDogAndCatTargetsAccumulateInOneSession() {
    GraphWorkspace graph = animalGraph(); RuntimeCoverageSession session = session(graph);
    var dog = run(graph, "dog"); session.add("dog", dog);
    assertTrue(dog.visitedEdges().contains(polymorphicTarget(graph, "Dog").label));
    assertFalse(dog.visitedEdges().contains(polymorphicTarget(graph, "Cat").label));
    assertEquals(1L, session.summarize(nodes(graph), edges(graph)).coverage().metric("polymorphic").numerator());
    var cat = run(graph, "cat"); session.add("cat", cat);
    assertTrue(cat.visitedEdges().contains(polymorphicTarget(graph, "Cat").label));
    var metric = session.summarize(nodes(graph), edges(graph)).coverage().metric("polymorphic");
    assertEquals(2L, metric.numerator()); assertEquals(2L, metric.denominator()); assertEquals(100.0, metric.percentage());
  }

  public void testCompilationFailureAcrossFilesHasNoMarkings() {
    GraphWorkspace graph = graph(file("Broken.java", "class Broken{static void nope(){missing();}}"),
        file("Main.java", "class Main{public static void main(String[]a){Broken.nope();}}"));
    var run = runUnchecked(graph);
    assertEquals("COMPILE_ERROR", run.status()); assertEquals(2, run.filesExecuted());
    assertTrue(run.visitedNodes().isEmpty()); assertTrue(run.visitedEdges().isEmpty());
  }

  public void testRuntimeFailureAcrossFilesRetainsActualPartialMarkings() {
    GraphWorkspace graph = graph(file("Worker.java", "class Worker{static void fail(){int x=1;throw new IllegalStateException();}}"),
        file("Main.java", "class Main{public static void main(String[]a){Worker.fail();}}"));
    var run = runUnchecked(graph);
    assertEquals("RUNTIME_ERROR", run.status()); assertTrue(run.compilationSuccess());
    assertFalse(run.visitedNodes().isEmpty()); assertTrue(run.runtimeTargets().stream().anyMatch(id -> method(graph, id, "Worker", "fail")));
  }

  private GraphWorkspace animalGraph() {
    return graph(file("Animal.java", "abstract class Animal{abstract void speak();}"),
        file("Dog.java", "class Dog extends Animal{void speak(){int dog=1;}}"),
        file("Cat.java", "class Cat extends Animal{void speak(){int cat=1;}}"),
        file("Main.java", "class Main{public static void main(String[]a){Animal x;if(a[0].equals(\"dog\")){x=new Dog();}else{x=new Cat();}x.speak();}}"));
  }
  private RuntimeCoverageSession session(GraphWorkspace graph) { return new RuntimeCoverageSession(nodes(graph).size(), edges(graph).size()); }
  private RuntimeExecutionService.Result run(GraphWorkspace graph, String... args) { var result=runUnchecked(graph,args); assertEquals(result.stderr(),"PASSED",result.status()); return result; }
  private RuntimeExecutionService.Result runUnchecked(GraphWorkspace graph, String... args) { return execution.execute(graph.current(),new RuntimeExecutionService.Request("Main",List.of(args),5_000,"multi")); }
  private GraphWorkspace graph(SourceFile... files) { return new GraphWorkspace(List.of(files)); }
  private SourceFile file(String name,String source) { return new SourceFile(name,source); }
  private List<Node> nodes(GraphWorkspace graph) { return graph.current().builders().stream().flatMap(b->b.getAllNodes().stream()).toList(); }
  private List<Edge> edges(GraphWorkspace graph) { return graph.current().builders().stream().flatMap(b->b.getAllEdges().stream()).toList(); }
  private Edge edge(GraphWorkspace graph,String type) { return edges(graph).stream().filter(e->type.equals(e.type)).findFirst().orElseThrow(); }
  private Edge polymorphicTarget(GraphWorkspace graph,String owner) { return edges(graph).stream().filter(e->"POLYMORPHIC_METHOD_CALL".equals(e.type)&&owner.equals(e.to.classId)).findFirst().orElseThrow(); }
  private boolean method(GraphWorkspace graph,String nodeId,String owner,String method) { return nodes(graph).stream().anyMatch(n->n.id.equals(nodeId)&&owner.equals(n.classId)&&method.equals(n.methodId)); }
}
