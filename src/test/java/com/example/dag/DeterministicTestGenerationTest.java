package com.example.dag;

import com.example.dag.coverage.*;
import com.example.dag.coverage.CoverageObligationEngine.*;
import com.example.dag.server.GraphWorkspace;
import com.example.dag.testcase.DeterministicTestGenerationService;
import com.example.dag.testcase.DeterministicTestGenerationService.*;
import junit.framework.TestCase;
import java.util.*;

public class DeterministicTestGenerationTest extends TestCase {
  private final CoverageObligationEngine engine=new CoverageObligationEngine();
  private final ControlRequirementExtractor extractor=new ControlRequirementExtractor();

  public void testPositiveBranch(){assertRetained(generate("class A{static void m(int x){if(x>0){x++;}}}","true"));}
  public void testNegativeBranch(){assertRetained(generate("class A{static void m(int x){if(x>0){x++;}else{x--;}}}","false"));}
  public void testNestedTrueFalse(){assertRetained(generate("class A{static void m(int x,int y){if(x>0){if(y>0){x++;}else{x--;}}}}","false"));}
  public void testNumericBoundary(){GenerationResult r=generate("class A{static void m(int x){if(x>=10){x++;}}}","true");assertRetained(r);assertTrue(r.retained().inputs().stream().anyMatch(i->i.javaLiteral().matches("9|10|11")));}
  public void testBooleanCondition(){assertRetained(generate("class A{static void m(boolean enabled){if(enabled){int x=1;}}}","true"));}
  public void testStringEquality(){assertRetained(generate("class A{static void m(String s){if(\"yes\".equals(s)){int x=1;}}}","true"));}
  public void testStringLength(){assertRetained(generate("class A{static void m(String s){if(s!=null&&s.length()>0){int x=1;}}}","true"));}
  public void testNullCheck(){assertRetained(generate("class A{static void m(String s){if(s==null){int x=1;}}}","true"));}
  public void testOneDimensionalArray(){assertRetained(generate("class A{static void m(int[] a){if(a!=null&&a.length>1&&a[1]>0){int x=1;}}}","true"));}
  public void testTwoDimensionalArray(){assertRetained(generate("class A{static void m(int[][] a){if(a!=null&&a.length>1&&a[1]!=null&&a[1].length>1&&a[1][1]>0){int x=1;}}}","true"));}
  public void testInstanceMethodUsesHarness(){GenerationResult r=generate("class A{void m(int x){if(x>0){x++;}}}","true");assertRetained(r);assertTrue(r.retained().harnessSourceReference().startsWith("__CosdgHarness_"));}
  public void testMethodCall(){GraphWorkspace w=graph("class A{static void f(){}static void m(boolean b){if(b){f();}}}");assertRetained(generate(w,obligation(w,Criterion.METHOD_CALL_EM1,null),12));}
  public void testInheritedMethod(){GraphWorkspace w=graph("class P{void f(){}}class C extends P{void m(){f();}}");assertRetained(generate(w,obligation(w,Criterion.METHOD_CALL_EM2,null),8));}
  public void testPolymorphicTarget(){GraphWorkspace w=graph("class P{void f(){}}class C extends P{void f(){}}class T{static void m(){P p=new C();p.f();}}");assertRetained(generate(w,obligation(w,Criterion.POLYMORPHIC_CALL_EM3,null),8));}
  public void testPolymorphicSelectorUsesSafeArguments(){GraphWorkspace w=graph("interface P{int f(int x);}class A implements P{public int f(int x){return x;}}class B implements P{public int f(int x){return x+1;}}class T{static P select(int mode){if(mode==1){return new A();}else if(mode==2){return new B();}return new A();}static int run(int mode,String text,int divisor){int x=Integer.parseInt(text);P p=select(mode);return p.f(x)/divisor;}}");for(Obligation o:obligations(w,Criterion.POLYMORPHIC_CALL_EM3)){GenerationResult r=generate(w,o,24);assertRetained(r);assertTrue(r.retained().inputs().stream().anyMatch(i->i.name().equals("text")&&i.javaLiteral().equals("\"1\"")));assertTrue(r.retained().inputs().stream().anyMatch(i->i.name().equals("divisor")&&i.javaLiteral().equals("1")));}}
  public void testExplicitThrowCatch(){GraphWorkspace w=graph("class A{static void m(){try{throw new IllegalArgumentException();}catch(IllegalArgumentException e){}}}");assertRetained(generate(w,obligation(w,Criterion.EXCEPTION_THROW_FLOW,null),8));}
  public void testInternallyCaughtExplicitNullException(){GraphWorkspace w=graph("class A{static int lookup(int[] values,int index){try{if(values==null){throw new NullPointerException();}return values[index];}catch(NullPointerException e){return -1;}catch(ArrayIndexOutOfBoundsException e){return -2;}}}");for(Obligation o:obligations(w,Criterion.EXCEPTION_THROW_FLOW).stream().filter(x->"NullPointerException".equals(x.details().exceptionType())).toList())assertRetained(generate(w,o,12));for(Obligation o:obligations(w,Criterion.EXCEPTION_CATCH).stream().filter(x->"NullPointerException".equals(x.details().exceptionType())).toList())assertRetained(generate(w,o,12));}
  public void testExpectedException(){GraphWorkspace w=graph("class A{static void m(){throw new IllegalArgumentException();}}");Obligation o=obligation(w,Criterion.EXCEPTION_TYPE,null);assertRetained(generate(w,o,8));}
  public void testMissThenRepairRetains(){GenerationResult r=generate("class A{static void m(int x){if(0<x){x++;}}}","true");assertRetained(r);assertTrue(r.attempts()>1);}
  public void testReachedCandidateIsAddedToSession(){GraphWorkspace w=graph("class A{static void m(boolean b){if(b){int x=1;}}}");Obligation o=obligation(w,Criterion.STATEMENT_CONTROL,"true");RuntimeCoverageSession s=session(w);GenerationResult r=new DeterministicTestGenerationService().generate(w.current(),o,extractor.extract(w.current(),o),s,new Options(8,15000,5000));assertRetained(r);assertEquals(1,s.summarize(nodes(w),edges(w)).testCount());}
  public void testCompilationFailureIsRejected(){GenerationResult r=generate("class A{static void m(int x){MissingType.bad();if(x>0){x++;}}}","true");assertFalse(r.records().isEmpty());assertEquals("FAILED",r.records().get(0).compileStatus());}
  public void testTimeoutIsRejected(){GraphWorkspace w=graph("class A{static void m(){while(true){}}}");Obligation o=obligation(w,Criterion.METHOD,null);GenerationResult r=generate(w,o,1,100);assertEquals(GenerationStatus.BLOCKED,r.status());assertEquals("TIMEOUT",r.records().get(0).executionStatus());}
  public void testUnsupportedSignature(){GraphWorkspace w=graph("class A{static void m(java.util.List<String> x){if(x!=null){}}}");Obligation o=obligation(w,Criterion.METHOD,null);assertEquals(GenerationStatus.UNSUPPORTED,generate(w,o,2).status());}
  public void testAttemptLimit(){GenerationResult r=generate("class A{static void m(int x){if(1000<x){x++;}}}","true",1);assertEquals(GenerationStatus.BLOCKED,r.status());assertEquals(1,r.attempts());}
  public void testStaleGraphRejected(){GraphWorkspace a=graph("class A{static void m(int x){if(x>0){x++;}}}"),b=graph("class A{static void m(int y){if(y>0){y++;}}}");Obligation old=obligation(a,Criterion.STATEMENT_CONTROL,"true");GenerationResult r=new DeterministicTestGenerationService().generate(b.current(),old,extractor.extract(b.current(),old),session(b),new Options(2,1000,1000));assertEquals(GenerationStatus.STALE,r.status());}

  private GenerationResult generate(String code,String branch){return generate(code,branch,24);}private GenerationResult generate(String code,String branch,int attempts){GraphWorkspace w=graph(code);return generate(w,obligation(w,Criterion.STATEMENT_CONTROL,branch),attempts);}
  private GenerationResult generate(GraphWorkspace w,Obligation o,int attempts){return generate(w,o,attempts,5000);}private GenerationResult generate(GraphWorkspace w,Obligation o,int attempts,long timeout){return new DeterministicTestGenerationService().generate(w.current(),o,extractor.extract(w.current(),o),session(w),new Options(attempts,20000,timeout));}
  private GraphWorkspace graph(String code){return new GraphWorkspace(List.of(new GraphWorkspace.SourceFile("Test.java",code)));}
  private Obligation obligation(GraphWorkspace w,Criterion c,String branch){return engine.build(w.current(),CoverageAnalyzer.CoverageMarking.staticPrediction(Set.of(),Set.of()),null).obligations().stream().filter(o->o.criterion()==c&&(branch==null||branch.equals(o.details().requiredBranch()))).reduce((a,b)->b).orElseThrow();}
  private List<Obligation> obligations(GraphWorkspace w,Criterion c){return engine.build(w.current(),CoverageAnalyzer.CoverageMarking.staticPrediction(Set.of(),Set.of()),null).obligations().stream().filter(o->o.criterion()==c).toList();}
  private RuntimeCoverageSession session(GraphWorkspace w){return new RuntimeCoverageSession(nodes(w).size(),edges(w).size());}
  private List<com.example.dag.graph.Node> nodes(GraphWorkspace w){return w.current().builders().stream().flatMap(b->b.getAllNodes().stream()).toList();}private List<com.example.dag.graph.Edge> edges(GraphWorkspace w){return w.current().builders().stream().flatMap(b->b.getAllEdges().stream()).toList();}
  private void assertRetained(GenerationResult r){assertEquals(r.reason(),GenerationStatus.RETAINED,r.status());assertNotNull(r.retained());assertTrue(r.retained().targetMarked());assertEquals("PASSED",r.retained().compileStatus());}
}
