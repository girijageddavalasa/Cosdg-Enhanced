package com.example.dag;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

// EXPLICIT IMPORTS ADDED HERE TO FIX UNRESOLVED COMPILATION PROBLEMS
import com.example.dag.visitor.ExceptionVisitor;
import com.example.dag.graph.Node;
import com.example.dag.graph.Edge;
import com.example.dag.testcase.VariableExtractor;
import com.example.dag.server.ApiServer;

import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Main {

  public static void main(String[] args)
      throws Exception {

    // ====================================================
    // READ SOURCE CODE
    // ====================================================

    String codePath = args.length > 0 ? args[0] : "input_code.txt";

    // Dynamic fallback guard if the requested file layout varies across paths
    if (!Files.exists(Paths.get(codePath)) && Files.exists(Paths.get("code.txt"))) {
        codePath = "code.txt";
    }

    String code = Files.readString(Paths.get(codePath));

    // ====================================================
    // PARSER SETUP
    // ====================================================

    CharStream input = CharStreams.fromString(code);

    JavaLexer lexer = new JavaLexer(input);

    CommonTokenStream tokens = new CommonTokenStream(lexer);

    JavaParser parser = new JavaParser(tokens);

    ParseTree tree = parser.compilationUnit();

    // ====================================================
    // BUILD DAG
    // ====================================================

    System.out.println(
        "\n--- Extended COSDG DAG Builder ---");

    DAGBuilder builder = new DAGBuilder();

    ExceptionVisitor visitor = new ExceptionVisitor(
        builder,
        null);

    visitor.visit(tree);

    Node startNode = visitor.getStartNode();

    // ====================================================
    // PRINT DAG
    // ====================================================

    System.out.println(
        "\n--- DAG Output ---");

    builder.printGraph(startNode);

    // ====================================================
    // EXPORT DAG TEXT
    // ====================================================

    String dagText = builder.exportAsText(startNode);

    System.out.println(
        "\n--- DAG TEXT ---");

    System.out.println(dagText);

    // ====================================================
    // VARIABLE EXTRACTION
    // ====================================================

    System.out.println(
        "\n--- Extracted Variables ---");

    // Explicitly typed as List<Map<String, String>> to solve unresolved Object errors
    List<Map<String, String>> variables = VariableExtractor.extract(code);

    for (Map<String, String> v : variables) {

      System.out.println(
          v.get("type")
              + " "
              + v.get("name"));
    }

    // ====================================================
    // EXPORT FULL UI (no AI call at startup)
    // ====================================================

    builder.exportAsHtml(
        startNode,
        "visual_graph.html",
        variables,
        "");

    // ====================================================
    // GET ALL NODES AND EDGES FOR COVERAGE
    // ====================================================

    List<Node> allNodes = builder.getAllNodes();

    List<Edge> allEdges = builder.getAllEdges();

    System.out.println(
        "\n--- Graph Stats ---");

    System.out.println(
        "Total nodes: " + allNodes.size());

    System.out.println(
        "Total edges: " + allEdges.size());

    // ====================================================
    // START API SERVER
    // ====================================================

    ApiServer.start(
        code,
        dagText,
        startNode,
        allNodes,
        allEdges);

    System.out.println(
        "\n--- AI Testing Platform Ready ---");

    System.out.println(
        "Open: http://localhost:8080");
  }
}