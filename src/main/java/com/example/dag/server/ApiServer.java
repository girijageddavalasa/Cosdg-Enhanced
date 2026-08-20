package com.example.dag.server;

import com.example.dag.DAGBuilder;
import com.example.dag.coverage.CoverageAnalyzer;
import com.example.dag.coverage.CoverageTracker;
import com.example.dag.coverage.RuntimeCoverageSession;
import com.example.dag.graph.Edge;
import com.example.dag.graph.Node;
import com.example.dag.runtime.RuntimeExecutionService;
import com.example.dag.server.GraphProjectionService.Query;
import com.example.dag.server.GraphWorkspace.Snapshot;
import com.example.dag.server.GraphWorkspace.SourceFile;
import com.example.dag.testcase.GeneratedTestCase;
import com.example.dag.testcase.PathEnumerator;
import com.example.dag.testcase.TestCaseGenerator;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static spark.Spark.*;

/** HTTP boundary for bounded graph browsing and legacy opt-in analysis actions. */
public final class ApiServer {
  private static final Gson GSON = new Gson();
  private static final GraphProjectionService PROJECTIONS = new GraphProjectionService();
  private static final RuntimeExecutionService EXECUTION = new RuntimeExecutionService();
  private static CoverageTracker staticCoverageTracker;
  private static RuntimeCoverageSession runtimeSession;

  private ApiServer() {}

  public static void start(GraphWorkspace workspace) {
    port(8080);
    before((request, response) -> {
      response.header("Access-Control-Allow-Origin", "*");
      response.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
      response.header("Access-Control-Allow-Headers", "Content-Type");
    });
    options("/*", (request, response) -> "OK");

    get("/", (request, response) -> {
      response.type("text/html");
      return Files.readString(Path.of("template.html"));
    });

    get("/api/source", (request, response) -> json(response, workspace.current().sources()));
    post("/api/source", (request, response) -> {
      SourceSubmission submission = GSON.fromJson(request.body(), SourceSubmission.class);
      if (submission == null || submission.files == null || submission.files.isEmpty()) {
        response.status(400);
        return json(response, new ErrorResponse("files[] must contain at least one Java source"));
      }
      int workers = submission.workers == null ? 1 : submission.workers;
      boolean baseline = Boolean.TRUE.equals(submission.measureSerialBaseline);
      Snapshot snapshot = workspace.submit(submission.files, workers, baseline);
      staticCoverageTracker = null;
      runtimeSession = null;
      return json(response, snapshot.statistics());
    });

    get("/api/stats", (request, response) -> json(response, workspace.current().statistics()));
    get("/api/classes", (request, response) -> json(response, PROJECTIONS.classes(workspace.current().graph())));
    get("/api/methods", (request, response) -> json(response, PROJECTIONS.methods(
        workspace.current().graph(), request.queryParams("file"), request.queryParams("classId"))));
    get("/api/graph", (request, response) -> {
      var projection = PROJECTIONS.project(workspace.current().graph(),
        new Query(request.queryParams("file"), request.queryParams("classId"), request.queryParams("methodId"),
            request.queryParams("nodeId"), request.queryParams("nodeType"), request.queryParams("edgeType"),
            integer(request.queryParams("limit"), 250), integer(request.queryParams("edgeLimit"), 750),
            integer(request.queryParams("depth"), 1)));
      JsonObject view = GSON.toJsonTree(projection).getAsJsonObject();
      if (runtimeSession != null) {
        var marking = runtimeSession.marking();
        view.add("visitedNodes", GSON.toJsonTree(projection.nodes().stream()
            .filter(node -> marking.visitedNodeIds().contains(node.internalId())).map(node -> node.id()).toList()));
        view.add("visitedEdges", GSON.toJsonTree(projection.edges().stream()
            .filter(edge -> marking.visitedEdgeLabels().contains(edge.internalId())).map(edge -> edge.id()).toList()));
      } else {
        view.add("visitedNodes", GSON.toJsonTree(List.of()));
        view.add("visitedEdges", GSON.toJsonTree(List.of()));
      }
      return json(response, view);
    });
    get("/api/export", (request, response) -> {
      response.type("application/json");
      response.header("Content-Disposition", "attachment; filename=canonical-graph.json");
      return workspace.current().canonicalJson();
    });
    post("/api/paths", (request, response) -> {
      PathEnumerator.EnumerationResult result = PathEnumerator.enumerate(componentRoots(workspace.current()), pathOptions(request));
      return json(response, new PathSummary(result.paths().size(), result.pathLimit(), result.pathLengthLimit(),
          result.traversalStateLimit(), result.timeLimitMillis(), result.status().name(), result.stopReason().name(),
          result.truncated(), result.generationTimeMs(), result.traversalStates(), result.maximumPathLength(),
          result.componentsStarted()));
    });
    get("/api/coverage", (request, response) -> {
      Snapshot snapshot = workspace.current();
      CoverageAnalyzer.CoverageMarking staticMarking = staticCoverageTracker == null
          ? CoverageAnalyzer.CoverageMarking.staticPrediction(java.util.Set.of(), java.util.Set.of())
          : staticCoverageTracker.toMarking();
      CoverageAnalyzer.CoverageResult predicted = CoverageAnalyzer.compute(staticMarking, allNodes(snapshot), allEdges(snapshot));
      RuntimeCoverageSession.Summary observed = runtimeSession == null ? null
          : runtimeSession.summarize(allNodes(snapshot), allEdges(snapshot));
      return json(response, new CoverageView(predicted,
          observed == null || observed.testCount() == 0 ? null : observed.coverage(), observed));
    });
    post("/api/coverage/session", (request, response) -> {
      Snapshot snapshot = workspace.current();
      runtimeSession = new RuntimeCoverageSession(allNodes(snapshot).size(), allEdges(snapshot).size());
      return json(response, runtimeSession.summarize(allNodes(snapshot), allEdges(snapshot)));
    });
    post("/api/coverage/session/reset", (request, response) -> {
      Snapshot snapshot = workspace.current();
      runtimeSession = new RuntimeCoverageSession(allNodes(snapshot).size(), allEdges(snapshot).size());
      return json(response, runtimeSession.summarize(allNodes(snapshot), allEdges(snapshot)));
    });
    post("/api/execute", (request, response) -> {
      RuntimeRequest body = GSON.fromJson(request.body(), RuntimeRequest.class);
      if (body == null) { response.status(400); return json(response, new ErrorResponse("Execution request is required")); }
      RuntimeExecutionService.Result result = EXECUTION.execute(workspace.current(), new RuntimeExecutionService.Request(
          body.mainClass, body.arguments, body.timeoutMillis == null ? 5_000 : body.timeoutMillis, body.testCase));
      if (result.compilationSuccess()) {
        Snapshot snapshot = workspace.current();
        if (runtimeSession == null) runtimeSession = new RuntimeCoverageSession(allNodes(snapshot).size(), allEdges(snapshot).size());
        runtimeSession.add(body.testCase, result);
      }
      return json(response, result);
    });

    // Expensive legacy actions remain explicit and are never invoked by graph browsing.
    post("/generate-tests", (request, response) -> {
      Snapshot snapshot = workspace.current();
      DAGBuilder builder = firstBuilder(snapshot);
      Node start = firstStart(snapshot);
      String result = TestCaseGenerator.generate(combinedSource(snapshot), builder.exportAsText(start), start);
      Files.writeString(Path.of("testcases.json"), result);
      staticCoverageTracker = new CoverageTracker(allNodes(snapshot).size(), allEdges(snapshot).size());
      response.type("application/json");
      return result;
    });
    post("/get-all-paths", (request, response) -> {
      Snapshot snapshot = workspace.current();
      DAGBuilder builder = firstBuilder(snapshot);
      Node start = firstStart(snapshot);
      String result = TestCaseGenerator.generateAllPaths(combinedSource(snapshot), builder.exportAsText(start),
          componentRoots(snapshot), pathOptions(request));
      Files.writeString(Path.of("all_paths.json"), result);
      response.type("application/json");
      return result;
    });
    post("/track-coverage", (request, response) -> trackCoverage(workspace.current(), request.body(), response));
    get("/load-testcases", (request, response) -> {
      String filename = request.queryParams("file");
      filename = filename == null || filename.isBlank() ? "testcases.json" : Path.of(filename).getFileName().toString();
      Path file = Path.of(filename);
      response.type("application/json");
      if (!Files.exists(file)) { response.status(404); return GSON.toJson(new ErrorResponse("File not found: " + filename)); }
      return Files.readString(file);
    });

    System.out.println("\nAPI SERVER RUNNING:\nhttp://localhost:8080");
  }

  private static String trackCoverage(Snapshot snapshot, String bodyText, spark.Response response) {
    List<Node> nodes = allNodes(snapshot);
    List<Edge> edges = allEdges(snapshot);
    if (staticCoverageTracker == null) staticCoverageTracker = new CoverageTracker(nodes.size(), edges.size());
    JsonObject body = GSON.fromJson(bodyText, JsonObject.class);
    GeneratedTestCase test = new GeneratedTestCase();
    test.id = body.has("id") ? body.get("id").getAsString() : "TC_CLICK";
    test.dag_path = strings(body, "dag_path");
    test.dag_edges = strings(body, "dag_edges");
    staticCoverageTracker.track(test);
    CoverageAnalyzer.CoverageResult metrics = CoverageAnalyzer.compute(
        staticCoverageTracker.getCoveredNodes(), staticCoverageTracker.getCoveredEdges(), nodes, edges);
    JsonObject result = new JsonObject();
    result.add("coverage", GSON.toJsonTree(metrics));
    response.type("application/json");
    return GSON.toJson(result);
  }

  private static List<String> strings(JsonObject object, String name) {
    List<String> values = new ArrayList<>();
    if (object != null && object.has(name)) object.getAsJsonArray(name).forEach(value -> values.add(value.getAsString()));
    return values;
  }
  private static List<Node> allNodes(Snapshot snapshot) {
    return snapshot.builders().stream().flatMap(builder -> builder.getAllNodes().stream()).toList();
  }
  private static List<Edge> allEdges(Snapshot snapshot) {
    return snapshot.builders().stream().flatMap(builder -> builder.getAllEdges().stream()).toList();
  }
  private static DAGBuilder firstBuilder(Snapshot snapshot) {
    if (snapshot.builders().isEmpty()) throw new IllegalStateException("No graph available");
    return snapshot.builders().get(0);
  }
  private static Node firstStart(Snapshot snapshot) {
    if (snapshot.startNodes().isEmpty() || snapshot.startNodes().get(0) == null) throw new IllegalStateException("No graph root available");
    return snapshot.startNodes().get(0);
  }
  private static String combinedSource(Snapshot snapshot) {
    return String.join("\n", snapshot.sources().stream().map(SourceFile::source).toList());
  }
  private static int integer(String value, int fallback) {
    try { return value == null ? fallback : Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
  }
  private static long longValue(String value, long fallback) {
    try { return value == null ? fallback : Long.parseLong(value); } catch (NumberFormatException ignored) { return fallback; }
  }
  private static PathEnumerator.Options pathOptions(spark.Request request) {
    return new PathEnumerator.Options(integer(request.queryParams("pathLimit"), PathEnumerator.DEFAULT_PATH_LIMIT),
        integer(request.queryParams("pathLengthLimit"), PathEnumerator.DEFAULT_PATH_LENGTH_LIMIT),
        longValue(request.queryParams("stateLimit"), PathEnumerator.DEFAULT_STATE_LIMIT),
        longValue(request.queryParams("timeLimitMs"), PathEnumerator.DEFAULT_TIME_LIMIT_MS), () -> false);
  }
  private static List<Node> componentRoots(Snapshot snapshot) {
    return allNodes(snapshot).stream().filter(node -> semanticType(node).equals("CLASS_ENTRY")).toList();
  }
  private static String semanticType(Node node) {
    String[] parts = node.type.split("<br/?>"); return parts.length > 1 ? parts[1] : node.type;
  }
  private static String json(spark.Response response, Object value) { response.type("application/json"); return GSON.toJson(value); }

  private static final class SourceSubmission {
    List<SourceFile> files;
    Integer workers;
    Boolean measureSerialBaseline;
  }
  private static final class RuntimeRequest {
    String mainClass;
    List<String> arguments;
    Long timeoutMillis;
    String testCase;
  }
  private record ErrorResponse(String error) {}
  private record CoverageView(CoverageAnalyzer.CoverageResult staticPredicted,
                              CoverageAnalyzer.CoverageResult runtimeObserved,
                              RuntimeCoverageSession.Summary session) {}
  private record PathSummary(int pathsGenerated, int pathLimit, int pathLengthLimit,
                             long traversalStateLimit, long timeLimitMs, String status, String stopReason,
                             boolean truncated, double generationTimeMs, long traversalStates,
                             int maximumPathLength, int componentsStarted) {}
}
