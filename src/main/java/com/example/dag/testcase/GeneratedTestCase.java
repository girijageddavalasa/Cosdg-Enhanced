package com.example.dag.testcase;

import java.util.List;
import java.util.Map;

/**
 * A test case whose dag_path and dag_edges are set DETERMINISTICALLY
 * by PathEnumerator, and whose description / input / expected_output
 * are filled in by the unified pipeline setup.
 */
public class GeneratedTestCase {

    public String id;
    public String description;
    public Map<String, Object> input; // Consistently typed Map to satisfy compilation checks
    public List<String> expected_output;
    public String exception_type;  // null if normal path

    // Set deterministically — NEVER by Ollama
    public List<String> dag_path;
    public List<String> dag_edges;

    public GeneratedTestCase() {}

    public GeneratedTestCase(
            String id,
            List<String> dag_path,
            List<String> dag_edges) {

        this.id = id;
        this.dag_path = dag_path;
        this.dag_edges = dag_edges;
    }
}