package com.example.dag;

import com.example.dag.coverage.CoverageAnalyzer.CoverageMarking;
import com.example.dag.coverage.CoverageObligationEngine;
import com.example.dag.coverage.CoverageObligationEngine.Criterion;
import com.example.dag.coverage.CoverageObligationEngine.Status;
import com.example.dag.coverage.RuntimeCoverageSession;
import com.example.dag.export.CanonicalGraphExporter.*;
import com.example.dag.runtime.RuntimeExecutionService;
import com.example.dag.server.GraphWorkspace;
import junit.framework.TestCase;
import java.util.*;

public class CoverageObligationEngineTest extends TestCase {
  private final CoverageObligationEngine engine=new CoverageObligationEngine();
  public void testEcObligations(){assertTrue(criteria(graph("class A{void m(){int x=1;if(x>0){x++;}}}"),Criterion.STATEMENT_CONTROL)>0);}
  public void testEbObligations(){assertEquals(1,criteria(graph("class A{void m(){}}"),Criterion.METHOD));}
  public void testEm1Obligations(){assertEquals(1,criteria(graph("class A{void f(){}void m(){f();}}"),Criterion.METHOD_CALL_EM1));}
  public void testEm2Obligations(){assertEquals(1,criteria(graph("class A{void f(){}}class B extends A{void m(){f();}}"),Criterion.METHOD_CALL_EM2));}
  public void testEm3Obligations(){assertEquals(2,criteria(graph("class A{void f(){}}class B extends A{void f(){}}class T{void m(){A a=new B();a.f();}}"),Criterion.POLYMORPHIC_CALL_EM3));}
  public void testInheritanceObligations(){assertEquals(1,criteria(graph("class A{void f(){}}class B extends A{}"),Criterion.INHERITANCE));}
  public void testEe1Obligations(){assertEquals(1,criteria(graph("class A{void m(){try{throw new RuntimeException();}catch(RuntimeException e){}}}"),Criterion.EXCEPTION_THROW_FLOW));}
  public void testEe2Obligations(){assertEquals(1,criteria(graph("class A{void m(){try{}catch(RuntimeException e){}}}"),Criterion.EXCEPTION_CATCH));}
  public void testExceptionTypeObligations(){assertEquals(1,criteria(graph("class A{void m(){try{throw new RuntimeException();}catch(RuntimeException e){}}}"),Criterion.EXCEPTION_TYPE));}
  public void testZeroDenominatorProducesNoObligations(){var c=catalog(graph("class Empty{}"),CoverageMarking.staticPrediction(Set.of(),Set.of()));assertEquals(0,c.summary().total());}
  public void testGraphFingerprintChangeMakesOldObligationsStale(){GraphWorkspace a=graph("class A{void a(){int x=1;}}"),b=graph("class B{void b(){int y=2;int z=3;}} ");var first=catalog(a,CoverageMarking.staticPrediction(Set.of(),Set.of()));var changed=engine.refresh(first,b.current(),CoverageMarking.staticPrediction(Set.of(),Set.of()),null);assertTrue(changed.summary().stale()>0);assertFalse(first.graphId().equals(changed.graphId()));}
  public void testDuplicateLegacyIdsRemainCanonicalUnique(){CanonicalGraph g=duplicateGraph();var c=engine.build(g,"duplicate-canonical",CoverageMarking.staticPrediction(Set.of(),Set.of()));List<String> ids=c.obligations().stream().map(o->o.elementId()).toList();assertEquals(2,new HashSet<>(ids).size());assertEquals(2,new HashSet<>(c.obligations().stream().map(o->o.id()).toList()).size());}
  public void testRuntimeMarkingChangesStatusAndAddsEvidence() {GraphWorkspace w=graph("class Test{public static void main(String[]a){int x=1;}}");RuntimeExecutionService service=new RuntimeExecutionService();var result=service.execute(w.current(),new RuntimeExecutionService.Request("Test",List.of(),5000,"actual-test"));assertEquals(result.stderr(),"PASSED",result.status());RuntimeCoverageSession session=new RuntimeCoverageSession(nodes(w),edges(w));session.add("actual-test",result);var summary=session.summarize(allNodes(w),allEdges(w));var c=engine.build(w.current(),session.marking(),summary);assertTrue(c.summary().covered()>0);assertTrue(c.obligations().stream().filter(o->o.status()==Status.COVERED).anyMatch(o->o.evidence().stream().anyMatch(e->"actual-test".equals(e.testCaseId()))));}
  public void testStaticAndRuntimeSourcesRemainSeparate(){GraphWorkspace w=graph("class A{void m(){int x=1;}}");String edge=w.current().builders().get(0).getAllEdges().stream().filter(e->"CONTROL_DEPENDENCE".equals(e.type)&&e.to.type.contains("VAR_DECL")).findFirst().orElseThrow().label;var stat=catalog(w,CoverageMarking.staticPrediction(Set.of(),Set.of(edge)));var run=catalog(w,new CoverageMarking(Set.of(),Set.of(edge),null,Map.of(),true,Set.of("statement")));assertEquals("STATIC_PREDICTED",stat.coverageSource());assertEquals("RUNTIME_OBSERVED",run.coverageSource());assertTrue(stat.obligations().stream().allMatch(o->!"RUNTIME_OBSERVED".equals(o.coverageSource())));}

  private GraphWorkspace graph(String source){return new GraphWorkspace(List.of(new GraphWorkspace.SourceFile("Test.java",source)));}
  private CoverageObligationEngine.Catalog catalog(GraphWorkspace w,CoverageMarking m){return engine.build(w.current(),m,null);}
  private int criteria(GraphWorkspace w,Criterion c){return(int)catalog(w,CoverageMarking.staticPrediction(Set.of(),Set.of())).obligations().stream().filter(o->o.criterion()==c).count();}
  private int nodes(GraphWorkspace w){return allNodes(w).size();}private int edges(GraphWorkspace w){return allEdges(w).size();}
  private List<com.example.dag.graph.Node> allNodes(GraphWorkspace w){return w.current().builders().stream().flatMap(b->b.getAllNodes().stream()).toList();}
  private List<com.example.dag.graph.Edge> allEdges(GraphWorkspace w){return w.current().builders().stream().flatMap(b->b.getAllEdges().stream()).toList();}
  private CanonicalGraph duplicateGraph(){NodeRecord m1=new NodeRecord("p::A.java::ve2_01","ve2_01","METHOD_ENTRY","A","m","A.java",1,List.of());NodeRecord s1=new NodeRecord("p::A.java::vs1_02","vs1_02","STMT","A","m","A.java",2,List.of());NodeRecord m2=new NodeRecord("p::B.java::ve2_01","ve2_01","METHOD_ENTRY","B","m","B.java",1,List.of());NodeRecord s2=new NodeRecord("p::B.java::vs1_02","vs1_02","STMT","B","m","B.java",2,List.of());EdgeRecord e1=new EdgeRecord("p::A.java::ec_01","ec_01","CONTROL_DEPENDENCE",m1.id(),s1.id(),null,null,List.of());EdgeRecord e2=new EdgeRecord("p::B.java::ec_01","ec_01","CONTROL_DEPENDENCE",m2.id(),s2.id(),null,null,List.of());return new CanonicalGraph(new Metadata("p",2,4,2),List.of(m1,s1,m2,s2),List.of(e1,e2));}
}
