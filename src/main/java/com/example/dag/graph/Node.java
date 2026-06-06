package com.example.dag.graph;

import java.util.*;

public class Node {

  public String id;

  public String type;

  public List<Edge> edges = new ArrayList<>();

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

  public void addEdge(
      Node to,
      String label) {

    edges.add(
        new Edge(
            this,
            to,
            label));
  }
}
