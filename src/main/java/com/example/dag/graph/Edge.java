package com.example.dag.graph;

public class Edge {

  public Node from;

  public Node to;

  public String label;

  // ====================================================
  // COVERAGE + ML SUPPORT
  // ====================================================

  public boolean covered = false;

  public double weight = 0.0;

  public int traversalCount = 0;

  public Edge(
      Node from,
      Node to,
      String label) {

    this.from = from;
    this.to = to;
    this.label = label;
  }
}
