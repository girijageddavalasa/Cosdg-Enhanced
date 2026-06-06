package com.example.dag.testcase;

import com.example.dag.graph.Node;
import com.example.dag.llm.OllamaClient;
import com.example.dag.testcase.MinCoverageSelector;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.util.*;

/**
 * Generates test cases in two phases:
 *
 * Phase 1 (deterministic, no LLM):
 * PathEnumerator walks the DAG and produces every distinct
 * root-to-leaf path with exact node IDs and edge labels.
 *
 * Phase 2 (Ollama, Master Pool Injection Matrix):
 * Fires exactly ONE ultra-fast batch request to generate a pool of 10 highly diversified
 * variable states. Java then binds this master data pool across the paths instantly.
 */
public class TestCaseGenerator {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    public static String generate(
            String sourceCode,
            String dagText,
            Node startNode)
            throws Exception {

        List<PathEnumerator.EnumeratedPath> paths =
                PathEnumerator.enumerate(startNode);

        List<GeneratedTestCase> allCases = buildAllTestCases(paths, sourceCode, dagText, "TC");

        List<GeneratedTestCase> normal    = new ArrayList<>();
        List<GeneratedTestCase> exception = new ArrayList<>();
        List<GeneratedTestCase> edge      = new ArrayList<>();

        for (GeneratedTestCase tc : allCases) {
            boolean isExceptionPath = tc.dag_path.stream()
                    .anyMatch(n -> n.startsWith("vx2."));

            if (isExceptionPath) {
                exception.add(tc);
            } else if (normal.isEmpty()) {
                normal.add(tc);
            } else {
                edge.add(tc);
            }
        }

        JsonObject root = new JsonObject();
        root.add("normal_test_cases",    GSON.toJsonTree(normal));
        root.add("exception_test_cases", GSON.toJsonTree(exception));
        root.add("edge_cases",           GSON.toJsonTree(edge));

        return GSON.toJson(root);
    }

    // ====================================================================
    // GENERATE ALL PATHS WITH FIX FOR EXPLICIT EDGE MATRIX POPULATION
    // ====================================================================
    public static String generateAllPaths(
            String sourceCode,
            String dagText,
            Node startNode)
            throws Exception {

        List<PathEnumerator.EnumeratedPath> paths =
                PathEnumerator.enumerate(startNode);

        JsonArray allPathsArr = new JsonArray();
        int pathIndex = 1;
        for (PathEnumerator.EnumeratedPath path : paths) {
            JsonObject pObj = new JsonObject();
            pObj.addProperty("path_id", "P" + pathIndex++);
            pObj.addProperty("description", describePathDeterministically(path));
            pObj.add("nodes", GSON.toJsonTree(path.nodeIds));
            pObj.add("edges", GSON.toJsonTree(path.edgeLabels));
            allPathsArr.add(pObj);
        }

        List<GeneratedTestCase> allCases = buildAllTestCases(paths, sourceCode, dagText, "MTC");
        List<MinCoverageSelector.ScoredTestCase> scored = new ArrayList<>();

        for (GeneratedTestCase tc : allCases) {
            MinCoverageSelector.ScoredTestCase scoredTc =
                    new MinCoverageSelector.ScoredTestCase(
                            tc,
                            new HashSet<>(tc.dag_edges));
            scored.add(scoredTc);
        }

        List<MinCoverageSelector.ScoredTestCase> minSet =
                MinCoverageSelector.selectMinimum(scored);

        minSet.sort((a, b) -> Integer.compare(
                b.coveredEdges.size(),
                a.coveredEdges.size()));

        Set<String> unionNodes = new LinkedHashSet<>();
        Set<String> unionEdges = new LinkedHashSet<>();
        JsonArray minSuiteArr = new JsonArray();

        for (MinCoverageSelector.ScoredTestCase s : minSet) {
            for (String node : s.testCase.dag_path) {
                unionNodes.add(node.replace('_', '.').trim());
            }
            // CRITICAL SECURE PERSISTENCE FIX: Pulls and populates structural edge arrays safely
            unionEdges.addAll(s.testCase.dag_edges);

            JsonObject obj = GSON.toJsonTree(s.testCase).getAsJsonObject();
            JsonArray covers = new JsonArray();
            covers.add(s.testCase.id.replace("MTC", "P"));
            obj.add("covers_paths", covers);
            minSuiteArr.add(obj);
        }

        JsonObject root = new JsonObject();
        root.addProperty("total_paths", paths.size());
        root.addProperty("minimum_test_cases_for_max_coverage", minSet.size());
        root.add("all_paths", allPathsArr);
        root.add("minimum_test_suite", minSuiteArr);

        JsonObject unionObj = new JsonObject();
        unionObj.add("nodes", GSON.toJsonTree(new ArrayList<>(unionNodes)));
        unionObj.add("edges", GSON.toJsonTree(new ArrayList<>(unionEdges)));
        root.add("combined_union", unionObj);

        return GSON.toJson(root);
    }

    @SuppressWarnings("unchecked")
    private static List<GeneratedTestCase> buildAllTestCases(
            List<PathEnumerator.EnumeratedPath> paths,
            String sourceCode,
            String dagText,
            String idPrefix)
            throws Exception {

        List<Map<String, Object>> masterDataPool = new ArrayList<>();
        java.lang.reflect.Type poolType = new TypeToken<List<Map<String, Object>>>(){}.getType();

        String prompt = """
                [
                  {"msg": "Valid Standard Flow Run", "age": 22, "a": 12, "b": 4},
                  {"msg": "Zero Parameter Division Exception Test", "age": 19, "a": 10, "b": 0},
                  {"msg": "Negative Integer Range Simulation", "age": 35, "a": -5, "b": 5},
                  {"msg": "Underage Boundary Constraint Verification", "age": 15, "a": 8, "b": 2},
                  {"msg": "Extreme Upper Range Scale Variable Test", "age": 85, "a": 500, "b": 25},
                  {"msg": "Empty String Message Trace Assignment", "age": 26, "a": 0, "b": 10},
                  {"msg": "Perfect Multiplier Variable Array Match", "age": 40, "a": 100, "b": 50},
                  {"msg": "Boundary Age Limit Check Normal Pass", "age": 18, "a": 9, "b": 3},
                  {"msg": "Null Pointer Protection Value Simulation", "age": 30, "a": 4, "b": 2},
                  {"msg": "Complex Variable Sequence Catch Flow", "age": 50, "a": 15, "b": 0}
                ]
                
                Generate a JSON array containing exactly 10 unique objects matching this variable configuration strategy.
                Return ONLY the valid raw JSON array block. Do not include markdown backticks or explanations.
                """;

        System.out.println("[ACK] Querying Ollama for master input values dataset pool...");
        
        try {
            String response = OllamaClient.generate(prompt);
            int start = response.indexOf('[');
            int end = response.lastIndexOf(']');
            if (start != -1 && end != -1) {
                String cleanedJson = response.substring(start, end + 1);
                masterDataPool = GSON.fromJson(cleanedJson, poolType);
            }
        } catch (Exception ex) {
            System.out.println("!!! OLLAMA OFFLINE OR RAW PARSE ERROR - APPLYING SAFE LOCAL FACTORY SEEDS !!!");
        }

        if (masterDataPool == null || masterDataPool.isEmpty()) {
            masterDataPool = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Map<String, Object> seed = new LinkedHashMap<>();
                seed.put("msg", "Deterministic Test Dataset Row " + (i + 1));
                seed.put("age", 16 + (i * 3));
                seed.put("a", 10 + (i * 5));
                seed.put("b", (i % 3 == 0) ? 0 : i * 2);
                masterDataPool.add(seed);
            }
        }

        List<GeneratedTestCase> result = new ArrayList<>();
        System.out.println("[ACK] Binding " + masterDataPool.size() + " data variants dynamically across " + paths.size() + " routes...");

        for (int i = 0; i < paths.size(); i++) {
            PathEnumerator.EnumeratedPath path = paths.get(i);
            
            // FIXED: Explicitly binds both node list and edge label list arrays down to object instances
            GeneratedTestCase tc = new GeneratedTestCase(
                    idPrefix + (i + 1),
                    new ArrayList<>(path.nodeIds),
                    new ArrayList<>(path.edgeLabels));

            Map<String, Object> allocatedData = masterDataPool.get(i % masterDataPool.size());
            tc.input = new LinkedHashMap<>(allocatedData);
            
            tc.description = allocatedData.get("msg") != null 
                    ? allocatedData.get("msg").toString() + " (Route Matrix Path Simulation " + (i + 1) + ")"
                    : "Execution route analysis flow flow case context configuration " + (i + 1);

            for (String nodeId : path.nodeIds) {
                if (nodeId.startsWith("vx2.")) {
                    tc.exception_type = "java.lang.Exception"; 
                    break;
                }
            }

            tc.expected_output = new ArrayList<>();
            result.add(tc);
        }

        System.out.println("[ACK] Completed binding matrix pipeline context structures successfully!");
        return result;
    }

    private static String describePathDeterministically(PathEnumerator.EnumeratedPath path) {
        boolean hasCatch   = path.nodeIds.stream().anyMatch(n -> n.startsWith("vx2."));
        boolean hasFinally = path.nodeIds.stream().anyMatch(n -> n.startsWith("vx3."));

        if (hasCatch && hasFinally) return "Exception path through catch and finally";
        if (hasCatch)               return "Exception path through catch block";
        if (hasFinally)             return "Normal path through finally block";
        return "Normal execution path";
    }
}