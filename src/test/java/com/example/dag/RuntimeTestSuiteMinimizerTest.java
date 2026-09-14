package com.example.dag;

import com.example.dag.coverage.CoverageObligationEngine.Evidence;
import com.example.dag.testcase.DeterministicTestGenerationService.*;
import com.example.dag.testcase.RuntimeTestSuiteMinimizer;
import junit.framework.TestCase;
import java.util.*;

public class RuntimeTestSuiteMinimizerTest extends TestCase {
  private final RuntimeTestSuiteMinimizer minimizer=new RuntimeTestSuiteMinimizer();
  public void testGreedyAddsNewRuntimeCoverage(){var r=min(Set.of("o1","o2"),test("a",Set.of("o1"),5),test("b",Set.of("o2"),5));assertEquals(2,r.selectedTestCount());assertEquals(2,r.coveredAfterMinimization());}
  public void testRedundantTestRemoved(){var r=min(Set.of("o1"),test("a",Set.of("o1"),2),test("b",Set.of("o1"),3));assertEquals(List.of("a"),r.selectedTestIds());}
  public void testLowerCostEquivalentPreferred(){var r=min(Set.of("o1"),test("slow",Set.of("o1"),20),test("fast",Set.of("o1"),2));assertEquals(List.of("fast"),r.selectedTestIds());}
  public void testFlakyTestPenalized(){var stable=test("stable",Set.of("o1"),10);var flaky=test("flaky",Set.of("o1"),1,"DETERMINISTIC",true,Set.of(),Set.of(),"NONE","RUNTIME_OBSERVED","s","g");assertEquals(List.of("stable"),min(Set.of("o1"),flaky,stable).selectedTestIds());}
  public void testDistinctEm3TargetsPreserved(){var a=test("a",Set.of("o1"),2,"POLYMORPHIC",false,Set.of("A.f()"),Set.of(),"NONE","RUNTIME_OBSERVED","s","g");var b=test("b",Set.of("o1"),2,"POLYMORPHIC",false,Set.of("B.f()"),Set.of(),"NONE","RUNTIME_OBSERVED","s","g");assertEquals(2,min(Set.of("o1"),a,b).selectedTestCount());}
  public void testDistinctExceptionFlowsPreserved(){assertEquals(2,min(Set.of("ee1a","ee1b"),test("a",Set.of("ee1a"),1),test("b",Set.of("ee1b"),1)).selectedTestCount());}
  public void testDistinctExceptionTypesPreserved(){var a=test("a",Set.of("o1"),1,"EXCEPTION",false,Set.of(),Set.of("IOException"),"NONE","RUNTIME_OBSERVED","s","g");var b=test("b",Set.of("o1"),1,"EXCEPTION",false,Set.of(),Set.of("RuntimeException"),"NONE","RUNTIME_OBSERVED","s","g");assertEquals(2,min(Set.of("o1"),a,b).selectedTestCount());}
  public void testDistinctAssertionOraclePreserved(){var a=test("a",Set.of("o1"),1,"DETERMINISTIC",false,Set.of(),Set.of(),"ASSERT:x=1","RUNTIME_OBSERVED","s","g");var b=test("b",Set.of("o1"),1,"DETERMINISTIC",false,Set.of(),Set.of(),"ASSERT:x=2","RUNTIME_OBSERVED","s","g");assertEquals(2,min(Set.of("o1"),a,b).selectedTestCount());}
  public void testStaleGraphExcluded(){var stale=test("old",Set.of("o1"),1,"DETERMINISTIC",false,Set.of(),Set.of(),"NONE","RUNTIME_OBSERVED","s","old");var r=min(Set.of("o1"),stale);assertEquals(0,r.selectedTestCount());assertTrue(r.excludedTests().get(0).reason().contains("STALE"));}
  public void testSessionsAreIsolated(){var other=test("other",Set.of("o1"),1,"DETERMINISTIC",false,Set.of(),Set.of(),"NONE","RUNTIME_OBSERVED","other","g");assertEquals(0,min(Set.of("o1"),other).candidateTestCount());}
  public void testZeroRuntimeCoverageProducesEmptySuite(){var r=min(Set.of("o1"));assertEquals(0,r.selectedTestCount());assertEquals(1,r.uncoveredCount());}
  public void testUncoveredObligationsRemainExplicit(){var r=min(Set.of("o1","o2"),test("a",Set.of("o1"),1));assertEquals(1,r.uncoveredCount());assertEquals(1,r.coveredAfterMinimization());}
  public void testRepeatedMinimizationIsDeterministic(){var tests=List.of(test("b",Set.of("o1"),1),test("a",Set.of("o1"),1));assertEquals(minimizer.minimize("g","s",Set.of("o1"),tests),minimizer.minimize("g","s",Set.of("o1"),tests));}
  public void testStaticOnlyEvidenceIsNotCounted(){var staticOnly=test("static",Set.of("o1"),1,"DETERMINISTIC",false,Set.of(),Set.of(),"NONE","STATIC_PREDICTED","s","g");assertEquals(0,min(Set.of("o1"),staticOnly).selectedTestCount());}

  private RuntimeTestSuiteMinimizer.Result min(Set<String> obligations,TestCaseRecord...tests){return minimizer.minimize("g","s",obligations,List.of(tests));}
  private TestCaseRecord test(String id,Set<String> obligations,double cost){return test(id,obligations,cost,"DETERMINISTIC",false,Set.of(),Set.of(),"NONE","RUNTIME_OBSERVED","s","g");}
  private TestCaseRecord test(String id,Set<String> obligations,double cost,String method,boolean flaky,Set<String> targets,Set<String> types,String oracle,String evidenceSource,String session,String graph){return new TestCaseRecord(id,"candidate-"+id,"target-"+id,graph,List.of(),"Harness.java",method,List.of(),"PASSED","PASSED",1,cost,"","",true,List.of(new Evidence(evidenceSource,session,id,"target-"+id,targets.stream().findFirst().orElse(null),types.stream().findFirst().orElse(null))),List.copyOf(obligations),1,GenerationStatus.RETAINED,null,0,null,null,targets.stream().findFirst().orElse(null),session,Set.copyOf(obligations),Set.of("n"),Set.of("e"),Set.copyOf(types),Set.copyOf(targets),oracle,flaky,flaky?"FAILED":"PASSED",1,null);}
}
