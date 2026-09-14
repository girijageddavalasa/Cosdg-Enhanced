package com.example.dag;

import com.example.dag.coverage.RuntimeCoverageSession;
import com.example.dag.coverage.CoverageAnalyzer;
import com.example.dag.coverage.CoverageObligationEngine;
import com.example.dag.coverage.ControlRequirementExtractor;
import com.example.dag.runtime.RuntimeExecutionService;
import com.example.dag.server.GraphWorkspace;
import com.example.dag.testcase.DeterministicTestGenerationService;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class ComprehensiveRuntimeRegressionTest extends TestCase {
  public void testInterfaceGraphAndNestedMainCallAreComplete() throws Exception {
    String source=Files.readString(Path.of("graph_examples","07_comprehensive_runtime.txt"));
    GraphWorkspace workspace=new GraphWorkspace(List.of(new GraphWorkspace.SourceFile("07_comprehensive_runtime.txt",source)));
    var nodes=workspace.current().builders().stream().flatMap(builder->builder.getAllNodes().stream()).toList();
    var edges=workspace.current().builders().stream().flatMap(builder->builder.getAllEdges().stream()).toList();
    assertEquals(4,nodes.stream().filter(node->node.type.contains("CLASS_ENTRY")).count());
    assertEquals(5,nodes.stream().filter(node->node.type.contains("METHOD_ENTRY")).count());
    assertEquals(2,edges.stream().filter(edge->"INHERITANCE".equals(edge.type)).count());
    assertEquals(1,edges.stream().filter(edge->"ABSTRACT_CLASS_MEMBER".equals(edge.type)).count());
    assertEquals(4,edges.stream().filter(edge->"CLASS_MEMBER".equals(edge.type)).count());
    assertEquals(2,edges.stream().filter(edge->"POLYMORPHIC_METHOD_CALL".equals(edge.type)).count());
    assertEquals(1,edges.stream().filter(edge->"SIMPLE_METHOD_CALL".equals(edge.type)).count());

    RuntimeExecutionService.Result run=new RuntimeExecutionService().execute(workspace.current(),
        new RuntimeExecutionService.Request("ComprehensiveRuntimeExample",List.of(),5_000,"comprehensive-main"));
    assertEquals(run.stderr(),"PASSED",run.status());
    assertTrue("method-entry to for-loop control edge must be runtime marked",run.visitedEdges().contains("ec.22"));
    assertTrue("for-loop repeat edge must be runtime marked",run.visitedEdges().contains("ec.26"));
    assertTrue("main call statement control edge must be runtime marked",run.visitedEdges().contains("ec.37"));
    RuntimeCoverageSession session=new RuntimeCoverageSession(nodes.size(),edges.size());session.add("comprehensive-main",run);
    assertEquals(4L,session.summarize(nodes,edges).coverage().metric("method").denominator());
    var calls=session.summarize(nodes,edges).coverage().metric("methodCall");
    assertEquals(2L,calls.numerator());assertEquals(3L,calls.denominator());
    var polymorphic=session.summarize(nodes,edges).coverage().metric("polymorphic");
    assertEquals(1L,polymorphic.numerator());assertEquals(2L,polymorphic.denominator());
    var catalog=new CoverageObligationEngine().build(workspace.current(),
        session.marking(),session.summarize(nodes,edges));
    assertFalse(catalog.obligations().stream().anyMatch(o->o.criterion()==CoverageObligationEngine.Criterion.METHOD
        && "ScoreProcessor".equals(o.source().classId())));
  }

  public void testBothInterfaceTargetsCanBeGeneratedAndRuntimeValidated() throws Exception {
    String source=Files.readString(Path.of("graph_examples","07_comprehensive_runtime.txt"));
    GraphWorkspace workspace=new GraphWorkspace(List.of(new GraphWorkspace.SourceFile("07_comprehensive_runtime.txt",source)));
    var nodes=workspace.current().builders().stream().flatMap(builder->builder.getAllNodes().stream()).toList();
    var edges=workspace.current().builders().stream().flatMap(builder->builder.getAllEdges().stream()).toList();
    var obligations=new CoverageObligationEngine().build(workspace.current(),
        CoverageAnalyzer.CoverageMarking.staticPrediction(Set.of(),Set.of()),null).obligations().stream()
        .filter(o->o.criterion()==CoverageObligationEngine.Criterion.POLYMORPHIC_CALL_EM3).toList();
    assertEquals(2,obligations.size());RuntimeCoverageSession session=new RuntimeCoverageSession(nodes.size(),edges.size());
    DeterministicTestGenerationService generator=new DeterministicTestGenerationService();
    for(var obligation:obligations){var result=generator.generate(workspace.current(),obligation,
        new ControlRequirementExtractor().extract(workspace.current(),obligation),session,
        new DeterministicTestGenerationService.Options(24,15_000,5_000));
      assertEquals(result.reason(),DeterministicTestGenerationService.GenerationStatus.RETAINED,result.status());}
    assertEquals(2L,session.summarize(nodes,edges).coverage().metric("polymorphic").numerator());
  }
}
