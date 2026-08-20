package com.example.dag;

import com.example.dag.server.GraphWorkspace;
import com.example.dag.server.GraphWorkspace.SourceFile;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ParallelGraphProcessingTest extends TestCase {
  public void testCanonicalGraphIsEquivalentForAllWorkerCounts() throws Exception {
    Path source = Path.of("benchmarks", "Synthetic5000.java");
    List<SourceFile> files = List.of(new SourceFile(source.toString(), Files.readString(source)));
    String baseline = null;
    for (int workers : List.of(1, 2, 4, 8)) {
      GraphWorkspace workspace = new GraphWorkspace();
      String canonical = workspace.submit(files, workers, false).canonicalJson();
      if (baseline == null) baseline = canonical;
      else assertEquals(baseline, canonical);
    }
  }

  public void testIndependentFilesMergeDeterministically() {
    List<SourceFile> files = List.of(
        new SourceFile("A.java", "class A { int a(int x){ return x; } }"),
        new SourceFile("B.java", "class B { void b(){ int y=2; } }"),
        new SourceFile("C.java", "class C { void c(){ try { throw new RuntimeException(); } catch(RuntimeException e){} } }"));
    GraphWorkspace serial = new GraphWorkspace();
    GraphWorkspace parallel = new GraphWorkspace();
    assertEquals(serial.submit(files, 1, false).canonicalJson(),
        parallel.submit(files, 8, false).canonicalJson());
  }

  public void testMeasuredBaselineProducesSpeedupMetadata() {
    List<SourceFile> files = List.of(
        new SourceFile("A.java", "class A { void a(){int x=1;} }"),
        new SourceFile("B.java", "class B { void b(){int y=2;} }"));
    var performance = new GraphWorkspace().submit(files, 2, true).statistics().measured();
    assertEquals(2, performance.workers());
    assertNotNull(performance.serialBaselineGraphMs());
    assertNotNull(performance.speedup());
  }
}
