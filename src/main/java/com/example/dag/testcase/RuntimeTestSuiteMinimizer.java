package com.example.dag.testcase;

import com.example.dag.testcase.DeterministicTestGenerationService.GenerationStatus;
import com.example.dag.testcase.DeterministicTestGenerationService.TestCaseRecord;
import java.util.*;

/** Deterministic weighted-greedy reduction over runtime-observed evidence only. */
public final class RuntimeTestSuiteMinimizer {
  public static final String APPROXIMATELY_MINIMIZED="APPROXIMATELY_MINIMIZED";
  public record Contribution(String testCaseId,String generationMethod,int obligationsAdded,
                             List<String> obligationIds,double executionMs,boolean flaky,
                             String oracle,Set<String> semanticDistinctions) {}
  public record Exclusion(String testCaseId,String reason) {}
  public record Result(String graphId,String sessionId,int candidateTestCount,int selectedTestCount,
                       int runtimeObligationCount,int coveredBeforeMinimization,int coveredAfterMinimization,
                       int uncoveredCount,List<String> selectedTestIds,List<Contribution> contributions,
                       double totalExecutionCostMs,List<Exclusion> excludedTests,
                       Map<String,String> flakiness,String status) {}

  public Result minimize(String graphId,String sessionId,Set<String> currentRuntimeObligations,
                         Collection<TestCaseRecord> records){
    Set<String> universe=currentRuntimeObligations==null?Set.of():Set.copyOf(currentRuntimeObligations);
    List<TestCaseRecord> eligible=new ArrayList<>();List<Exclusion> excluded=new ArrayList<>();
    for(TestCaseRecord test:records==null?List.<TestCaseRecord>of():records){String reason=ineligible(test,graphId,sessionId);if(reason==null)eligible.add(test);else excluded.add(new Exclusion(test.testCaseId(),reason));}
    eligible.sort(Comparator.comparing(TestCaseRecord::testCaseId));
    Set<String> coverable=new LinkedHashSet<>(),semanticGoal=new LinkedHashSet<>();for(TestCaseRecord test:eligible){for(String id:test.runtimeObligations())if(universe.contains(id))coverable.add(id);semanticGoal.addAll(semantics(test));}
    Set<String> covered=new LinkedHashSet<>(),semantics=new LinkedHashSet<>();List<String> selectedIds=new ArrayList<>();List<Contribution> contributions=new ArrayList<>();List<TestCaseRecord> remaining=new ArrayList<>(eligible);double cost=0;
    while(!remaining.isEmpty()&&(!covered.containsAll(coverable)||!semantics.containsAll(semanticGoal))){TestCaseRecord best=null;Score bestScore=null;for(TestCaseRecord test:remaining){Set<String> newObligations=new LinkedHashSet<>(test.runtimeObligations());newObligations.retainAll(coverable);newObligations.removeAll(covered);Set<String> newSemantic=new LinkedHashSet<>(semantics(test));newSemantic.removeAll(semantics);Score score=new Score(newObligations.size(),newSemantic.size(),test.flaky()||"FAILED".equals(test.replayStatus()),test.executionMs(),generationRank(test.generationMethod()),test.setupComplexity(),test.testCaseId());if((score.newObligations>0||score.newSemantics>0)&&(bestScore==null||score.betterThan(bestScore))){best=test;bestScore=score;}}
      if(best==null)break;Set<String> added=new LinkedHashSet<>(best.runtimeObligations());added.retainAll(coverable);added.removeAll(covered);Set<String> semanticAdded=new LinkedHashSet<>(semantics(best));semanticAdded.removeAll(semantics);covered.addAll(added);semantics.addAll(semanticAdded);remaining.remove(best);selectedIds.add(best.testCaseId());cost+=best.executionMs();contributions.add(new Contribution(best.testCaseId(),best.generationMethod(),added.size(),List.copyOf(added),best.executionMs(),best.flaky(),best.oracle(),Set.copyOf(semanticAdded)));}
    for(TestCaseRecord test:remaining)excluded.add(new Exclusion(test.testCaseId(),"Redundant after runtime-evidence selection"));
    Map<String,String> flakiness=new LinkedHashMap<>();for(TestCaseRecord test:eligible)flakiness.put(test.testCaseId(),test.flaky()?"FLAKY":Objects.toString(test.replayStatus(),"NOT_REPLAYED"));
    int uncovered=Math.max(0,universe.size()-covered.size());return new Result(graphId,sessionId,eligible.size(),selectedIds.size(),universe.size(),coverable.size(),covered.size(),uncovered,List.copyOf(selectedIds),List.copyOf(contributions),Math.round(cost*10.0)/10.0,List.copyOf(excluded),Map.copyOf(flakiness),APPROXIMATELY_MINIMIZED);
  }

  private String ineligible(TestCaseRecord test,String graphId,String sessionId){if(test==null)return"Missing test record";if(!Objects.equals(graphId,test.graphId()))return"STALE graph fingerprint";if(!Objects.equals(sessionId,test.sessionId()))return"Different runtime coverage session";if(test.status()!=GenerationStatus.RETAINED||!test.targetMarked())return"Not a retained runtime-valid candidate";if(!"PASSED".equals(test.executionStatus())&&!(test.oracle()!=null&&test.oracle().startsWith("EXPECTED_EXCEPTION:")))return"Execution was not successful or expected";if(test.runtimeEvidence()==null||test.runtimeEvidence().stream().noneMatch(e->"RUNTIME_OBSERVED".equals(e.source())))return"No RUNTIME_OBSERVED evidence";if(test.runtimeObligations()==null||test.runtimeObligations().isEmpty())return"No runtime-observed obligations";return null;}
  private Set<String> semantics(TestCaseRecord test){LinkedHashSet<String> values=new LinkedHashSet<>();for(String target:nullSafe(test.runtimeTargets()))values.add("EM3_TARGET:"+target);for(String type:nullSafe(test.runtimeExceptionTypes()))values.add("EXCEPTION_TYPE:"+type);if(test.oracle()!=null&&!test.oracle().isBlank()&&!"NONE".equals(test.oracle()))values.add("ORACLE:"+test.oracle());return values;}
  private Set<String> nullSafe(Set<String> values){return values==null?Set.of():values;}
  private int generationRank(String method){return "DETERMINISTIC".equals(method)?0:"EXCEPTION".equals(method)||"POLYMORPHIC".equals(method)||"INHERITED".equals(method)?1:2;}
  private record Score(int newObligations,int newSemantics,boolean flaky,double cost,int generationRank,int complexity,String id){double weight(){return newObligations*1_000_000_000_000d+newSemantics*1_000_000_000d-(flaky?100_000_000d:0)-cost*1_000d-generationRank*100d-complexity;}boolean betterThan(Score other){int weighted=Double.compare(weight(),other.weight());return weighted!=0?weighted>0:id.compareTo(other.id)<0;}}
}
