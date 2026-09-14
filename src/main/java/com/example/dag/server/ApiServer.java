package com.example.dag.server;

import com.example.dag.DAGBuilder;
import com.example.dag.coverage.CoverageAnalyzer;
import com.example.dag.coverage.CoverageTracker;
import com.example.dag.coverage.CoverageObligationEngine;
import com.example.dag.coverage.ControlRequirementExtractor;
import com.example.dag.coverage.RuntimeCoverageSession;
import com.example.dag.graph.Edge;
import com.example.dag.graph.Node;
import com.example.dag.runtime.RuntimeExecutionService;
import com.example.dag.server.GraphProjectionService.Query;
import com.example.dag.server.GraphWorkspace.Snapshot;
import com.example.dag.server.GraphWorkspace.SourceFile;
import com.example.dag.testcase.GeneratedTestCase;
import com.example.dag.testcase.DeterministicTestGenerationService;
import com.example.dag.testcase.EvoSuiteCandidateProvider;
import com.example.dag.testcase.RuntimeTestSuiteMinimizer;
import com.example.dag.testcase.PathEnumerator;
import com.example.dag.testcase.TestCaseGenerator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static spark.Spark.*;

/** HTTP boundary for bounded graph browsing and legacy opt-in analysis actions. */
public final class ApiServer {
  private static final Gson GSON = new Gson();
  private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final GraphProjectionService PROJECTIONS = new GraphProjectionService();
  private static final RuntimeExecutionService EXECUTION = new RuntimeExecutionService();
  private static final CoverageObligationEngine OBLIGATIONS = new CoverageObligationEngine();
  private static final ControlRequirementExtractor REQUIREMENTS = new ControlRequirementExtractor();
  private static final DeterministicTestGenerationService GENERATION = new DeterministicTestGenerationService();
  private static final EvoSuiteCandidateProvider EVOSUITE = new EvoSuiteCandidateProvider();
  private static final RuntimeTestSuiteMinimizer MINIMIZER = new RuntimeTestSuiteMinimizer();
  private static CoverageTracker staticCoverageTracker;
  private static RuntimeCoverageSession runtimeSession;
  private static CoverageObligationEngine.Catalog obligationCatalog;
  private static Path activeResultDirectory;

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
      System.out.printf("[COSDG] BUILD started: files=%d workers=%d serialBaseline=%s%n",
          submission.files.size(), workers, baseline);
      Snapshot snapshot = workspace.submit(submission.files, workers, baseline, true);
      System.out.printf("[COSDG] BUILD complete: statements=%d nodes=%d edges=%d parse=%.1fms graph=%.1fms%n",
          snapshot.statistics().statements(), snapshot.statistics().nodes(), snapshot.statistics().edges(),
          snapshot.statistics().measured().parseMs(), snapshot.statistics().measured().graphMs());
      staticCoverageTracker = null;
      runtimeSession = null;
      GENERATION.clearRecords();
      obligationCatalog = OBLIGATIONS.build(snapshot,
          CoverageAnalyzer.CoverageMarking.staticPrediction(java.util.Set.of(), java.util.Set.of()), null);
      activeResultDirectory = createResultArchive(snapshot);
      System.out.printf("[COSDG] RESULT archive created: %s%n", activeResultDirectory);
      return json(response, snapshot.statistics());
    });

    get("/api/stats", (request, response) -> json(response, workspace.current().statistics()));
    get("/api/experiment", (request, response) -> json(response,
        new ExperimentLocation(activeResultDirectory == null ? null : activeResultDirectory.toString())));
    get("/api/classes", (request, response) -> json(response, PROJECTIONS.classes(workspace.current().graph())));
    get("/api/methods", (request, response) -> json(response, PROJECTIONS.methods(
        workspace.current().graph(), request.queryParams("file"), request.queryParams("classId"))));
    get("/api/graph", (request, response) -> {
      Snapshot snapshot=workspace.current();
      Set<String> requestedTests = csvSet(request.queryParams("testCaseIds"));
      var selectedRecords=currentTestRecords(snapshot).stream().filter(t->requestedTests.contains(t.testCaseId())).toList();
      var projection = requestedTests.isEmpty()?PROJECTIONS.project(snapshot.graph(),
          new Query(request.queryParams("file"), request.queryParams("classId"), request.queryParams("methodId"),
              request.queryParams("nodeId"), request.queryParams("nodeType"), request.queryParams("edgeType"),
              integer(request.queryParams("limit"), 250), integer(request.queryParams("edgeLimit"), 750),
              integer(request.queryParams("depth"), 1)))
          :PROJECTIONS.projectEvidence(snapshot.graph(),selectedRecords.stream().flatMap(t->t.runtimeNodes().stream()).collect(java.util.stream.Collectors.toSet()),
              selectedRecords.stream().flatMap(t->t.runtimeEdges().stream()).collect(java.util.stream.Collectors.toSet()),
              integer(request.queryParams("limit"),250),integer(request.queryParams("edgeLimit"),750),integer(request.queryParams("depth"),1));
      JsonObject view = GSON.toJsonTree(projection).getAsJsonObject();
      if (runtimeSession != null) {
        var marking = requestedTests.isEmpty() ? runtimeSession.marking() : runtimeSession.markingForTests(requestedTests);
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
      Snapshot snapshot=workspace.current();JsonObject document=GSON.fromJson(snapshot.canonicalJson(),JsonObject.class);
      document.add("research",GSON.toJsonTree(researchMetadata(snapshot)));
      return GSON.toJson(document);
    });
    post("/api/paths", (request, response) -> {
      PathEnumerator.EnumerationResult result = PathEnumerator.enumerate(componentRoots(workspace.current()), pathOptions(request));
      archiveJson("path-enumeration.json", result);
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
      Set<String> selectedTests=csvSet(request.queryParams("testCaseIds"));
      RuntimeCoverageSession.Summary observed = runtimeSession == null ? null
          : runtimeSession.summarize(allNodes(snapshot), allEdges(snapshot));
      CoverageAnalyzer.CoverageResult runtime=observed==null||observed.testCount()==0?null:
          selectedTests.isEmpty()?observed.coverage():CoverageAnalyzer.compute(runtimeSession.markingForTests(selectedTests),allNodes(snapshot),allEdges(snapshot));
      return json(response, new CoverageView(predicted,runtime,selectedTests.isEmpty()?observed:null));
    });
    get("/api/obligations", (request, response) -> {
      return json(response, currentObligations(workspace.current()));
    });
    get("/api/obligations/:id/requirements", (request, response) -> {
      Snapshot snapshot=workspace.current();
      CoverageObligationEngine.Catalog catalog=currentObligations(snapshot);
      var obligation=catalog.obligations().stream().filter(o->o.id().equals(request.params(":id"))).findFirst().orElse(null);
      if(obligation==null){response.status(404);return json(response,new ErrorResponse("Obligation not found"));}
      return json(response,REQUIREMENTS.extract(snapshot,obligation));
    });
    post("/api/obligations/:id/generate", (request,response)->{
      Snapshot snapshot=workspace.current();CoverageObligationEngine.Catalog catalog=currentObligations(snapshot);
      var obligation=catalog.obligations().stream().filter(o->o.id().equals(request.params(":id"))).findFirst().orElse(null);
      if(obligation==null){response.status(404);return json(response,new ErrorResponse("Obligation not found"));}
      System.out.printf("[COSDG] GENERATE started: obligation=%s criterion=%s%n", obligation.id(), obligation.criterion());
      GenerationRequest body=request.body()==null||request.body().isBlank()?null:GSON.fromJson(request.body(),GenerationRequest.class);
      if(runtimeSession==null)runtimeSession=new RuntimeCoverageSession(allNodes(snapshot).size(),allEdges(snapshot).size());
      var options=body==null?DeterministicTestGenerationService.Options.defaults():new DeterministicTestGenerationService.Options(
          body.maxAttempts==null?24:body.maxAttempts,body.generationTimeLimitMs==null?15_000:body.generationTimeLimitMs,
          body.executionTimeoutMs==null?5_000:body.executionTimeoutMs);
      var generated=GENERATION.generate(snapshot,obligation,REQUIREMENTS.extract(snapshot,obligation),runtimeSession,options);
      System.out.printf("[COSDG] GENERATE complete: obligation=%s status=%s attempts=%d method=%s targetMarked=%s%n",
          obligation.id(), generated.status(), generated.attempts(),
          generated.retained()==null?"none":generated.retained().generationMethod(),
          generated.retained()!=null&&generated.retained().targetMarked());
      updateResultArchive(snapshot);
      return json(response,generated);
    });
    exception(Exception.class,(failure,request,response)->{
      System.err.printf("[COSDG] API failed: method=%s path=%s error=%s%n",request.requestMethod(),request.pathInfo(),failure);
      failure.printStackTrace(System.err);
      response.status(500);response.type("application/json");
      response.body(GSON.toJson(new ErrorResponse("Server operation failed: "+safeError(failure))));
    });
    post("/api/tests/generate", (request,response)->{
      Snapshot snapshot=workspace.current();
      if(runtimeSession==null)runtimeSession=new RuntimeCoverageSession(allNodes(snapshot).size(),allEdges(snapshot).size());
      GenerationRequest body=request.body()==null||request.body().isBlank()?null:GSON.fromJson(request.body(),GenerationRequest.class);
      int maximum=body==null||body.maxObligations==null?100:Math.max(1,Math.min(body.maxObligations,500));
      var options=body==null?DeterministicTestGenerationService.Options.defaults():new DeterministicTestGenerationService.Options(
          body.maxAttempts==null?24:body.maxAttempts,body.generationTimeLimitMs==null?15_000:body.generationTimeLimitMs,
          body.executionTimeoutMs==null?5_000:body.executionTimeoutMs);
      boolean useEvoSuite=body==null||body.useEvoSuite==null||body.useEvoSuite;
      EvoSuiteCandidateProvider.Result evoSuiteResult;
      if(useEvoSuite){
        var evoOptions=new EvoSuiteCandidateProvider.Options(
            body==null||body.evoSuiteBudgetSeconds==null?3:body.evoSuiteBudgetSeconds,
            body==null||body.evoSuiteMaxClasses==null?10:body.evoSuiteMaxClasses,
            body==null||body.evoSuiteMaxTests==null?100:body.evoSuiteMaxTests,
            body==null||body.executionTimeoutMs==null?5_000:body.executionTimeoutMs);
        System.out.printf("[COSDG] EVOSUITE phase started: available=%s budget=%ds maxClasses=%d%n",
            EVOSUITE.available(),evoOptions.searchBudgetSeconds(),evoOptions.maxClasses());
        evoSuiteResult=EVOSUITE.generate(snapshot,runtimeSession,evoOptions);
        evoSuiteResult.records().forEach(GENERATION::registerExternal);
        System.out.printf("[COSDG] EVOSUITE phase complete: status=%s generated=%d retained=%d%n",
            evoSuiteResult.status(),evoSuiteResult.generatedTests(),evoSuiteResult.retainedTests());
      }else{
        evoSuiteResult=new EvoSuiteCandidateProvider.Result(EVOSUITE.available(),"DISABLED",0,0,0,0,
            "EvoSuite disabled for this request",List.of());
      }
      int processed=0,retained=evoSuiteResult.retainedTests();List<DeterministicTestGenerationService.GenerationResult> results=new ArrayList<>();
      Set<String> attemptedObligations=new LinkedHashSet<>();
      System.out.printf("[COSDG] SUITE generation started: maxObligations=%d%n",maximum);
      while(processed<maximum){
        CoverageObligationEngine.Catalog catalog=currentObligations(snapshot);
        var obligation=catalog.obligations().stream().filter(o->o.status()==CoverageObligationEngine.Status.UNCOVERED)
            .filter(o->!attemptedObligations.contains(o.id()))
            .filter(o->GENERATION.records(o.id()).stream().noneMatch(t->t.status()==DeterministicTestGenerationService.GenerationStatus.RETAINED))
            .findFirst().orElse(null);
        if(obligation==null)break;
        attemptedObligations.add(obligation.id());
        var generated=GENERATION.generate(snapshot,obligation,REQUIREMENTS.extract(snapshot,obligation),runtimeSession,options);
        results.add(generated);processed++;if(generated.retained()!=null)retained++;
      }
      var responseBody=new SuiteGenerationResult(CoverageObligationEngine.fingerprint(snapshot),processed,retained,
          GENERATION.records().size(),currentObligations(snapshot).summary(),minimizedSuite(snapshot),evoSuiteResult,results);
      System.out.printf("[COSDG] SUITE generation complete: processed=%d retained=%d candidates=%d%n",processed,retained,GENERATION.records().size());
      updateResultArchive(snapshot);
      return json(response,responseBody);
    });
    get("/api/tests",(request,response)->json(response,currentTestRecords(workspace.current())));
    get("/api/evosuite/status",(request,response)->json(response,
        new EvoSuiteStatus(EVOSUITE.available(),"1.2.0",EVOSUITE.available()?"READY":"JARs missing under tools/evosuite")));
    get("/api/test-suite/export",(request,response)->{
      Snapshot snapshot=workspace.current();response.type("application/json");
      response.header("Content-Disposition","attachment; filename=validated-test-suite.json");
      return GSON.toJson(new TestSuiteDocument("COSDG_VALIDATED_TEST_SUITE_V1",CoverageObligationEngine.fingerprint(snapshot),
          runtimeSession==null?null:runtimeSession.id(),researchMetadata(snapshot),currentTestRecords(snapshot),minimizedSuite(snapshot)));
    });
    get("/api/tests/:id",(request,response)->{
      Snapshot snapshot=workspace.current();String id=request.params(":id");
      var record=currentTestRecords(snapshot).stream().filter(t->t.testCaseId().equals(id)).findFirst().orElse(null);
      if(record==null){response.status(404);return json(response,new ErrorResponse("Test case not found"));}
      var marking=runtimeSession!=null&&runtimeSession.run(id)!=null?runtimeSession.markingForTests(Set.of(id)):markingForRecords(List.of(record));
      var coverage=CoverageAnalyzer.compute(marking,allNodes(snapshot),allEdges(snapshot));
      return json(response,new TestCaseView(record,coverage,canonicalNodeIds(snapshot,record.runtimeNodes()),canonicalEdgeIds(snapshot,record.runtimeEdges())));
    });
    get("/api/suite/minimized",(request,response)->json(response,minimizedSuite(workspace.current())));
    get("/api/suite/selected",(request,response)->{var result=minimizedSuite(workspace.current());var selected=new java.util.LinkedHashSet<>(result.selectedTestIds());return json(response,GENERATION.records().stream().filter(t->selected.contains(t.testCaseId())).toList());});
    post("/api/coverage/session", (request, response) -> {
      Snapshot snapshot = workspace.current();
      runtimeSession = new RuntimeCoverageSession(allNodes(snapshot).size(), allEdges(snapshot).size());
      updateResultArchive(snapshot);
      return json(response, runtimeSession.summarize(allNodes(snapshot), allEdges(snapshot)));
    });
    post("/api/coverage/session/reset", (request, response) -> {
      Snapshot snapshot = workspace.current();
      runtimeSession = new RuntimeCoverageSession(allNodes(snapshot).size(), allEdges(snapshot).size());
      updateResultArchive(snapshot);
      return json(response, runtimeSession.summarize(allNodes(snapshot), allEdges(snapshot)));
    });
    post("/api/execute", (request, response) -> {
      RuntimeRequest body = GSON.fromJson(request.body(), RuntimeRequest.class);
      if (body == null) { response.status(400); return json(response, new ErrorResponse("Execution request is required")); }
      System.out.printf("[COSDG] EXECUTE started: test=%s main=%s timeout=%dms%n",
          body.testCase, body.mainClass, body.timeoutMillis == null ? 5_000 : body.timeoutMillis);
      RuntimeExecutionService.Result result = EXECUTION.execute(workspace.current(), new RuntimeExecutionService.Request(
          body.mainClass, body.arguments, body.timeoutMillis == null ? 5_000 : body.timeoutMillis, body.testCase));
      System.out.printf("[COSDG] EXECUTE complete: test=%s compile=%s status=%s execution=%.1fms nodes=%d edges=%d%n",
          body.testCase, result.compilationSuccess(), result.status(), result.executionMs(),
          result.visitedNodes().size(), result.visitedEdges().size());
      if (result.compilationSuccess()) {
        Snapshot snapshot = workspace.current();
        if (runtimeSession == null) runtimeSession = new RuntimeCoverageSession(allNodes(snapshot).size(), allEdges(snapshot).size());
        runtimeSession.add(body.testCase, result);
      }
      archiveJson("latest-execution.json", result);
      updateResultArchive(workspace.current());
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
  private static CoverageObligationEngine.Catalog currentObligations(Snapshot snapshot){
    RuntimeCoverageSession.Summary observed=runtimeSession==null?null:runtimeSession.summarize(allNodes(snapshot),allEdges(snapshot));
    CoverageAnalyzer.CoverageMarking marking=observed!=null&&observed.testCount()>0?runtimeSession.marking()
        :staticCoverageTracker==null?CoverageAnalyzer.CoverageMarking.staticPrediction(java.util.Set.of(),java.util.Set.of()):staticCoverageTracker.toMarking();
    obligationCatalog=OBLIGATIONS.refresh(obligationCatalog,snapshot,marking,observed!=null&&observed.testCount()>0?observed:null);
    return obligationCatalog;
  }
  private static RuntimeTestSuiteMinimizer.Result minimizedSuite(Snapshot snapshot){CoverageObligationEngine.Catalog catalog=currentObligations(snapshot);java.util.Set<String> obligations=catalog.obligations().stream().filter(o->o.status()!=CoverageObligationEngine.Status.STALE&&o.status()!=CoverageObligationEngine.Status.UNSUPPORTED).map(CoverageObligationEngine.Obligation::id).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));return MINIMIZER.minimize(CoverageObligationEngine.fingerprint(snapshot),runtimeSession==null?null:runtimeSession.id(),obligations,GENERATION.records());}

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
  private static String safeError(Throwable failure){String message=failure==null?null:failure.getMessage();return message==null||message.isBlank()?failure.getClass().getSimpleName():message;}

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
  private static final class GenerationRequest {Integer maxAttempts;Long generationTimeLimitMs;Long executionTimeoutMs;Integer maxObligations;
    Boolean useEvoSuite;Integer evoSuiteBudgetSeconds;Integer evoSuiteMaxClasses;Integer evoSuiteMaxTests;}
  private record SuiteGenerationResult(String graphId,int obligationsProcessed,int retained,int candidateRecords,
      CoverageObligationEngine.Summary obligationSummary,RuntimeTestSuiteMinimizer.Result minimized,
      EvoSuiteCandidateProvider.Result evoSuite,List<DeterministicTestGenerationService.GenerationResult> results){}
  private record EvoSuiteStatus(boolean available,String version,String status){}
  private record TestCaseView(DeterministicTestGenerationService.TestCaseRecord test,
      CoverageAnalyzer.CoverageResult coverage,List<String> visitedNodeIds,List<String> visitedEdgeIds){}
  private record TestSuiteDocument(String format,String graphId,String sessionId,
      GraphWorkspace.ResearchMetadata research,
      List<DeterministicTestGenerationService.TestCaseRecord> tests,RuntimeTestSuiteMinimizer.Result minimized){}
  private record ErrorResponse(String error) {}
  private record ExperimentLocation(String directory) {}
  private record CoverageView(CoverageAnalyzer.CoverageResult staticPredicted,
                              CoverageAnalyzer.CoverageResult runtimeObserved,
                              RuntimeCoverageSession.Summary session) {}
  private record PathSummary(int pathsGenerated, int pathLimit, int pathLengthLimit,
                             long traversalStateLimit, long timeLimitMs, String status, String stopReason,
                             boolean truncated, double generationTimeMs, long traversalStates,
                             int maximumPathLength, int componentsStarted) {}

  private static List<DeterministicTestGenerationService.TestCaseRecord> currentTestRecords(Snapshot snapshot){
    String graphId=CoverageObligationEngine.fingerprint(snapshot);
    return GENERATION.records().stream().filter(record->graphId.equals(record.graphId())).toList();
  }
  private static GraphWorkspace.ResearchMetadata researchMetadata(Snapshot snapshot){return GraphWorkspace.researchMetadata(snapshot,CoverageObligationEngine.fingerprint(snapshot));}
  private static CoverageAnalyzer.CoverageMarking markingForRecords(List<DeterministicTestGenerationService.TestCaseRecord> records){
    LinkedHashSet<String> nodes=new LinkedHashSet<>(),edges=new LinkedHashSet<>(),types=new LinkedHashSet<>();
    records.forEach(record->{nodes.addAll(record.runtimeNodes());edges.addAll(record.runtimeEdges());types.addAll(record.runtimeExceptionTypes());});
    return new CoverageAnalyzer.CoverageMarking(Set.copyOf(nodes),Set.copyOf(edges),Set.of(),java.util.Map.of(),true,
        Set.of("statement","method","polymorphic","throw","catch","exceptionType","exceptionFlow"));
  }
  private static List<String> canonicalNodeIds(Snapshot snapshot,Set<String> internal){return snapshot.graph().nodes().stream().filter(n->internal.contains(n.internalId())).map(n->n.id()).toList();}
  private static List<String> canonicalEdgeIds(Snapshot snapshot,Set<String> internal){return snapshot.graph().edges().stream().filter(e->internal.contains(e.internalId())).map(e->e.id()).toList();}
  private static Set<String> csvSet(String value){if(value==null||value.isBlank())return Set.of();LinkedHashSet<String> out=new LinkedHashSet<>();for(String part:value.split(","))if(!part.isBlank())out.add(part.trim());return Set.copyOf(out);}

  private static synchronized Path createResultArchive(Snapshot snapshot) {
    try {
      Path root = Path.of("results").toAbsolutePath().normalize();
      Files.createDirectories(root);
      int next = 1;
      try (var entries = Files.list(root)) {
        next = entries.filter(Files::isDirectory).map(path -> path.getFileName().toString())
            .filter(name -> name.matches("\\d+"))
            .mapToInt(Integer::parseInt).max().orElse(0) + 1;
      }
      Path directory = root.resolve(String.format("%02d", next));
      Files.createDirectories(directory.resolve("sources"));
      int index = 0;
      for (SourceFile source : snapshot.sources()) {
        String name = String.format("%02d-%s", ++index, source.name());
        atomicWrite(directory.resolve("sources").resolve(name), source.source());
      }
      activeResultDirectory = directory;
      updateResultArchive(snapshot);
      return directory;
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot create experiment result archive", failure);
    }
  }

  private static synchronized void updateResultArchive(Snapshot snapshot) {
    if (activeResultDirectory == null || snapshot == null) return;
    JsonObject graph = GSON.fromJson(snapshot.canonicalJson(), JsonObject.class);
    graph.add("research", GSON.toJsonTree(researchMetadata(snapshot)));
    archiveJson("canonical-graph.json", graph);
    archiveJson("statistics.json", snapshot.statistics());
    archiveJson("research-metadata.json", researchMetadata(snapshot));
    archiveJson("coverage-obligations.json", currentObligations(snapshot));
    CoverageAnalyzer.CoverageMarking staticMarking = staticCoverageTracker == null
        ? CoverageAnalyzer.CoverageMarking.staticPrediction(Set.of(), Set.of()) : staticCoverageTracker.toMarking();
    var predicted = CoverageAnalyzer.compute(staticMarking, allNodes(snapshot), allEdges(snapshot));
    var observed = runtimeSession == null ? null : runtimeSession.summarize(allNodes(snapshot), allEdges(snapshot));
    archiveJson("coverage.json", new CoverageView(predicted,
        observed == null || observed.testCount() == 0 ? null : observed.coverage(), observed));
    var suite = new TestSuiteDocument("COSDG_VALIDATED_TEST_SUITE_V1", CoverageObligationEngine.fingerprint(snapshot),
        runtimeSession == null ? null : runtimeSession.id(), researchMetadata(snapshot),
        currentTestRecords(snapshot), minimizedSuite(snapshot));
    archiveJson("validated-test-suite.json", suite);
    try { atomicWrite(activeResultDirectory.resolve("result-summary.md"), resultSummary(snapshot, suite)); }
    catch (Exception failure) { throw new IllegalStateException("Cannot archive result-summary.md", failure); }
  }

  private static synchronized void archiveJson(String name, Object value) {
    if (activeResultDirectory == null) return;
    try { atomicWrite(activeResultDirectory.resolve(name), PRETTY_GSON.toJson(value)); }
    catch (Exception failure) { throw new IllegalStateException("Cannot archive " + name, failure); }
  }

  private static void atomicWrite(Path target, String content) throws Exception {
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
    Files.writeString(temporary, content, StandardCharsets.UTF_8);
    try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
    catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static String resultSummary(Snapshot snapshot, TestSuiteDocument suite) {
    var s = snapshot.statistics(); var m = s.measured(); var minimized = suite.minimized();
    return "# COSDG/ACSD Experiment Result\n\n"
        + "- Program: `" + snapshot.program() + "`\n"
        + "- Source files: " + s.sourceFiles() + "\n"
        + "- Statements: " + s.statements() + "\n"
        + "- Classes: " + s.classes() + "\n"
        + "- Methods: " + s.methods() + "\n"
        + "- Nodes: " + s.nodes() + "\n"
        + "- Edges: " + s.edges() + "\n"
        + "- Syntax errors: " + s.syntaxErrors() + "\n"
        + "- Parse time: " + m.parseMs() + " ms\n"
        + "- Graph time: " + m.graphMs() + " ms\n"
        + "- Total construction time: " + m.totalMs() + " ms\n"
        + "- Heap after parse: " + m.heapAfterParseBytes() + " bytes\n"
        + "- Heap after graph: " + m.heapAfterGraphBytes() + " bytes\n"
        + "- Graph heap estimate: " + m.graphHeapBytes() + " bytes\n"
        + "- Raw signed heap delta: " + m.graphHeapRawDeltaBytes() + " bytes\n"
        + "- JSON size: " + m.jsonBytes() + " bytes\n"
        + "- Runtime-validated candidates: " + suite.tests().stream().filter(t -> t.status() == DeterministicTestGenerationService.GenerationStatus.RETAINED).count() + "\n"
        + "- Approximately minimized tests: " + minimized.selectedTestCount() + "\n"
        + "- Covered obligations after minimization: " + minimized.coveredAfterMinimization() + "\n"
        + "- Remaining uncovered obligations: " + minimized.uncoveredCount() + "\n\n"
        + "> Heap values are approximate JVM observations. The minimized suite uses deterministic weighted-greedy set cover.\n";
  }
}
