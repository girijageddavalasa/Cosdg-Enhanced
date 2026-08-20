package com.example.dag.server;

import com.example.dag.DAGBuilder;
import com.example.dag.JavaLexer;
import com.example.dag.JavaParser;
import com.example.dag.JavaParserBaseVisitor;
import com.example.dag.export.CanonicalGraphExporter;
import com.example.dag.export.CanonicalGraphExporter.CanonicalGraph;
import com.example.dag.export.CanonicalGraphExporter.GraphInput;
import com.example.dag.graph.Node;
import com.example.dag.visitor.ExceptionVisitor;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Builds and owns one complete, immutable-at-publication graph snapshot. */
public final class GraphWorkspace {
  public record SourceFile(String name, String source) {}
  public record Performance(int workers, int effectiveWorkers, double parseMs, double graphMs, double totalMs,
                            long graphHeapBytes, double jsonExportMs, int jsonBytes,
                            Double serialBaselineGraphMs, Double speedup) {}
  public record Statistics(int sourceFiles, int statements, int classes, int methods,
                           int nodes, int edges, int syntaxErrors, Performance measured,
                           String theoreticalTime, String theoreticalSpace) {}
  public record Snapshot(String program, List<SourceFile> sources, List<DAGBuilder> builders,
                         List<Node> startNodes, CanonicalGraph graph, String canonicalJson,
                         Statistics statistics) {}

  private record Parsed(SourceFile source, JavaParser parser, ParseTree tree, StructureCounter count) {}
  private record Built(DAGBuilder builder, Node start) {}
  private record BuildResult(Snapshot snapshot, double graphMs) {}

  private volatile Snapshot current;

  public GraphWorkspace() {}

  public GraphWorkspace(List<SourceFile> initialSources) {
    submit(initialSources, 1, false);
  }

  public synchronized Snapshot submit(List<SourceFile> submitted) {
    return submit(submitted, 1, false);
  }

  public synchronized Snapshot submit(List<SourceFile> submitted, int requestedWorkers, boolean measureSerialBaseline) {
    return submit(submitted, requestedWorkers, measureSerialBaseline, false);
  }

  public synchronized Snapshot submit(List<SourceFile> submitted, int requestedWorkers,
                                      boolean measureSerialBaseline, boolean stabilizeMemory) {
    List<SourceFile> sources = normalizeSources(submitted);
    int workers = Math.max(1, Math.min(requestedWorkers, 32));
    Double baseline = null;
    if (measureSerialBaseline && workers > 1 && sources.size() > 1) {
      baseline = build(sources, 1, null, false).graphMs();
    }
    BuildResult result = build(sources, workers, baseline, stabilizeMemory);
    current = result.snapshot();
    return current;
  }

  public Snapshot current() { return current; }

  private BuildResult build(List<SourceFile> sources, int workers, Double serialBaseline, boolean stabilizeMemory) {
    String program = sources.size() == 1 ? sources.get(0).name() : "submitted-program";
    int poolSize = Math.max(1, Math.min(workers, sources.size()));
    ExecutorService pool = Executors.newFixedThreadPool(poolSize);
    try {
      long parseStart = System.nanoTime();
      List<Callable<Parsed>> parseTasks = sources.stream().<Callable<Parsed>>map(source -> () -> parse(source)).toList();
      List<Parsed> parsed = ordered(pool.invokeAll(parseTasks));
      double parseMs = elapsedMs(parseStart);
      long heapAfterParse = stabilizeMemory ? stabilizedHeap() : usedHeap();

      long graphStart = System.nanoTime();
      Built programGraph = buildProgram(program, parsed);
      List<Built> built = List.of(programGraph);
      double graphMs = elapsedMs(graphStart);
      long graphHeap = Math.max(0, (stabilizeMemory ? stabilizedHeap() : usedHeap()) - heapAfterParse);

      List<DAGBuilder> builders = built.stream().map(Built::builder).toList();
      List<Node> starts = built.stream().map(Built::start).toList();
      int statements = parsed.stream().mapToInt(unit -> unit.count().statements).sum();
      int classes = parsed.stream().mapToInt(unit -> unit.count().classes).sum();
      int methods = parsed.stream().mapToInt(unit -> unit.count().methods).sum();
      int syntaxErrors = parsed.stream().mapToInt(unit -> unit.parser().getNumberOfSyntaxErrors()).sum();
      List<GraphInput> inputs = List.of(new GraphInput(programGraph.builder(), "whole-program"));
      CanonicalGraph graph = CanonicalGraphExporter.export(inputs, program, statements);
      long jsonStart = System.nanoTime();
      String canonicalJson = CanonicalGraphExporter.toJson(graph);
      double jsonMs = elapsedMs(jsonStart);
      int jsonBytes = canonicalJson.getBytes(StandardCharsets.UTF_8).length;
      Double speedup = serialBaseline == null || graphMs <= 0 || poolSize <= 1 ? null
          : Math.round((serialBaseline / graphMs) * 100.0) / 100.0;
      Performance performance = new Performance(workers, poolSize, parseMs, graphMs,
          Math.round((parseMs + graphMs) * 10.0) / 10.0, graphHeap, jsonMs, jsonBytes,
          serialBaseline, speedup);
      Statistics statistics = new Statistics(sources.size(), statements, classes, methods,
          graph.nodes().size(), graph.edges().size(), syntaxErrors, performance, "O(S)", "O(S)");
      Snapshot snapshot = new Snapshot(program, List.copyOf(sources), List.copyOf(builders), List.copyOf(starts),
          graph, canonicalJson, statistics);
      return new BuildResult(snapshot, graphMs);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Graph construction interrupted", interrupted);
    } finally {
      pool.shutdown();
    }
  }

  private Parsed parse(SourceFile source) {
    JavaParser parser = new JavaParser(new CommonTokenStream(new JavaLexer(CharStreams.fromString(source.source()))));
    ParseTree tree = parser.compilationUnit();
    StructureCounter counter = new StructureCounter();
    counter.visit(tree);
    return new Parsed(source, parser, tree, counter);
  }

  private Built buildProgram(String program, List<Parsed> units) {
    DAGBuilder builder = new DAGBuilder();
    builder.setGraphScope(program, units.size() == 1 ? units.get(0).source().name() : "whole-program");
    ExceptionVisitor visitor = new ExceptionVisitor(builder, null);
    List<JavaParser.CompilationUnitContext> trees = units.stream()
        .map(unit -> (JavaParser.CompilationUnitContext) unit.tree()).toList();
    visitor.visitCompilationUnits(trees, units.stream().map(unit -> unit.source().name()).toList());
    return new Built(builder, visitor.getStartNode());
  }

  private static <T> List<T> ordered(List<Future<T>> futures) {
    List<T> result = new ArrayList<>(futures.size());
    for (Future<T> future : futures) {
      try { result.add(future.get()); }
      catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Worker interrupted", interrupted);
      } catch (ExecutionException failed) {
        Throwable cause = failed.getCause();
        if (cause instanceof RuntimeException runtime) throw runtime;
        throw new IllegalStateException("Worker failed", cause);
      }
    }
    return result;
  }

  private static List<SourceFile> normalizeSources(List<SourceFile> submitted) {
    if (submitted == null || submitted.isEmpty()) throw new IllegalArgumentException("At least one Java source file is required");
    List<SourceFile> result = new ArrayList<>();
    for (SourceFile file : submitted) {
      result.add(new SourceFile(safeName(file.name()), file.source() == null ? "" : file.source()));
    }
    return List.copyOf(result);
  }

  private static String safeName(String name) {
    if (name == null || name.isBlank()) return "Source.java";
    String normalized = name.replace('\\', '/');
    return normalized.substring(normalized.lastIndexOf('/') + 1);
  }
  private static double elapsedMs(long start) { return Math.round((System.nanoTime() - start) / 100_000.0) / 10.0; }
  private static long usedHeap() { Runtime runtime = Runtime.getRuntime(); return runtime.totalMemory() - runtime.freeMemory(); }
  private static long stabilizedHeap() { System.gc(); return usedHeap(); }

  private static final class StructureCounter extends JavaParserBaseVisitor<Void> {
    int classes; int methods; int statements;
    @Override public Void visitClassDeclaration(JavaParser.ClassDeclarationContext ctx) { classes++; return visitChildren(ctx); }
    @Override public Void visitMethodDeclaration(JavaParser.MethodDeclarationContext ctx) { methods++; return visitChildren(ctx); }
    @Override public Void visitConstructorDeclaration(JavaParser.ConstructorDeclarationContext ctx) { methods++; return visitChildren(ctx); }
    @Override public Void visitLocalVariableDeclaration(JavaParser.LocalVariableDeclarationContext ctx) {
      statements += ctx.VAR() != null ? 1 : ctx.variableDeclarators().variableDeclarator().size(); return visitChildren(ctx);
    }
    @Override public Void visitStatement(JavaParser.StatementContext ctx) {
      if (!(ctx.block() != null && ctx.TRY() == null)) statements++; return visitChildren(ctx);
    }
    @Override public Void visitCatchClause(JavaParser.CatchClauseContext ctx) { statements++; return visitChildren(ctx); }
  }
}
