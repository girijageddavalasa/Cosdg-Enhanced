package com.example.dag.graph;

import java.util.ArrayList;
import java.util.List;

public class Edge {

  public Node from;

  public Node to;

  public String label;

  public String type;

  public String branch;

  /** Paper-defined edge tags, currently used for visible methods on Ei. */
  public List<String> tags = new ArrayList<>();

  /** Exception type represented by an Ee1 edge, when applicable. */
  public String exceptionType;

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

    this(from, to, label, "UNSPECIFIED", null);
  }

  public Edge(
      Node from,
      Node to,
      String label,
      String type,
      String branch) {

    this.from = from;
    this.to = to;
    this.label = label;
    this.type = type;
    this.branch = branch;
  }
}
