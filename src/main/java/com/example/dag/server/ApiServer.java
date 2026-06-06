package com.example.dag.server;

import static spark.Spark.*;

import com.google.gson.*;
import com.example.dag.testcase.*;
import com.example.dag.analysis.*;
import com.example.dag.coverage.*;
import com.example.dag.graph.*;

import java.util.*;
import java.nio.file.*;

public class ApiServer {

    private static com.example.dag.coverage.CoverageTracker systemCoverageTracker = null;

    public static void start(
            String sourceCode,
            String dagText,
            Node startNode,
            List<Node> allNodes,
            List<Edge> allEdges)
            throws Exception {

        port(8080);
        Gson gson = new Gson();

        before((req, res) -> {
            res.header("Access-Control-Allow-Origin", "*");
            res.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            res.header("Access-Control-Allow-Headers", "Content-Type");
        });

        options("/*", (req, res) -> {
            res.status(200);
            return "OK";
        });

        get("/", (req, res) -> {
            res.type("text/html");
            return new String(Files.readAllBytes(Paths.get("visual_graph.html")));
        });

        post("/generate-tests", (req, res) -> {
            System.out.println("[ACK] Parsing source code...");
            System.out.println("[ACK] Building DAG...");
            System.out.println("[ACK] Enumerating paths...");
            System.out.println("[ACK] Calling Ollama...");

            String result = TestCaseGenerator.generate(sourceCode, dagText, startNode);
            saveToFile("testcases.json", result);

            systemCoverageTracker = new com.example.dag.coverage.CoverageTracker(allNodes.size(), allEdges.size());
            System.out.println("[ACK] Stateful Coverage Tracker Initialized: Bounds (" + allNodes.size() + " Nodes, " + allEdges.size() + " Edges)");

            res.type("application/json");
            return result;
        });

        post("/get-all-paths", (req, res) -> {
            System.out.println("[ACK] Enumerating paths...");
            System.out.println("[ACK] Calling Ollama...");

            String result = TestCaseGenerator.generateAllPaths(sourceCode, dagText, startNode);
            saveToFile("all_paths.json", result);

            systemCoverageTracker = new com.example.dag.coverage.CoverageTracker(allNodes.size(), allEdges.size());
            System.out.println("[ACK] Stateful Coverage Tracker Initialized: Bounds (" + allNodes.size() + " Nodes, " + allEdges.size() + " Edges)");

            System.out.println("[ACK] Selecting minimum testcase suite...");
            res.type("application/json");
            return result;
        });

        // ====================================================
        // TRACK COVERAGE — COMPLETE METRIC INTEGRATION
        // ====================================================
        post("/track-coverage", (req, res) -> {
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            
            if (systemCoverageTracker == null) {
                systemCoverageTracker = new com.example.dag.coverage.CoverageTracker(allNodes.size(), allEdges.size());
            }

            List<String> currentPathNodes = new ArrayList<>();
            List<String> currentPathEdges = new ArrayList<>();

            if (body.has("dag_path")) {
                body.getAsJsonArray("dag_path").forEach(e -> currentPathNodes.add(e.getAsString()));
            }

            if (body.has("dag_edges")) {
                body.getAsJsonArray("dag_edges").forEach(e -> currentPathEdges.add(e.getAsString()));
            }

            GeneratedTestCase tcShell = new GeneratedTestCase();
            tcShell.id = body.has("id") ? body.get("id").getAsString() : "TC_CLICK";
            tcShell.dag_path = currentPathNodes;
            tcShell.dag_edges = currentPathEdges;

            systemCoverageTracker.track(tcShell);

            // Compute precise coverage bounds using structural lists
            CoverageAnalyzer.CoverageResult metrics = CoverageAnalyzer.compute(
                systemCoverageTracker.getCoveredNodes(),
                systemCoverageTracker.getCoveredEdges(),
                allNodes,
                allEdges
            );

            JsonObject responseObj = new JsonObject();
            JsonObject covObj = new JsonObject();
            
            // Map structured metrics out to web response boundaries
            covObj.addProperty("covmd", Math.round(metrics.covmd * 100.0) / 100.0);
            covObj.addProperty("covpc", Math.round(metrics.covpc * 100.0) / 100.0);
            covObj.addProperty("covic", Math.round(metrics.covic * 100.0) / 100.0);
            covObj.addProperty("covmc", Math.round(metrics.covmc * 100.0) / 100.0);
            
            covObj.addProperty("covet", Math.round(metrics.covet * 100.0) / 100.0);
            covObj.addProperty("covec", Math.round(metrics.covec * 100.0) / 100.0);
            covObj.addProperty("covety", Math.round(metrics.covety * 100.0) / 100.0);
            covObj.addProperty("covef", Math.round(metrics.covef * 100.0) / 100.0);
            
            covObj.addProperty("overall", Math.round(metrics.overall * 100.0) / 100.0);
            
            responseObj.add("coverage", covObj);

            JsonArray nodesArr = new JsonArray();
            systemCoverageTracker.getCoveredNodes().forEach(nodesArr::add);
            responseObj.add("visited_nodes", nodesArr);

            JsonArray edgesArr = new JsonArray();
            systemCoverageTracker.getCoveredEdges().forEach(edgesArr::add);
            responseObj.add("visited_edges", edgesArr);

            res.type("application/json");
            return gson.toJson(responseObj);
        });

        get("/load-testcases", (req, res) -> {
            String filename = req.queryParams("file");
            if (filename == null || filename.isBlank()) {
                filename = "testcases.json";
            }
            filename = Paths.get(filename).getFileName().toString();

            res.type("application/json");
            Path p = Paths.get(filename);
            if (!Files.exists(p)) {
                return "{\"error\":\"File not found: " + filename + "\"}";
            }
            return Files.readString(p);
        });

        System.out.println("\nAPI SERVER RUNNING:\nhttp://localhost:8080\nOpen http://localhost:8080 in browser");
    }

    private static void saveToFile(String filename, String content) {
        try {
            Files.writeString(Paths.get(filename), content);
        } catch (Exception e) {
            System.err.println("Could not save " + filename + ": " + e.getMessage());
        }
    }
}