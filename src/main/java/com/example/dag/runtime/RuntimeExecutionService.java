package com.example.dag.runtime;

import com.example.dag.DAGBuilder;
import com.example.dag.coverage.CoverageAnalyzer;
import com.example.dag.coverage.CoverageAnalyzer.CoverageMarking;
import com.example.dag.coverage.CoverageAnalyzer.CoverageResult;
import com.example.dag.graph.Edge;
import com.example.dag.graph.Node;
import com.example.dag.server.GraphWorkspace.Snapshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Compiles and runs instrumented source in a bounded child JVM. */
public final class RuntimeExecutionService {
  private static final String SENTINEL = "__COSDG_EVENTS__";
  private static final int OUTPUT_LIMIT = 1_048_576;

  public record Request(String mainClass, List<String> arguments, long timeoutMillis, String testCase) {
    public Request {
      arguments = arguments == null ? List.of() : List.copyOf(arguments);
      timeoutMillis = Math.max(100, Math.min(timeoutMillis <= 0 ? 5_000 : timeoutMillis, 30_000));
      testCase = testCase == null || testCase.isBlank() ? "runtime-test" : testCase;
    }
  }
  public record Result(String status, boolean compilationSuccess, Integer exitCode, boolean timedOut,
                       int filesExecuted, double compileMs, double executionMs, double totalRuntimeMs,
                       String stdout, String stderr,
                       Set<String> visitedNodes, Set<String> visitedEdges,
                       Set<String> taggedClassMemberEdges, Set<String> runtimeTargets,
                       Set<String> runtimeTargetMethods,
                       Map<String, Set<String>> exceptionTypes, Set<String> eventTypes,
                       Map<String, String> instrumentationMap, List<String> unsupported,
                       CoverageResult coverage) {}

  public Result execute(Snapshot snapshot, Request request) {
    if (snapshot == null) return failure("NO_GRAPH", "No graph has been generated", request);
    if (request.mainClass() == null || request.mainClass().isBlank()) return failure("INVALID_REQUEST", "A main class is required", request);
    SourceInstrumenter.ProgramResult instrumented;
    try { instrumented = new SourceInstrumenter().instrumentAll(snapshot.program(), snapshot.sources(), snapshot.graph()); }
    catch (RuntimeException failed) { return failure("INSTRUMENTATION_FAILED", failed.getMessage(), request); }

    Path work = null;
    try {
      Path base = Path.of("target", "runtime-instrumented").toAbsolutePath().normalize();
      Files.createDirectories(base);
      work = Files.createTempDirectory(base, "run-");
      Path classes = Files.createDirectories(work.resolve("classes"));
      Path collector = work.resolve("__CosdgRuntime.java");
      List<Path> sourcePaths = new ArrayList<>();
      for (SourceInstrumenter.InstrumentedFile file : instrumented.files()) {
        Path source = work.resolve(file.name());
        Files.writeString(source, file.source(), StandardCharsets.UTF_8);
        sourcePaths.add(source);
      }
      Files.writeString(collector, instrumented.collectorSource(), StandardCharsets.UTF_8);

      long compileStart = System.nanoTime();
      List<String> compileCommand = new ArrayList<>(List.of(tool("javac"), "-proc:none", "-encoding", "UTF-8", "-d", classes.toString()));
      sourcePaths.forEach(path -> compileCommand.add(path.toString())); compileCommand.add(collector.toString());
      ProcessResult compilation = run(compileCommand, work, 30_000);
      double compileMs = elapsed(compileStart);
      if (compilation.timedOut || compilation.exitCode != 0) {
        return result("COMPILE_ERROR", false, compilation.exitCode, compilation.timedOut, compileMs, 0,
            compilation.stdout, compilation.stderr, instrumented, Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Set.of(), snapshot);
      }

      List<String> command = new ArrayList<>(List.of(tool("java"), "-Xmx128m", "-XX:MaxMetaspaceSize=128m",
          "-cp", classes.toString(), request.mainClass()));
      command.addAll(request.arguments());
      long executionStart = System.nanoTime();
      ProcessResult execution = run(command, work, request.timeoutMillis());
      double executionMs = elapsed(executionStart);
      ParsedEvents events = parseEvents(execution.stdout);
      markAuthoritative(snapshot, events.nodes, events.edges);
      String status = execution.timedOut ? "TIMEOUT" : execution.exitCode == 0 ? "PASSED" : "RUNTIME_ERROR";
      return result(status, true, execution.exitCode, execution.timedOut, compileMs, executionMs,
          events.userOutput, execution.stderr, instrumented, events.nodes, events.edges,
          events.taggedMembers, events.runtimeTargets, events.exceptionTypes, events.types, snapshot);
    } catch (IOException failed) {
      return failure("EXECUTION_FAILED", failed.getMessage(), request);
    } finally {
      if (work != null) deleteTree(work);
    }
  }

  private Result result(String status, boolean compiled, Integer exit, boolean timeout, double compileMs,
                        double executionMs, String stdout, String stderr, SourceInstrumenter.ProgramResult instrumented,
                        Set<String> nodes, Set<String> edges, Map<String, Set<String>> exceptionTypes,
                        Set<String> eventTypes, Snapshot snapshot) {
    return result(status, compiled, exit, timeout, compileMs, executionMs, stdout, stderr, instrumented,
        nodes, edges, Set.of(), Set.of(), exceptionTypes, eventTypes, snapshot);
  }

  private Result result(String status, boolean compiled, Integer exit, boolean timeout, double compileMs,
                        double executionMs, String stdout, String stderr, SourceInstrumenter.ProgramResult instrumented,
                        Set<String> nodes, Set<String> edges, Set<String> taggedMembers, Set<String> runtimeTargets,
                        Map<String, Set<String>> exceptionTypes, Set<String> eventTypes, Snapshot snapshot) {
    List<Node> allNodes = snapshot.builders().stream().flatMap(b -> b.getAllNodes().stream()).toList();
    List<Edge> allEdges = snapshot.builders().stream().flatMap(b -> b.getAllEdges().stream()).toList();
    Set<String> available = Set.of("statement", "method", "polymorphic", "throw", "catch", "exceptionType", "exceptionFlow");
    CoverageResult coverage = CoverageAnalyzer.compute(new CoverageMarking(nodes, edges,
        taggedMembers.isEmpty() ? null : taggedMembers, exceptionTypes, true, available), allNodes, allEdges);
    Set<String> targetMethods = new LinkedHashSet<>();
    snapshot.graph().nodes().stream().filter(node -> runtimeTargets.contains(node.internalId())).forEach(node ->
        targetMethods.add(node.classId() + "." + node.methodId() + "()"));
    return new Result(status, compiled, exit, timeout, instrumented.files().size(), compileMs, executionMs,
        Math.round((compileMs + executionMs) * 10.0) / 10.0, stdout, stderr,
        Set.copyOf(nodes), Set.copyOf(edges), Set.copyOf(taggedMembers), Set.copyOf(runtimeTargets), Set.copyOf(targetMethods), Map.copyOf(exceptionTypes), Set.copyOf(eventTypes),
        instrumented.instrumentationMap(), instrumented.unsupported(), coverage);
  }

  private Result failure(String status, String error, Request request) {
    return new Result(status, false, null, false, 0, 0, 0, 0, "", error == null ? "" : error,
        Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Set.of(), Map.of(), List.of(), null);
  }

  private static ProcessResult run(List<String> command, Path directory, long timeoutMs) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
    Map<String, String> original = System.getenv();
    builder.environment().clear();
    copyEnv(original, builder.environment(), "SystemRoot", "WINDIR", "TEMP", "TMP");
    builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    Process process = builder.start();
    CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
    CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
    boolean completed;
    try { completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); process.destroyForcibly(); completed = false; }
    if (!completed) { process.destroyForcibly(); try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
    return new ProcessResult(completed ? process.exitValue() : -1, !completed, stdout.join(), stderr.join());
  }

  private static void copyEnv(Map<String, String> from, Map<String, String> to, String... names) {
    for (String name : names) if (from.get(name) != null) to.put(name, from.get(name));
  }
  private static String read(InputStream stream) {
    try (stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192]; int total = 0, read;
      while ((read = stream.read(buffer)) >= 0) {
        int accepted = Math.min(read, OUTPUT_LIMIT - total);
        if (accepted > 0) bytes.write(buffer, 0, accepted);
        total += accepted;
      }
      return bytes.toString(StandardCharsets.UTF_8);
    } catch (IOException failed) { return "[output capture failed: " + failed.getMessage() + "]"; }
  }

  private static ParsedEvents parseEvents(String stdout) {
    Set<String> nodes = new LinkedHashSet<>(), edges = new LinkedHashSet<>(), tagged = new LinkedHashSet<>(), targets = new LinkedHashSet<>(), types = new LinkedHashSet<>();
    Map<String, Set<String>> exceptionTypes = new LinkedHashMap<>();
    StringBuilder user = new StringBuilder();
    for (String line : stdout.split("\\R", -1)) {
      if (!line.startsWith(SENTINEL)) { if (!line.isEmpty()) user.append(line).append(System.lineSeparator()); continue; }
      try {
        String payload = new String(Base64.getDecoder().decode(line.substring(SENTINEL.length())), StandardCharsets.UTF_8);
        for (String event : payload.split("\\n")) {
          int separator = event.indexOf('|'); if (separator < 1) continue;
          String type = event.substring(0, separator), id = event.substring(separator + 1); types.add(type);
          if (type.equals("NODE_HIT") || type.equals("METHOD_ENTER") || type.equals("EXCEPTION_THROW") || type.equals("EXCEPTION_CATCH")) nodes.add(id);
          if (type.equals("EDGE_HIT") || type.equals("METHOD_CALL") || type.equals("BRANCH") || type.equals("LOOP")) edges.add(id);
          if (type.equals("INHERITANCE_MEMBER")) tagged.add(id);
          if (type.equals("RUNTIME_TARGET")) targets.add(id);
          if (type.equals("EXCEPTION_TYPE")) { int equals = id.indexOf('='); if (equals > 0) exceptionTypes.computeIfAbsent(id.substring(0, equals), ignored -> new LinkedHashSet<>()).add(id.substring(equals + 1)); }
        }
      } catch (IllegalArgumentException ignored) { user.append("[invalid COSDG event payload]").append(System.lineSeparator()); }
    }
    return new ParsedEvents(user.toString().stripTrailing(), nodes, edges, tagged, targets, exceptionTypes, types);
  }

  private static void markAuthoritative(Snapshot snapshot, Set<String> nodes, Set<String> edges) {
    snapshot.builders().forEach(builder -> {
      builder.getAllNodes().stream().filter(node -> nodes.contains(node.id)).forEach(node -> { node.covered = true; node.visitCount++; });
      builder.getAllEdges().stream().filter(edge -> edges.contains(edge.label)).forEach(edge -> { edge.covered = true; edge.traversalCount++; });
    });
  }
  private static String tool(String name) {
    String suffix = System.getProperty("os.name", "").toLowerCase().contains("win") ? ".exe" : "";
    return Path.of(System.getProperty("java.home"), "bin", name + suffix).toString();
  }
  private static double elapsed(long start) { return Math.round((System.nanoTime() - start) / 100_000.0) / 10.0; }
  private static void deleteTree(Path root) {
    try (var paths = Files.walk(root)) { paths.sorted((a, b) -> b.compareTo(a)).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} }); }
    catch (IOException ignored) {}
  }
  private record ProcessResult(int exitCode, boolean timedOut, String stdout, String stderr) {}
  private record ParsedEvents(String userOutput, Set<String> nodes, Set<String> edges, Set<String> taggedMembers, Set<String> runtimeTargets,
                              Map<String, Set<String>> exceptionTypes, Set<String> types) {}
}
