# EvoSuite runtime tools

Place the official EvoSuite 1.2.0 release artifacts here:

- `evosuite.jar` — executable test generator
- `evosuite-standalone-runtime.jar` — generated-test runtime dependency

The JARs are intentionally git-ignored. The application treats EvoSuite as an optional
external candidate provider and continues with deterministic, SMT and Ollama generation
when these files are unavailable.

EvoSuite 1.2.0 generates against Java 11 bytecode. The adapter automatically looks for a
sibling `jdk-11*` installation; set `EVOSUITE_JAVA_HOME` to a JDK 11 directory when it is
installed elsewhere. Source that cannot compile with `javac --release 11` is reported as
unsupported by EvoSuite and still proceeds through the existing targeted generators.
