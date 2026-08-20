package com.example.dag;

import com.example.dag.graph.Node;
import com.example.dag.server.GraphWorkspace;
import com.example.dag.server.GraphWorkspace.SourceFile;
import com.example.dag.testcase.GeneratedTestCase;
import com.example.dag.testcase.MinCoverageSelector;
import com.example.dag.testcase.PathEnumerator;
import com.example.dag.testcase.PathEnumerator.EnumerationResult;
import com.example.dag.testcase.PathEnumerator.Options;
import com.google.gson.GsonBuilder;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public class PathEnumerationTest extends TestCase {
  public void testSequentialGraphHasOneCompletePath() { EnumerationResult r=enumerate(chain(5)); assertEquals(1,r.paths().size());assertFalse(r.truncated());assertEquals(5,r.maximumPathLength()); }
  public void testIfElseHasTwoPaths() { Node root=node("r"),a=node("a"),b=node("b");edge(root,a);edge(root,b);assertEquals(2,enumerate(root).paths().size()); }
  public void testNestedIfHasFourPaths() { Node r=node("r"),a=node("a"),b=node("b");edge(r,a);edge(r,b);edge(a,node("a1"));edge(a,node("a2"));edge(b,node("b1"));edge(b,node("b2"));assertEquals(4,enumerate(r).paths().size()); }
  public void testWhileIsBoundedAtSecondVisit() { Node p=node("p"),body=node("body");edge(p,body);edge(p,p);EnumerationResult r=enumerate(p);assertEquals(2,r.paths().size());assertTrue(r.paths().stream().anyMatch(path->path.nodeIds.equals(List.of("p","p")))); }
  public void testNestedLoopsTerminate() { Node a=node("a"),b=node("b"),leaf=node("leaf");edge(a,b);edge(a,a);edge(b,leaf);edge(b,b);EnumerationResult r=enumerate(a);assertFalse(r.paths().isEmpty());assertTrue(r.maximumPathLength()<=4); }
  public void testArbitraryCycleTerminates() { Node a=node("a"),b=node("b");edge(a,b);edge(b,a);EnumerationResult r=enumerate(a);assertEquals(List.of("a","b","a"),r.paths().get(0).nodeIds); }
  public void testDisconnectedComponentsAreEnumerated() { EnumerationResult r=PathEnumerator.enumerate(List.of(chain(2),chainNamed("x",3)),Options.defaults());assertEquals(2,r.paths().size());assertEquals(2,r.componentsStarted()); }
  public void testPathLimitReportsTruncated() { EnumerationResult r=PathEnumerator.enumerate(List.of(binary(8)),new Options(10,100,10000,5000,()->false));assertEquals(10,r.paths().size());assertEquals(PathEnumerator.StopReason.PATH_LIMIT,r.stopReason());assertTrue(r.truncated()); }
  public void testPathLengthLimitReportsTruncated() { EnumerationResult r=PathEnumerator.enumerate(List.of(chain(20)),new Options(10,5,100,5000,()->false));assertEquals(5,r.maximumPathLength());assertEquals(PathEnumerator.StopReason.PATH_LENGTH_LIMIT,r.stopReason()); }
  public void testTraversalStateLimitReportsTruncated() { EnumerationResult r=PathEnumerator.enumerate(List.of(binary(10)),new Options(5000,100,7,5000,()->false));assertEquals(7L,r.traversalStates());assertEquals(PathEnumerator.StopReason.TRAVERSAL_STATE_LIMIT,r.stopReason()); }
  public void testCancellationIsExplicit() { AtomicBoolean cancel=new AtomicBoolean(true);EnumerationResult r=PathEnumerator.enumerate(List.of(binary(10)),new Options(5000,100,10000,5000,cancel::get));assertEquals(PathEnumerator.Status.CANCELLED,r.status());assertEquals(PathEnumerator.StopReason.CANCELLED,r.stopReason()); }
  public void testTimeBudgetReportsTruncated() { EnumerationResult r=PathEnumerator.enumerate(List.of(binary(10)),new Options(5000,100,10000,1,()->{LockSupport.parkNanos(2_000_000);return false;}));assertEquals(PathEnumerator.Status.TRUNCATED,r.status());assertEquals(PathEnumerator.StopReason.TIME_LIMIT,r.stopReason()); }
  public void testSynthetic5000IsLengthBounded() throws Exception { GraphWorkspace w=large5000();EnumerationResult r=PathEnumerator.enumerate(roots(w),new Options(100,100,10000,1000,()->false));assertTrue(r.truncated());assertTrue(r.traversalStates()<=10000);assertTrue(r.maximumPathLength()<=100); }
  public void testGreedySelectionReportsApproximationForTruncatedCandidates() { GeneratedTestCase a=new GeneratedTestCase("a",List.of("a"),List.of("e1"));GeneratedTestCase b=new GeneratedTestCase("b",List.of("b"),List.of("e2"));var result=MinCoverageSelector.selectGreedy(List.of(new MinCoverageSelector.ScoredTestCase(a,Set.of("e1")),new MinCoverageSelector.ScoredTestCase(b,Set.of("e2"))),true);assertTrue(result.approximate());assertTrue(result.candidatePathsTruncated());assertEquals(2,result.selected().size()); }

  public void testWriteBoundedBenchmarkResults() throws Exception {
    List<Map<String,Object>> rows=new ArrayList<>();
    benchmark(rows,"sequential",chain(20),new Options(100,100,10000,1000,()->false));
    benchmark(rows,"branch-heavy",binary(12),new Options(500,100,50000,1000,()->false));
    benchmark(rows,"nested-branches",binary(8),new Options(500,100,50000,1000,()->false));
    Node loop=node("loop"),body=node("body");edge(loop,body);edge(loop,loop);benchmark(rows,"loop",loop,Options.defaults());
    Node outer=node("outer"),inner=node("inner");edge(outer,inner);edge(outer,outer);edge(inner,node("done"));edge(inner,inner);benchmark(rows,"nested-loops",outer,Options.defaults());
    GraphWorkspace thousand=new GraphWorkspace(List.of(new SourceFile("Code1000.java",Files.readString(Path.of("code1000.txt")))));
    benchmark(rows,"1000-statements",roots(thousand),new Options(100,200,20000,1000,()->false));
    benchmark(rows,"5000-statements",roots(large5000()),new Options(100,200,20000,1000,()->false));
    Files.writeString(Path.of("path_enumeration_results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(rows));
    assertEquals(7,rows.size());
  }

  private void benchmark(List<Map<String,Object>> rows,String name,Node root,Options options){benchmark(rows,name,List.of(root),options);}
  private void benchmark(List<Map<String,Object>> rows,String name,List<Node> roots,Options options){long before=usedHeap();EnumerationResult r=PathEnumerator.enumerate(roots,options);long memory=Math.max(0,usedHeap()-before);Map<String,Object> row=new LinkedHashMap<>();row.put("program",name);row.put("pathsGenerated",r.paths().size());row.put("generationTimeMs",r.generationTimeMs());row.put("approximateHeapIncreaseBytes",memory);row.put("maximumPathLength",r.maximumPathLength());row.put("truncated",r.truncated());row.put("status",r.status());row.put("stopReason",r.stopReason());row.put("traversalStates",r.traversalStates());rows.add(row);}
  private EnumerationResult enumerate(Node root){return PathEnumerator.enumerate(List.of(root),Options.defaults());}
  private Node chain(int length){return chainNamed("n",length);} private Node chainNamed(String prefix,int length){Node root=node(prefix+0),at=root;for(int i=1;i<length;i++){Node next=node(prefix+i);edge(at,next);at=next;}return root;}
  private Node binary(int depth){Node root=node("r"),at=root;List<Node> level=List.of(root);int id=0;for(int d=0;d<depth;d++){List<Node> next=new ArrayList<>();for(Node n:level){Node l=node("n"+(++id)),r=node("n"+(++id));edge(n,l);edge(n,r);next.add(l);next.add(r);}level=next;}return root;}
  private Node node(String id){return new Node(id,id);} private void edge(Node from,Node to){from.addEdge(to,"e"+from.edges.size(),"CONTROL_DEPENDENCE",null);}
  private List<Node> roots(GraphWorkspace w){return w.current().builders().stream().flatMap(b->b.getAllNodes().stream()).filter(n->n.type.contains("CLASS_ENTRY")).toList();}
  private GraphWorkspace large5000() throws Exception{return new GraphWorkspace(List.of(new SourceFile("Synthetic5000.java",Files.readString(Path.of("benchmarks","Synthetic5000.java")))));}
  private long usedHeap(){Runtime r=Runtime.getRuntime();return r.totalMemory()-r.freeMemory();}
}
