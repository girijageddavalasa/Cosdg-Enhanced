package com.example.dag;

import com.example.dag.graph.*;

import java.util.*;
import java.io.*;
import java.nio.file.*;

public class DAGBuilder {

  private int nodeCount = 1;

  private int controlEdgeCount = 1;

  private int exceptionEdgeCount = 1;

  // ============================================================
  // ALL NODES AND EDGES (for coverage computation)
  // ============================================================

  private List<com.example.dag.graph.Node> allNodesList = new java.util.ArrayList<>();

  private List<com.example.dag.graph.Edge> allEdgesList = new java.util.ArrayList<>();

  // ============================================================
  // PRIVATIZED SANITIZATION UTILITY FOR LABELS BOUNDS
  // ============================================================
  private String escapeMermaidLabel(String text) {
    if (text == null) return "";
    return text.replace("\"", "'")
               .replace("[", "(")   
               .replace("]", ")")   
               .replace("&", "and") 
               .replace("<", "lt")
               .replace(">", "gt");
  }

  // ============================================================
  // NODE CREATION
  // ============================================================

  public Node createNode(String type) {

    String formalId;

    switch (type) {

      case "TRY_BLOCK_START":

        formalId = String.format(
            "vx1.%02d",
            nodeCount++);
        break;

      case "CATCH_START":

        formalId = String.format(
            "vx2.%02d",
            nodeCount++);
        break;

      case "FINALLY_START":

        formalId = String.format(
            "vx3.%02d",
            nodeCount++);
        break;

      default:

        formalId = String.format(
            "vs1.%02d",
            nodeCount++);
        break;
    }

    Node node = new Node(

        formalId.replace('.', '_'),

        formalId + "<br/>" + type);

    allNodesList.add(node);

    return node;
  }

  // ============================================================
  // CREATE CATCH NODE WITH EXCEPTION TYPE LABEL
  // ============================================================

  public Node createCatchNode(String type, String exceptionType) {

    String formalId = String.format(
        "vx2.%02d",
        nodeCount++);

    Node node = new Node(

        formalId.replace('.', '_'),

        formalId + "<br/>" + type + "<br/>[" + exceptionType + "]");

    allNodesList.add(node);

    return node;
  }

  // ============================================================
  // GET ALL NODES AND EDGES
  // ============================================================

  public List<com.example.dag.graph.Node> getAllNodes() {
    return allNodesList;
  }

  public List<com.example.dag.graph.Edge> getAllEdges() {
    return allEdgesList;
  }

  // ============================================================
  // EDGE CONNECTION
  // ============================================================

  public void connect(
      Node from,
      Node to,
      String label) {

    String edgeLabel;

    if (label.equalsIgnoreCase("exception")) {

      edgeLabel = String.format(
          "ex.%02d",
          exceptionEdgeCount++);

    } else {

      edgeLabel = String.format(
          "ec.%02d",
          controlEdgeCount++);
    }

    from.addEdge(
        to,
        edgeLabel);

    // Track for coverage analysis
    allEdgesList.add(
        new com.example.dag.graph.Edge(from, to, edgeLabel));
  }

  // ============================================================
  // PRINT GRAPH
  // ============================================================

  public void printGraph(Node start) {

    Queue<Node> q = new LinkedList<>();

    Set<String> visited = new HashSet<>();

    q.add(start);

    while (!q.isEmpty()) {

      Node n = q.poll();

      if (visited.contains(n.id))
        continue;

      visited.add(n.id);

      for (Edge e : n.edges) {

        System.out.println(

            n.type
                + " --("
                + e.label
                + ")--> "
                + e.to.type);

        q.add(e.to);
      }
    }
  }

  // ============================================================
  // EXPORT DAG AS TEXT
  // ============================================================

  public String exportAsText(Node start) {

    StringBuilder sb = new StringBuilder();

    Queue<Node> q = new LinkedList<>();

    Set<String> visited = new HashSet<>();

    q.add(start);

    while (!q.isEmpty()) {

      Node n = q.poll();

      if (visited.contains(n.id))
        continue;

      visited.add(n.id);

      for (Edge e : n.edges) {

        sb.append(
            n.id
                + " --("
                + e.label
                + ")--> "
                + e.to.id
                + "\n");

        q.add(e.to);
      }
    }

    return sb.toString();
  }

  // ============================================================
  // EXPORT HTML
  // ============================================================

  public void exportAsHtml(
      Node start,
      String filename,
      List<Map<String, String>> variables,
      String aiResponse) {

    StringBuilder mermaid = new StringBuilder();

    mermaid.append("graph TD\n");

    Queue<Node> q = new LinkedList<>();

    Set<String> visited = new HashSet<>();

    q.add(start);

    // ========================================================
    // NODES EXPORT GENERATOR (CLEAN STANDARD VIEW MODE)
    // ========================================================
    while (!q.isEmpty()) {

      Node n = q.poll();

      if (visited.contains(n.id))
        continue;

      visited.add(n.id);

      // Extract only the structural token header string parameters
      // Bypasses the complex source code strings that break the Mermaid parser regex
      String structuralLabel = n.type;
      if (structuralLabel.contains("<br/>")) {
         String[] tokens = structuralLabel.split("<br/>");
         if (tokens.length >= 2) {
             structuralLabel = tokens[0] + " : " + tokens[1];
         }
      } else if (structuralLabel.contains("<br>")) {
         String[] tokens = structuralLabel.split("<br>");
         if (tokens.length >= 2) {
             structuralLabel = tokens[0] + " : " + tokens[1];
         }
      }

      mermaid.append("    ")
          .append(n.id)
          .append("[\"")
          .append(escapeMermaidLabel(structuralLabel))
          .append("\"]\n");

      for (Edge e : n.edges) {
        q.add(e.to);
      }
    }

    // ========================================================
    // EDGES EXPORT GENERATOR
    // ========================================================
    q.clear();
    visited.clear();
    q.add(start);

    int edgeIndex = 0;

    List<Integer> exceptionEdgeIndexes = new ArrayList<>();

    while (!q.isEmpty()) {

      Node n = q.poll();

      if (visited.contains(n.id))
        continue;

      visited.add(n.id);

      for (Edge e : n.edges) {

        if (e.label.startsWith("ex.")) {
          exceptionEdgeIndexes.add(edgeIndex);
        }

        if (e.label.startsWith("ex.")) {

          mermaid.append("    ")
              .append(n.id)
              .append(" -.->|")
              .append(e.label)
              .append("| ")
              .append(e.to.id)
              .append("\n");

        } else {

          mermaid.append("    ")
              .append(n.id)
              .append(" -->|")
              .append(e.label)
              .append("| ")
              .append(e.to.id)
              .append("\n");
        }

        q.add(e.to);
        edgeIndex++;
      }
    }

    // ========================================================
    // EXCEPTION EDGE STYLING
    // ========================================================

    for (Integer idx : exceptionEdgeIndexes) {
      mermaid.append("    linkStyle ")
          .append(idx)
          .append(" stroke:#e24b4a,stroke-width:2.5px,stroke-dasharray: 6 4;\n");
    }

    // ========================================================
    // VARIABLE INPUTS
    // ========================================================

    StringBuilder variableInputs = new StringBuilder();

    for (Map<String, String> v : variables) {

      variableInputs.append("<div class=\"input-group\">")
          .append("<label>").append(v.get("name"))
          .append(" (").append(v.get("type")).append(")</label>")
          .append("<input type=\"text\" placeholder=\"Enter value\" id=\"")
          .append(v.get("name")).append("\">")
          .append("</div>\n");
    }

    // ========================================================
    // LOAD TEMPLATE AND SUBSTITUTE
    // ========================================================

    String html;

    try {

      html = Files.readString(Paths.get("template.html"));

    } catch (Exception e) {

      System.err.println("template.html not found — using fallback");

      html = "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
          + "<script type='module'>import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.esm.min.mjs';"
          + "mermaid.initialize({startOnLoad:true});</script></head><body>"
          + "<div class='mermaid'>MERMAID_CONTENT</div></body></html>";
    }

    html = html.replace("MERMAID_CONTENT", mermaid.toString());
    html = html.replace("VARIABLE_INPUTS", variableInputs.toString());

    // ========================================================
    // WRITE HTML FILE DATA
    // ========================================================

    try {

      Files.writeString(Paths.get(filename), html);

      System.out.println(
          "Successfully generated: "
              + Paths.get(filename).toAbsolutePath());

      if (java.awt.Desktop.isDesktopSupported()) {
        java.awt.Desktop.getDesktop().browse(Paths.get(filename).toUri());
      }

    } catch (Exception e) {

      System.err.println("Failed to write HTML: " + e.getMessage());
    }
  }
}