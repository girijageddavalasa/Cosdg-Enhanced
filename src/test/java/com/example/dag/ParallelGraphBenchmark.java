package com.example.dag;

import com.example.dag.server.GraphWorkspace;
import com.example.dag.server.GraphWorkspace.SourceFile;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reproducible worker-count benchmark for the existing single-file 5K fixture. */
public final class ParallelGraphBenchmark {
  private static final int WARMUPS = 2;
  private static final int RUNS = 7;
  private record Sample(double parseMs, double graphMs, double totalMs, long memory, int nodes, int edges) {}
  private record Result(int workers, double parseMs, double graphMs, double totalMs,
                        long memoryBytes, int nodes, int edges, double speedup) {}

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
    Path sourcePath = root.resolve("benchmarks/Synthetic5000.java");
    String source = Files.readString(sourcePath);
    List<SourceFile> files = List.of(new SourceFile(sourcePath.getFileName().toString(), source));
    List<SourceFile> splitFiles = splitByClass(source);
    List<Result> results = benchmark(files);
    List<Result> splitResults = benchmark(splitFiles);

    Map<String, Object> output = new LinkedHashMap<>();
    output.put("fixture", "benchmarks/Synthetic5000.java");
    output.put("warmups", WARMUPS);
    output.put("runs", RUNS);
    output.put("architecture", "fixed worker pool across independent source files; deterministic input-order merge");
    output.put("memory", "Approximate Runtime heap increase after forced-GC stage baselines; not exact object sizing");
    output.put("singleFileResults", results);
    output.put("split25FileResults", splitResults);
    String json = new GsonBuilder().setPrettyPrinting().create().toJson(output);
    Files.writeString(root.resolve("parallel_graph_benchmark_results.json"), json);
    StringBuilder csv = new StringBuilder("fixture,workers,parse_ms,graph_ms,total_ms,memory_bytes,nodes,edges,speedup\n");
    appendCsv(csv, "single", results);
    appendCsv(csv, "split25", splitResults);
    Files.writeString(root.resolve("parallel_graph_benchmark_results.csv"), csv.toString());
    System.out.println(json);
  }

  private static List<Result> benchmark(List<SourceFile> files) {
    List<Result> results = new ArrayList<>();
    Map<Integer, List<Sample>> samples = new LinkedHashMap<>();
    for (int workers : List.of(1, 2, 4, 8)) samples.put(workers, new ArrayList<>());
    for (int round = 0; round < WARMUPS; round++) {
      for (int workers : List.of(1, 2, 4, 8)) measure(files, workers);
    }
    List<Integer> workerCounts = List.of(1, 2, 4, 8);
    for (int round = 0; round < RUNS; round++) {
      for (int offset = 0; offset < workerCounts.size(); offset++) {
        int workers = workerCounts.get((round + offset) % workerCounts.size());
        samples.get(workers).add(measure(files, workers));
      }
    }
    double serial = median(samples.get(1).stream().map(Sample::graphMs).toList());
    for (int workers : List.of(1, 2, 4, 8)) {
      List<Sample> values = samples.get(workers);
      Sample first = values.get(0);
      double graph = median(values.stream().map(Sample::graphMs).toList());
      results.add(new Result(workers,
          median(values.stream().map(Sample::parseMs).toList()), graph,
          median(values.stream().map(Sample::totalMs).toList()),
          medianLong(values.stream().map(Sample::memory).toList()), first.nodes(), first.edges(),
          Math.round((serial / graph) * 100.0) / 100.0));
    }
    return results;
  }

  private static List<SourceFile> splitByClass(String source) {
    String body = source.replaceFirst("package\\s+benchmark\\s*;", "").trim();
    String[] classes = body.split("(?=class ScaleClass)");
    List<SourceFile> result = new ArrayList<>();
    for (int i = 0; i < classes.length; i++) {
      if (!classes[i].isBlank()) result.add(new SourceFile("ScaleClass" + i + ".java", "package benchmark;\n" + classes[i]));
    }
    return result;
  }

  private static void appendCsv(StringBuilder csv, String fixture, List<Result> results) {
    for (Result row : results) csv.append(fixture).append(',').append(row.workers()).append(',').append(row.parseMs()).append(',')
        .append(row.graphMs()).append(',').append(row.totalMs()).append(',').append(row.memoryBytes()).append(',')
        .append(row.nodes()).append(',').append(row.edges()).append(',').append(row.speedup()).append('\n');
  }

  private static Sample measure(List<SourceFile> files, int workers) {
    System.gc();
    var stats = new GraphWorkspace().submit(files, workers, false, true).statistics();
    var measured = stats.measured();
    return new Sample(measured.parseMs(), measured.graphMs(), measured.totalMs(), measured.graphHeapBytes(),
        stats.nodes(), stats.edges());
  }
  private static double median(List<Double> values) {
    List<Double> sorted = values.stream().sorted().toList(); return sorted.get(sorted.size() / 2);
  }
  private static long medianLong(List<Long> values) {
    List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList(); return sorted.get(sorted.size() / 2);
  }
}
