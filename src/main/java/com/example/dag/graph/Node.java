package com.example.dag.graph;

import java.util.*;

public class Node {

  public String id;

  public String type;

  /** Canonical-export ownership metadata; the legacy id remains unchanged. */
  public String program;
  public String sourceFile;
  public String classId;
  public String methodId;
  public int sourceLine = -1;

  public List<Edge> edges = new ArrayList<>();

  /** Static exception types associated with throw/catch vertices. */
  public Set<String> exceptionTypes = new LinkedHashSet<>();

  // ====================================================
  // COVERAGE + ML SUPPORT
  // ====================================================

  public boolean covered = false;

  public double probability = 0.0;

  public int visitCount = 0;

  public Node(String id, String type) {

    this.id = id;
    this.type = type;
  }

  public Edge addEdge(
      Node to,
      String label) {

    return addEdge(to, label, "UNSPECIFIED", null);
  }

  public Edge addEdge(
      Node to,
      String label,
      String type,
      String branch) {

    Edge edge = new Edge(this, to, label, type, branch);
    edges.add(edge);
    return edge;
  }
}
