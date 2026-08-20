package com.example.dag;

import com.example.dag.graph.Edge;
import com.example.dag.graph.Node;
import com.example.dag.visitor.ExceptionVisitor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Reproducible, graph-construction-only benchmark. */
public final class GraphComplexityBenchmark {

  private static final int WARMUP_RUNS = 2;
  private static final int MEASURED_RUNS = 7;

  private record Corpus(String name, List<Path> files) {}
  private record Parsed(JavaParser parser, ParseTree tree) {}

  private static final class Sample {
    double parseMs;
    double graphMs;
    double totalMs;
    long heapBefore;
    long heapAfterParse;
    long heapAfterGraph;
    long graphHeapIncrease;
    int vertices;
    int edges;
    double jsonExportMs;
    int jsonExportBytes;
    double mermaidGenerationMs;
    int mermaidBytes;
  }

  private static final class Result {
    String program;
    int sourceFileCount;
    List<String> sourceFiles;
    int classCount;
    int methodCount;
    int statementCount;
    int syntaxErrors;
    int vertices;
    int edges;
    Map<String, Integer> vertexTypes;
    Map<String, Integer> edgeTypes;
    double medianParseMs;
    double medianGraphMs;
    double medianTotalMs;
    long medianHeapBeforeBytes;
    long medianHeapAfterParseBytes;
    long medianHeapAfterGraphBytes;
    long medianGraphHeapIncreaseBytes;
    double approximateBytesPerGraphElement;
    double verticesPerStatement;
    double edgesPerStatement;
    List<Double> parseMsSamples;
    List<Double> graphMsSamples;
    List<Long> graphHeapIncreaseSamples;
    double medianJsonExportMs;
    int medianJsonExportBytes;
    double medianMermaidGenerationMs;
    int medianMermaidBytes;
    int currentMermaidReachableVertices;
    int currentMermaidReachableEdges;
  }

  private static final class StructureCounter extends JavaParserBaseVisitor<Void> {
    int classes;
    int methods;
    int statements;

    @Override public Void visitClassDeclaration(JavaParser.ClassDeclarationContext ctx) {
      classes++;
      return visitChildren(ctx);
    }

    @Override public Void visitMethodDeclaration(JavaParser.MethodDeclarationContext ctx) {
      methods++;
      return visitChildren(ctx);
    }

    @Override public Void visitConstructorDeclaration(JavaParser.ConstructorDeclarationContext ctx) {
      methods++;
      return visitChildren(ctx);
    }

    @Override public Void visitLocalVariableDeclaration(JavaParser.LocalVariableDeclarationContext ctx) {
      statements += ctx.VAR() != null ? 1 : ctx.variableDeclarators().variableDeclarator().size();
      return visitChildren(ctx);
    }

    @Override public Void visitStatement(JavaParser.StatementContext ctx) {
      if (!(ctx.block() != null && ctx.TRY() == null)) statements++;
      return visitChildren(ctx);
    }

    @Override public Void visitCatchClause(JavaParser.CatchClauseContext ctx) {
      statements++;
      return visitChildren(ctx);
    }
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
    Path synthetic5000 = generateSynthetic5000(root);
    List<Path> allSources;
    try (var stream = Files.walk(root.resolve("src/main/java"))) {
      allSources = stream.filter(path -> path.toString().endsWith(".java"))
          .sorted().toList();
    }

    List<Corpus> corpora = List.of(
        new Corpus("code", List.of(root.resolve("code.txt"))),
        new Corpus("core_graph_classes", List.of(
            root.resolve("src/main/java/com/example/dag/graph/Node.java"),
            root.resolve("src/main/java/com/example/dag/graph/Edge.java"),
            root.resolve("src/main/java/com/example/dag/Tracer.java"))),
        new Corpus("code500", List.of(root.resolve("code500.txt"))),
        new Corpus("code1000", List.of(root.resolve("code1000.txt"))),
        new Corpus("synthetic5000", List.of(synthetic5000)),
        new Corpus("project_main_sources", allSources)
    );

    // Load all source text before heap baselines so file buffers are excluded.
    Map<Path, String> sourceText = new LinkedHashMap<>();
    for (Corpus corpus : corpora) {
      for (Path file : corpus.files()) sourceText.computeIfAbsent(file, GraphComplexityBenchmark::read);
    }

    List<Result> results = new ArrayList<>();
    for (Corpus corpus : corpora) {
      for (int i = 0; i < WARMUP_RUNS; i++) measure(corpus, sourceText);

      List<Sample> samples = new ArrayList<>();
      for (int i = 0; i < MEASURED_RUNS; i++) samples.add(measure(corpus, sourceText));
      results.add(summarize(root, corpus, sourceText, samples));
    }
    results.sort(Comparator.comparingInt(result -> result.statementCount));

    Map<String, Object> output = new LinkedHashMap<>();
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("generatedAt", Instant.now().toString());
    metadata.put("javaVersion", System.getProperty("java.version"));
    metadata.put("vmName", System.getProperty("java.vm.name"));
    metadata.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
    metadata.put("warmupRuns", WARMUP_RUNS);
    metadata.put("measuredRuns", MEASURED_RUNS);
    metadata.put("timing", "System.nanoTime; parsing and graph construction timed separately; GC excluded");
    metadata.put("memory", "Runtime used heap after explicit GC; graph increase = after graph - after parse; approximate, not object sizing");
    metadata.put("statementDefinition", "local declarators + non-block statement grammar nodes + catch clauses");
    metadata.put("multiFileMode", "independent compilation units aggregated; no cross-file linking");
    metadata.put("varBugStatus", "fixed by handling the grammar's VAR local-declaration alternative");
    metadata.put("excluded", List.of("HTML/template file I/O", "SVG layout", "path enumeration", "test generation", "coverage analysis", "browser rendering"));
    metadata.put("exportTiming", "compact full-graph JSON DTO and current reachable-root Mermaid text; template/file I/O/browser excluded");
    output.put("metadata", metadata);
    output.put("results", results);

    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    Files.writeString(root.resolve("graph_complexity_results.json"), gson.toJson(output));
    Files.writeString(root.resolve("graph_complexity_results.csv"), toCsv(results));
    Result syntheticResult = results.stream()
        .filter(result -> "synthetic5000".equals(result.program)).findFirst().orElseThrow();
    Files.writeString(root.resolve("large_program_benchmark_results.json"),
        gson.toJson(Map.of("metadata", metadata, "result", syntheticResult)));
    System.out.println(gson.toJson(output));
  }

  private static Sample measure(Corpus corpus, Map<Path, String> sourceText) {
    forceGc();
    Sample sample = new Sample();
    sample.heapBefore = usedHeap();

    long parseStart = System.nanoTime();
    List<Parsed> parsedFiles = new ArrayList<>();
    for (Path file : corpus.files()) {
      JavaParser parser = new JavaParser(new CommonTokenStream(
          new JavaLexer(CharStreams.fromString(sourceText.get(file)))));
      parsedFiles.add(new Parsed(parser, parser.compilationUnit()));
    }
    sample.parseMs = nanosToMs(System.nanoTime() - parseStart);
    forceGc();
    sample.heapAfterParse = usedHeap();

    long graphStart = System.nanoTime();
    List<DAGBuilder> builders = new ArrayList<>();
    for (Parsed parsed : parsedFiles) {
      DAGBuilder builder = new DAGBuilder();
      new ExceptionVisitor(builder, null).visit(parsed.tree());
      builders.add(builder);
    }
    sample.graphMs = nanosToMs(System.nanoTime() - graphStart);
    sample.totalMs = sample.parseMs + sample.graphMs;
    forceGc();
    sample.heapAfterGraph = usedHeap();
    sample.graphHeapIncrease = sample.heapAfterGraph - sample.heapAfterParse;
    sample.vertices = builders.stream().mapToInt(builder -> builder.getAllNodes().size()).sum();
    sample.edges = builders.stream().mapToInt(builder -> builder.getAllEdges().size()).sum();

    long jsonStart = System.nanoTime();
    String json = exportGraphJson(builders);
    sample.jsonExportMs = nanosToMs(System.nanoTime() - jsonStart);
    sample.jsonExportBytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

    long mermaidStart = System.nanoTime();
    String mermaid = currentMermaid(builders);
    sample.mermaidGenerationMs = nanosToMs(System.nanoTime() - mermaidStart);
    sample.mermaidBytes = mermaid.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    return sample;
  }

  private static Result summarize(Path root, Corpus corpus, Map<Path, String> sourceText,
                                  List<Sample> samples) {
    Result result = new Result();
    result.program = corpus.name();
    result.sourceFileCount = corpus.files().size();
    result.sourceFiles = corpus.files().stream().map(root::relativize)
        .map(Path::toString).toList();

    List<DAGBuilder> builders = new ArrayList<>();
    for (Path file : corpus.files()) {
      JavaParser parser = new JavaParser(new CommonTokenStream(
          new JavaLexer(CharStreams.fromString(sourceText.get(file)))));
      ParseTree tree = parser.compilationUnit();
      result.syntaxErrors += parser.getNumberOfSyntaxErrors();
      StructureCounter counter = new StructureCounter();
      counter.visit(tree);
      result.classCount += counter.classes;
      result.methodCount += counter.methods;
      result.statementCount += counter.statements;
      DAGBuilder builder = new DAGBuilder();
      new ExceptionVisitor(builder, null).visit(tree);
      builders.add(builder);
    }

    result.vertexTypes = countVertexTypes(builders);
    result.edgeTypes = countEdgeTypes(builders);
    result.vertices = builders.stream().mapToInt(builder -> builder.getAllNodes().size()).sum();
    result.edges = builders.stream().mapToInt(builder -> builder.getAllEdges().size()).sum();
    result.medianParseMs = medianDouble(samples.stream().map(sample -> sample.parseMs).toList());
    result.medianGraphMs = medianDouble(samples.stream().map(sample -> sample.graphMs).toList());
    result.medianTotalMs = medianDouble(samples.stream().map(sample -> sample.totalMs).toList());
    result.medianHeapBeforeBytes = medianLong(samples.stream().map(sample -> sample.heapBefore).toList());
    result.medianHeapAfterParseBytes = medianLong(samples.stream().map(sample -> sample.heapAfterParse).toList());
    result.medianHeapAfterGraphBytes = medianLong(samples.stream().map(sample -> sample.heapAfterGraph).toList());
    result.medianGraphHeapIncreaseBytes = medianLong(samples.stream().map(sample -> sample.graphHeapIncrease).toList());
    int graphElements = result.vertices + result.edges;
    result.approximateBytesPerGraphElement = graphElements == 0 ? 0.0
        : result.medianGraphHeapIncreaseBytes / (double) graphElements;
    result.verticesPerStatement = result.statementCount == 0 ? 0.0
        : result.vertices / (double) result.statementCount;
    result.edgesPerStatement = result.statementCount == 0 ? 0.0
        : result.edges / (double) result.statementCount;
    result.parseMsSamples = samples.stream().map(sample -> round(sample.parseMs)).toList();
    result.graphMsSamples = samples.stream().map(sample -> round(sample.graphMs)).toList();
    result.graphHeapIncreaseSamples = samples.stream().map(sample -> sample.graphHeapIncrease).toList();
    result.medianJsonExportMs = medianDouble(samples.stream().map(sample -> sample.jsonExportMs).toList());
    result.medianJsonExportBytes = (int) medianLong(samples.stream().map(sample -> (long) sample.jsonExportBytes).toList());
    result.medianMermaidGenerationMs = medianDouble(samples.stream().map(sample -> sample.mermaidGenerationMs).toList());
    result.medianMermaidBytes = (int) medianLong(samples.stream().map(sample -> (long) sample.mermaidBytes).toList());
    int[] reachable = reachableCounts(builders);
    result.currentMermaidReachableVertices = reachable[0];
    result.currentMermaidReachableEdges = reachable[1];
    return result;
  }

  private static int[] reachableCounts(List<DAGBuilder> builders) {
    int vertices = 0;
    int edges = 0;
    for (DAGBuilder builder : builders) {
      if (builder.getAllNodes().isEmpty()) continue;
      Queue<Node> queue = new ArrayDeque<>();
      Set<String> visited = new HashSet<>();
      queue.add(builder.getAllNodes().get(0));
      while (!queue.isEmpty()) {
        Node node = queue.remove();
        if (!visited.add(node.id)) continue;
        vertices++;
        edges += node.edges.size();
        for (Edge edge : node.edges) queue.add(edge.to);
      }
    }
    return new int[] {vertices, edges};
  }

  private static String exportGraphJson(List<DAGBuilder> builders) {
    List<Map<String, Object>> nodes = new ArrayList<>();
    List<Map<String, Object>> edges = new ArrayList<>();
    for (DAGBuilder builder : builders) {
      for (Node node : builder.getAllNodes()) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", node.id);
        item.put("type", node.type);
        if (!node.exceptionTypes.isEmpty()) item.put("exceptionTypes", node.exceptionTypes);
        nodes.add(item);
      }
      for (Edge edge : builder.getAllEdges()) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", edge.label);
        item.put("type", edge.type);
        item.put("source", edge.from.id);
        item.put("destination", edge.to.id);
        if (edge.branch != null) item.put("branch", edge.branch);
        if (edge.exceptionType != null) item.put("exceptionType", edge.exceptionType);
        if (!edge.tags.isEmpty()) item.put("tags", edge.tags);
        edges.add(item);
      }
    }
    return new Gson().toJson(Map.of("nodes", nodes, "edges", edges));
  }

  /** Mirrors the production exporter's reachable-root, monolithic Mermaid strategy. */
  private static String currentMermaid(List<DAGBuilder> builders) {
    StringBuilder output = new StringBuilder("graph TD\n");
    for (DAGBuilder builder : builders) {
      if (builder.getAllNodes().isEmpty()) continue;
      Queue<Node> queue = new ArrayDeque<>();
      Set<String> visited = new HashSet<>();
      queue.add(builder.getAllNodes().get(0));
      while (!queue.isEmpty()) {
        Node node = queue.remove();
        if (!visited.add(node.id)) continue;
        output.append("    ").append(node.id).append("[\"")
            .append(node.type.replace("\"", "'")).append("\"]\n");
        for (Edge edge : node.edges) queue.add(edge.to);
      }
      visited.clear();
      queue.add(builder.getAllNodes().get(0));
      while (!queue.isEmpty()) {
        Node node = queue.remove();
        if (!visited.add(node.id)) continue;
        for (Edge edge : node.edges) {
          output.append("    ").append(node.id).append(" -->|")
              .append(edge.label).append("| ").append(edge.to.id).append('\n');
          queue.add(edge.to);
        }
      }
    }
    return output.toString();
  }

  private static Path generateSynthetic5000(Path root) throws Exception {
    Path directory = root.resolve("benchmarks");
    Files.createDirectories(directory);
    Path output = directory.resolve("Synthetic5000.java");
    StringBuilder source = new StringBuilder("package benchmark;\n\n");
    for (int classIndex = 0; classIndex < 25; classIndex++) {
      source.append("class ScaleClass").append(classIndex).append(" {\n");
      for (int methodIndex = 0; methodIndex < 20; methodIndex++) {
        source.append("  int method").append(methodIndex).append("(int input) {\n")
            .append("    int value = input + 1;\n");
        for (int statement = 0; statement < 7; statement++) {
          source.append("    value = value + ").append(statement + 2).append(";\n");
        }
        if (methodIndex == 0) source.append("    value = value * 2;\n");
        else source.append("    value = method").append(methodIndex - 1).append("(value);\n");
        source.append("    return value;\n  }\n");
      }
      source.append("}\n\n");
    }
    Files.writeString(output, source.toString());
    return output;
  }

  private static Map<String, Integer> countVertexTypes(List<DAGBuilder> builders) {
    Map<String, Integer> counts = initializedCounts(List.of(
        "class_entry", "method_entry", "statement", "call", "parameter", "try", "catch"));
    for (DAGBuilder builder : builders) for (Node node : builder.getAllNodes()) {
      String type = node.type;
      if (type.contains("CLASS_ENTRY")) increment(counts, "class_entry");
      else if (type.contains("METHOD_ENTRY")) increment(counts, "method_entry");
      else if (type.contains("CALL")) increment(counts, "call");
      else if (type.contains("FORMAL_") || type.contains("ACTUAL_")) increment(counts, "parameter");
      else if (type.contains("TRY_BLOCK_START")) increment(counts, "try");
      else if (type.contains("CATCH_START")) increment(counts, "catch");
      else increment(counts, "statement");
    }
    return counts;
  }

  private static Map<String, Integer> countEdgeTypes(List<DAGBuilder> builders) {
    Map<String, Integer> counts = initializedCounts(List.of(
        "control", "data", "class_member", "inheritance", "method_call",
        "simple_method_call", "inherited_method_call", "polymorphic_call",
        "parameter_in", "parameter_out", "summary", "exception_throw", "exception_catch"));
    for (DAGBuilder builder : builders) for (Edge edge : builder.getAllEdges()) {
      switch (edge.type) {
        case "CONTROL_DEPENDENCE" -> increment(counts, "control");
        case "DATA_DEPENDENCE" -> increment(counts, "data");
        case "CLASS_MEMBER" -> increment(counts, "class_member");
        case "INHERITANCE" -> increment(counts, "inheritance");
        case "SIMPLE_METHOD_CALL" -> { increment(counts, "simple_method_call"); increment(counts, "method_call"); }
        case "INHERITED_METHOD_CALL" -> { increment(counts, "inherited_method_call"); increment(counts, "method_call"); }
        case "POLYMORPHIC_METHOD_CALL" -> increment(counts, "polymorphic_call");
        case "PARAMETER_IN" -> increment(counts, "parameter_in");
        case "PARAMETER_OUT" -> increment(counts, "parameter_out");
        case "SUMMARY" -> increment(counts, "summary");
        case "EXCEPTION_THROW" -> increment(counts, "exception_throw");
        case "EXCEPTION_CATCH" -> increment(counts, "exception_catch");
        default -> { }
      }
    }
    return counts;
  }

  private static Map<String, Integer> initializedCounts(List<String> names) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String name : names) counts.put(name, 0);
    return counts;
  }

  private static void increment(Map<String, Integer> counts, String name) {
    counts.put(name, counts.get(name) + 1);
  }

  private static String toCsv(List<Result> results) {
    StringBuilder csv = new StringBuilder("program,source_files,classes,methods,S,N,M,parse_ms,graph_ms,total_ms,heap_before_bytes,heap_after_parse_bytes,heap_after_graph_bytes,graph_heap_increase_bytes,approx_bytes_per_element\n");
    for (Result result : results) {
      csv.append(result.program).append(',').append(result.sourceFileCount).append(',')
          .append(result.classCount).append(',').append(result.methodCount).append(',')
          .append(result.statementCount).append(',').append(result.vertices).append(',')
          .append(result.edges).append(',').append(format(result.medianParseMs)).append(',')
          .append(format(result.medianGraphMs)).append(',').append(format(result.medianTotalMs)).append(',')
          .append(result.medianHeapBeforeBytes).append(',').append(result.medianHeapAfterParseBytes).append(',')
          .append(result.medianHeapAfterGraphBytes).append(',').append(result.medianGraphHeapIncreaseBytes).append(',')
          .append(format(result.approximateBytesPerGraphElement)).append('\n');
    }
    return csv.toString();
  }

  private static String read(Path path) {
    try { return Files.readString(path); }
    catch (Exception error) { throw new IllegalStateException("Cannot read " + path, error); }
  }

  private static void forceGc() {
    for (int i = 0; i < 3; i++) {
      System.gc();
      try { Thread.sleep(25); }
      catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
  }

  private static long usedHeap() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  private static double nanosToMs(long nanos) { return nanos / 1_000_000.0; }
  private static double round(double value) { return Math.round(value * 1000.0) / 1000.0; }
  private static String format(double value) { return String.format(Locale.ROOT, "%.3f", value); }

  private static double medianDouble(List<Double> values) {
    List<Double> sorted = values.stream().sorted().toList();
    return round(sorted.get(sorted.size() / 2));
  }

  private static long medianLong(List<Long> values) {
    List<Long> sorted = values.stream().sorted().toList();
    return sorted.get(sorted.size() / 2);
  }
}
