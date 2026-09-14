# COSDG / ACSD Graph Explorer

## 1. Purpose

- This project analyzes Java source code for software-testing research.
- It constructs a graph inspired by the Call-based Object-Oriented System Dependence Graph (COSDG).
- It augments that graph with exception-handling relationships inspired by the Augmented COSDG (ACSD).
- It records supported traditional, object-oriented, and exception-oriented coverage using runtime evidence.
- It generates candidate tests, compiles and executes them in a bounded child JVM, retains tests that produce runtime evidence, and approximately minimizes the retained suite.
- It archives graph size, timing, approximate heap usage, coverage, tests, and minimization results for every experiment.

## 2. How the project evolved

- The first design investigated **Soot** and bytecode-level analysis.
- Soot is useful for bytecode transformation, call graphs, and intermediate representations such as Jimple.
- The project later moved to **ANTLR** because COSDG/ACSD construction needs clear source-level correspondence.
- Source analysis preserves filenames, line numbers, declarations, conditions, loops, calls, parameters, `try`, `catch`, and `throw` constructs.
- It also provides source expressions needed by targeted test generation.
- Soot is not a dependency of the current application.
- The current pipeline uses ANTLR-generated lexer/parser classes and custom visitors.

## 3. Research position

- COSDG supplies the foundation for procedural and object-oriented relationships in one dependence graph.
- ACSD extends it with exception vertices and exceptional relationships.
- This implementation is **inspired by and extends** those models; it is not claimed to reproduce every paper algorithm exactly.
- Extensions in this project include:
  - Interactive bounded graph exploration.
  - Canonical JSON export and graph fingerprints.
  - Hybrid automatic test generation.
  - Runtime validation of generated candidates.
  - Approximate suite minimization.
  - Automatic research metadata and result folders.

## 4. Technologies

- **Java 17** - implementation and server runtime.
- **Maven** - dependencies, ANTLR generation, compilation, and tests.
- **ANTLR 4.13.1** - Java lexical/syntactic analysis.
- **JavaLexer.g4** and **JavaParser.g4** - Java grammar files.
- **ANTLR visitors** - parse-tree traversal and program analysis.
- **Spark Java 2.9.4** - HTTP server and REST API.
- **Gson 2.10.1** - JSON processing.
- **HTML, CSS, JavaScript, and SVG** - browser interface and graph view.
- **EvoSuite 1.2.0** - optional broad class-level test generation.
- **Z3** - optional SMT constraint solver.
- **Ollama** - optional final input-generation fallback.
- **JUnit** - regression and integration testing.
- **javac and bounded child JVMs** - candidate compilation, execution, and runtime evidence.

## 5. Java grammar and parsing

- Grammar files:
  - `src/main/antlr4/com/example/dag/JavaLexer.g4`
  - `src/main/antlr4/com/example/dag/JavaParser.g4`
- Maven's ANTLR plugin generates `JavaLexer`, `JavaParser`, listeners, and visitors.
- `JavaParserBase` supplies supporting Java-specific parser decisions.
- For each uploaded file the application:
  - Creates an ANTLR character stream.
  - Tokenizes it with `JavaLexer`.
  - Creates a common token stream.
  - Parses a compilation unit with `JavaParser`.
  - Counts syntax errors.
  - Retains the parse tree for graph construction.
- Multiple source files may be parsed with a fixed worker pool.
- Parallelism applies across files. One uploaded file has one effective worker even if four are requested.

## 6. End-to-end workflow

- **Upload**
  - Select one or more `.java` or `.txt` files.
  - A new experiment starts; old runtime coverage and candidate records are cleared.
- **Parse**
  - ANTLR identifies classes, methods, constructors, statements, parameters, expressions, calls, inheritance, and exception constructs.
- **Construct**
  - `ExceptionVisitor` traverses all compilation units.
  - `DAGBuilder` creates graph nodes and edges.
  - Supported cross-method and cross-class relationships are resolved.
- **Canonicalize**
  - Authoritative nodes and edges are exported into one deterministic graph.
  - Duplicate IDs and edges with missing endpoints are rejected.
- **Visualize**
  - The browser shows program, class, and method projections.
  - Large views are bounded to protect the browser; downloaded JSON remains complete.
- **Create obligations**
  - Supported graph elements become explicit coverage obligations with IDs, criterion, source, status, and evidence.
- **Generate candidates**
  - EvoSuite performs broad generation.
  - Remaining obligations use deterministic and specialized generation.
  - SMT and Ollama are bounded fallbacks.
- **Validate at runtime**
  - A retained candidate must compile and execute acceptably.
  - Runtime probes must show that it reached supported graph obligations.
- **Measure coverage**
  - Runtime events are mapped to graph elements and exception/method information.
- **Minimize**
  - Deterministic weighted-greedy set cover removes redundant candidates while preserving achieved runtime coverage.
- **Archive**
  - The complete experiment is saved under the next `results/NN` directory.

## 7. Graph nodes

- Class entry.
- Method entry.
- Statement and variable declaration.
- Call.
- If and loop predicate.
- Formal-in and formal-out parameter.
- Actual-in and actual-out parameter.
- Try-block start.
- Catch start.
- Throw statement.

## 8. Graph edges

- Class member.
- Control dependence.
- Data dependence.
- Simple method call (`Em1`).
- Inherited method call (`Em2`).
- Polymorphic method call (`Em3`).
- Inheritance.
- Parameter in and parameter out.
- Summary.
- Exception catch (`Ee2`).
- Exception throw/flow (`Ee1`).

## 9. Exception modeling

- A try node represents the beginning of a monitored block.
- A catch node records accepted exception types.
- An exception-catch edge associates a try block with a handler.
- A throw node represents an explicit throw statement or supported throwing point.
- An exception-throw edge connects a throwing point to a compatible handler where resolution is possible.
- Supported intra- and interprocedural exception relationships are modeled.
- Runtime probes record thrown/caught events and observed exception types separately.
- The original ACSD study restricted itself to checked exceptions. Any wider support here is an extension that requires separate evaluation.

## 10. Coverage measures

- **Statement** - marked applicable statement-control edges / all applicable statement-control edges.
- **Method** - marked class-member edges / all class-member edges.
- **Method call** - marked `Em1 + Em2 + Em3` edges / all such edges.
- **Polymorphic** - marked `Em3` edges / all `Em3` edges.
- **Inheritance** - runtime-tagged inherited members / modeled visible inherited members.
- **Throw** - marked control dependencies ending at throw nodes / all such elements.
- **Catch** - marked exception-catch edges / all exception-catch edges.
- **Exception flow** - marked exception-throw edges / all exception-throw edges.
- **Exception type** - observed modeled types at throw points / all modeled types at those points.
- Unsupported criteria are displayed as `N/A`, not as invented percentages.
- Branch, condition, path, all-defs, all-uses, and all-def-uses are not claimed as fully runtime-implemented formulas.
- `STATIC_PREDICTED` and `RUNTIME_OBSERVED` are kept separate.
- Coverage proves execution evidence, not behavioral correctness.

## 11. Test generation

- `EVOSUITE`
  - Official EvoSuite broad generation.
  - Requires complete compilable source/dependencies.
  - EvoSuite 1.2.0 uses Java-11-compatible target bytecode.
- `DETERMINISTIC`
  - Bounded values for supported primitive, string, and array parameters.
  - Uses extracted conditions and runtime feedback.
- `EXCEPTION`
  - Specialized deterministic generation for exception obligations.
- `POLYMORPHIC`
  - Constructs a suitable subtype to exercise a dynamic call target.
- `INHERITED`
  - Targets inherited calls and inherited-member obligations.
- `SMT`
  - Sends supported constraints to Z3.
  - Records `SAT`, `UNSAT`, `UNKNOWN`, `TIMEOUT`, or `UNSUPPORTED`.
- `OLLAMA`
  - Final fallback after deterministic/SMT generation.
  - Returns bounded JSON parameter values, not trusted Java code.
  - Candidates still require compilation and runtime validation.
- `generationMethod` states the source of a candidate; it does not guarantee oracle correctness.

## 12. Candidate acceptance

- Generated does not mean retained.
- Each record includes inputs, harness, generation method, compilation/execution status and time, output/error, runtime nodes/edges, method targets, exception types, new obligations, oracle, and failure reason.
- A candidate is retained only when execution is acceptable and the required runtime target is observed.
- EvoSuite regression assertions provide an automatically generated oracle.
- Oracle `NONE` means the test supports structural coverage but does not prove the output is correct.

## 13. Suite minimization

- Only retained runtime-valid candidates from the current graph/session are eligible.
- Graph fingerprints prevent stale tests from being mixed with changed source.
- The minimizer is deterministic weighted-greedy set cover.
- It considers new obligations, semantic distinctions, execution cost, flakiness, generator rank, and setup complexity.
- The result is `APPROXIMATELY_MINIMIZED` because exact minimum set cover is NP-hard.
- Correct wording: **approximately minimized runtime-validated suite preserving achieved supported obligation coverage**.
- Incorrect wording: **guaranteed minimum tests covering every graph node**.

## 14. Time measurement

- Uses monotonic `System.nanoTime()`.
- `parseMs` measures parsing and structure counting.
- `graphMs` measures graph construction.
- `totalMs = parseMs + graphMs`.
- `jsonExportMs` measures canonical graph serialization.
- Candidate records contain separate compilation and execution times.
- Graph timing excludes generation, execution, coverage analysis, paths, HTTP transfer, browser layout, and SVG rendering.
- Benchmarks use warm-ups and median measured runs.

## 15. Heap and complexity

```text
usedHeap = Runtime.totalMemory() - Runtime.freeMemory()
rawGraphHeapDelta = heapAfterGraph - heapAfterParse
graphHeapEstimate = max(0, rawGraphHeapDelta)
```

- UI builds request three garbage-collection passes with short waits before samples.
- `System.gc()` is a request, not a guarantee.
- Heap values are approximate JVM observations, not exact graph-object sizes.
- Exact sizing needs Java Object Layout, an instrumentation agent, or heap-dump analysis.
- The papers describe construction as `O(S)`, where `S` is counted statements.
- Concrete graph storage is `O(V + E)`.
- Experiments must test how `V`, `E`, time, and heap grow with `S`; printing `O(S)` is not proof.

## 16. Canonical graph and reproducibility

- Canonical JSON contains complete authoritative node and edge collections.
- Every edge must reference existing canonical source and target nodes.
- Duplicate node/edge IDs are rejected.
- A fingerprint is calculated from deterministic source/graph content.
- Timestamps and heap samples are excluded from that fingerprint.
- Obligations and tests carry the fingerprint to reject stale evidence.

## 17. Automatic results

- Every successful UI upload creates `results/01`, `results/02`, `results/03`, and so on.
- Each experiment contains:

```text
sources/
canonical-graph.json
statistics.json
research-metadata.json
coverage.json
coverage-obligations.json
validated-test-suite.json
result-summary.md
```

- It may additionally contain `latest-execution.json` and `path-enumeration.json`.
- The same folder is refreshed after generation, execution, session changes, and path enumeration.
- The UI prints its exact location after building the graph.

## 18. Validated large-program example

- `benchmarks/Synthetic5000.java` is the self-contained large fixture.
- It has 5,000 counted statements, 25 classes, 500 methods, and no external dependencies.
- Current bounded validation produced:
  - 7,475 nodes.
  - 14,850 edges.
  - 0 syntax errors.
  - 5,975 supported obligations.
  - Unique canonical node/edge IDs and valid edge endpoints.
- One-class, three-second EvoSuite validation generated ten tests:
  - 10/10 compiled.
  - 10/10 executed successfully.
  - 10/10 produced runtime evidence.
  - Candidates achieved 239 obligations.
  - Greedy minimization selected one test while preserving those 239 obligations.
- This does not mean one test covers the complete 25-class program.
- Twenty regression/integration test classes passed in that validation cycle.
- Times and heap values vary; use the values archived for each experiment.

## 19. Real-world source limitation

- `graph_examples/StringUtils.java` is useful for real-source graph scalability.
- Alone, it cannot compile because it references other Apache Commons Lang classes.
- EvoSuite/runtime validation needs the complete project and dependency classpath.
- Report dependency compilation failure as a limitation, not successful generation.

## 20. Demonstration steps

- Build and start the application.
- Open `http://localhost:8080`.
- Upload `benchmarks/Synthetic5000.java`.
- Click **Build graph**.
- Show syntax errors, statements, classes, methods, nodes, edges, timing, heap, workers, hierarchy, and bounded graph.
- Explain that the canonical JSON is complete even though the screen is bounded.
- Click **Generate tests**.
- Show EvoSuite status, generated/retained counts, compile/execution status, generation method, runtime evidence, coverage contribution, oracle, and minimized suite.
- Open the generated `results/NN` folder.
- Give the complete folder to the supervisor as the experiment package.

## 21. Journal evaluation plan

- Manually validate graph semantics on the nine small controlled programs.
- Test increasing synthetic sizes: 100, 250, 500, 1,000, 2,500, 5,000, and 10,000 statements.
- Use several complete real open-source Java projects.
- Use fresh JVMs where practical, warm-ups, repeated runs, medians, and dispersion.
- Record JVM/OS/hardware, processors, maximum heap, and configuration.
- Compare supported conventional coverage with an established tool such as JaCoCo.
- Compare EvoSuite-only, deterministic-only, deterministic+SMT, and full hybrid generation.
- Report generated candidates, compile success, runtime success, target success, coverage, minimization reduction, and costs.
- Add PIT mutation testing to evaluate fault detection, not coverage alone.
- Compare greedy minimization with exact ILP/SMT set cover on small candidate sets.

## 22. Limitations and threats to validity

- Not every Java/runtime feature is modeled exactly.
- Reflection, dynamic loading, native methods, concurrency, generated bytecode, and external processes may escape analysis.
- Isolated source files may lack compilation dependencies.
- EvoSuite needs Java-11-compatible targets.
- Z3 and Ollama are optional and may be unavailable.
- Ollama can be nondeterministic; runtime validation is mandatory.
- Some graph nodes are structural and not independently executable.
- Some obligations may be infeasible.
- Green elements mean observed execution, not functional correctness.
- Minimization is approximate.
- Heap measurements are approximate.
- One-file uploads do not gain multi-file parallel speedup.
- Browser visualization is bounded.

## 23. Safe and unsafe claims

- Safe: “The tool builds a source-level COSDG/ACSD-inspired canonical graph.”
- Safe: “Generated candidates are compiled and executed before retention.”
- Safe: “The greedy minimizer preserves achieved supported runtime-obligation coverage.”
- Unsafe: “All generated tests are 100% functionally correct.”
- Unsafe: “Every Java program is completely supported.”
- Unsafe: “Every graph node is executable and covered.”
- Unsafe: “The suite is the mathematically guaranteed minimum.”
- Unsafe: “The measurements prove `O(S)` complexity.”

## 24. Build and run

- Use Java 17 for the application. EvoSuite may use an installed Java 11 runtime.

```powershell
mvn clean test
mvn compile exec:java "-Dexec.mainClass=com.example.dag.Main" "-Dexec.args=code.txt"
```

- If `mvn` is unavailable, use the full path to `mvn.cmd`.
- If Windows reports a user-mapped/locked `.class`, stop the running application JVM before rebuilding.

## 25. Important files

- `pom.xml` - build and dependencies.
- `src/main/antlr4/com/example/dag/JavaLexer.g4` - lexer grammar.
- `src/main/antlr4/com/example/dag/JavaParser.g4` - parser grammar.
- `src/main/java/com/example/dag/visitor/ExceptionVisitor.java` - source-to-graph analysis.
- `src/main/java/com/example/dag/DAGBuilder.java` - nodes and edges.
- `src/main/java/com/example/dag/export/CanonicalGraphExporter.java` - complete export.
- `src/main/java/com/example/dag/coverage/CoverageAnalyzer.java` - formulas.
- `src/main/java/com/example/dag/coverage/CoverageObligationEngine.java` - obligations.
- `src/main/java/com/example/dag/coverage/ControlRequirementExtractor.java` - conditions.
- `src/main/java/com/example/dag/runtime/SourceInstrumenter.java` - runtime probes.
- `src/main/java/com/example/dag/runtime/RuntimeExecutionService.java` - bounded execution.
- `src/main/java/com/example/dag/testcase/EvoSuiteCandidateProvider.java` - EvoSuite adapter.
- `src/main/java/com/example/dag/testcase/DeterministicTestGenerationService.java` - targeted generators.
- `src/main/java/com/example/dag/testcase/RuntimeTestSuiteMinimizer.java` - minimization.
- `src/main/java/com/example/dag/server/GraphWorkspace.java` - lifecycle/measurements.
- `src/main/java/com/example/dag/server/ApiServer.java` - API/archive.
- `template.html` - current UI.
- `benchmarks/Synthetic5000.java` - large self-contained fixture.

## 26. Summary

- The work began by investigating Soot bytecode analysis.
- It moved to ANTLR for source-level modeling.
- The grammar creates parse trees and custom visitors construct a COSDG/ACSD-inspired graph.
- The graph is canonicalized, validated, visualized, and exported.
- Runtime probes mark executed elements.
- EvoSuite and targeted strategies generate candidates.
- Compilation and bounded execution validate runtime evidence.
- Supported coverage is calculated from those markings.
- Weighted-greedy set cover approximately minimizes retained tests.
- Each experiment archives source, graph, timing, heap, coverage, tests, and limitations.
- Journal conclusions must distinguish evidence from unsupported or unproven claims.
