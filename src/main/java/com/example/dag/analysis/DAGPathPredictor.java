package com.example.dag.analysis;

import com.example.dag.llm.OllamaClient;

public class DAGPathPredictor {

  public static String predict(
      String sourceCode,
      String dag,
      String testCase)
      throws Exception {

    String prompt = """
        You are an expert symbolic execution engine.

        Given:
        1. Java code
        2. DAG
        3. Test case

        Predict EXACTLY:
        - visited_nodes
        - visited_edges
        - exceptions

        Return STRICT JSON ONLY.

        Example:
        {
          "visited_nodes": ["vx1_01","vs1_02"],
          "visited_edges": ["ec.01","ec.02"],
          "exception":"ArithmeticException"
        }

        CODE:
        %s

        DAG:
        %s

        TEST CASE:
        %s
        """.formatted(
        sourceCode,
        dag,
        testCase);

    return OllamaClient.generate(prompt);
  }
}
