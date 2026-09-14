package com.example.dag;

import com.example.dag.coverage.RuntimeCoverageSession;
import com.example.dag.runtime.RuntimeExecutionService;
import com.example.dag.server.GraphWorkspace;
import com.example.dag.testcase.EvoSuiteCandidateProvider;
import junit.framework.TestCase;

import java.nio.file.Path;
import java.util.List;

public class EvoSuiteCandidateProviderTest extends TestCase {
  public void testMissingArtifactsAreReportedWithoutBreakingGeneration() {
    var provider = new EvoSuiteCandidateProvider(Path.of("target", "missing-evosuite.jar"),
        Path.of("target", "missing-evosuite-runtime.jar"), new RuntimeExecutionService());
    var workspace = graph();
    var result = provider.generate(workspace.current(), session(workspace), EvoSuiteCandidateProvider.Options.defaults());
    assertFalse(result.available());
    assertEquals("UNAVAILABLE", result.status());
    assertTrue(result.records().isEmpty());
  }

  /** Runs only in developer checkouts containing the ignored official release artifacts. */
  public void testOfficialEvoSuiteCandidateIsRuntimeValidatedWhenInstalled() {
    var provider = new EvoSuiteCandidateProvider();
    if (!provider.available()) return;
    var workspace = graph();
    var result = provider.generate(workspace.current(), session(workspace),
        new EvoSuiteCandidateProvider.Options(2, 1, 20, 5_000));
    assertEquals(result.message(), "COMPLETED", result.status());
    assertTrue("Expected EvoSuite to emit at least one @Test", result.generatedTests() > 0);
    assertTrue("Expected at least one test with real graph evidence", result.retainedTests() > 0);
    assertTrue(result.records().stream().anyMatch(test -> "EVOSUITE".equals(test.generationMethod())
        && test.targetMarked() && !test.runtimeEdges().isEmpty()));
  }

  public void testExceptionProgramFailureIsContainedWhenInstalled() {
    var provider = new EvoSuiteCandidateProvider();
    if (!provider.available()) return;
    var workspace = new GraphWorkspace(List.of(new GraphWorkspace.SourceFile("ExceptionPropagationExample.txt",
        "class ExceptionPropagationExample { static void fail(int value){if(value==0)throw new RuntimeException(\"zero\");}"+
            " static void caller(int value){try{fail(value);}catch(RuntimeException exception){System.out.println(\"caught\");}}"+
            " public static void main(String[] args){caller(0);} }")));
    var result = provider.generate(workspace.current(), session(workspace),
        new EvoSuiteCandidateProvider.Options(2, 1, 20, 5_000));
    assertTrue(result.available());
    assertNotNull(result.status());
    assertNotNull(result.records());
  }

  private GraphWorkspace graph() {
    return new GraphWorkspace(List.of(new GraphWorkspace.SourceFile("SimpleEvo.java",
        "public class SimpleEvo { public static int classify(int x){ if(x>0)return 1; return -1; } }")));
  }

  private RuntimeCoverageSession session(GraphWorkspace workspace) {
    int nodes=(int)workspace.current().builders().stream().flatMap(builder->builder.getAllNodes().stream()).count();
    int edges=(int)workspace.current().builders().stream().flatMap(builder->builder.getAllEdges().stream()).count();
    return new RuntimeCoverageSession(nodes,edges);
  }
}
