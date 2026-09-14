package com.example.dag.runtime;

import com.example.dag.DAGBuilder;
import com.example.dag.JavaLexer;
import com.example.dag.JavaParser;
import com.example.dag.JavaParserBaseVisitor;
import com.example.dag.export.CanonicalGraphExporter.CanonicalGraph;
import com.example.dag.graph.Edge;
import com.example.dag.graph.Node;
import com.example.dag.server.GraphWorkspace.SourceFile;
import com.example.dag.visitor.ExceptionVisitor;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.TokenStreamRewriter;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Produces a temporary source-level probe copy without changing the submitted source. */
public final class SourceInstrumenter {
  public record Result(String source, String collectorSource, String packageName,
                       Map<String, String> instrumentationMap, List<String> unsupported,
                       DAGBuilder rebuiltGraph) {}
  public record InstrumentedFile(String name, String source, String packageName) {}
  public record ProgramResult(List<InstrumentedFile> files, String collectorSource, String collectorPackage,
                              Map<String, String> instrumentationMap, List<String> unsupported,
                              DAGBuilder rebuiltGraph) {}

  public Result instrument(String program, SourceFile input, CanonicalGraph canonical) {
    ProgramResult result = instrumentAll(program, List.of(input), canonical);
    InstrumentedFile file = result.files().get(0);
    return new Result(file.source(), result.collectorSource(), file.packageName(), result.instrumentationMap(),
        result.unsupported(), result.rebuiltGraph());
  }

  public ProgramResult instrumentAll(String program, List<SourceFile> inputs, CanonicalGraph canonical) {
    List<CommonTokenStream> streams = new ArrayList<>();
    List<JavaParser.CompilationUnitContext> trees = new ArrayList<>();
    for (SourceFile input : inputs) {
      CommonTokenStream tokens = new CommonTokenStream(new JavaLexer(CharStreams.fromString(input.source())));
      JavaParser parser = new JavaParser(tokens);
      JavaParser.CompilationUnitContext tree = parser.compilationUnit();
      if (parser.getNumberOfSyntaxErrors() > 0) throw new IllegalArgumentException("Source contains syntax errors in " + input.name());
      streams.add(tokens); trees.add(tree);
    }
    DAGBuilder builder = new DAGBuilder();
    builder.setGraphScope(program, inputs.size() == 1 ? inputs.get(0).name() : "whole-program");
    ExceptionVisitor graphVisitor = new ExceptionVisitor(builder, null);
    graphVisitor.visitCompilationUnits(trees, inputs.stream().map(SourceFile::name).toList());
    Map<ParseTree, Node> bindings = graphVisitor.getInstrumentationNodes();
    Map<String, String> ids = new LinkedHashMap<>();
    canonical.nodes().forEach(node -> ids.put("N:" + node.internalId(), node.id()));
    canonical.edges().forEach(edge -> ids.put("E:" + edge.internalId(), edge.id()));
    List<String> unsupported = new ArrayList<>();
    List<InstrumentedFile> files = new ArrayList<>();
    String collectorPackage = null;
    for (int i = 0; i < inputs.size(); i++) {
      TokenStreamRewriter rewriter = new TokenStreamRewriter(streams.get(i));
      new ProbeVisitor(rewriter, bindings, builder, unsupported).visit(trees.get(i));
      String pkg = trees.get(i).packageDeclaration() == null ? "" : trees.get(i).packageDeclaration().qualifiedName().getText();
      if (collectorPackage == null) collectorPackage = pkg;
      else if (!collectorPackage.equals(pkg)) throw new IllegalArgumentException("Runtime instrumentation currently requires one Java package");
      files.add(new InstrumentedFile(inputs.get(i).name(), rewriter.getText(), pkg));
    }
    String pkg = collectorPackage == null ? "" : collectorPackage;
    return new ProgramResult(List.copyOf(files), collector(pkg, builder), pkg, Map.copyOf(ids), List.copyOf(unsupported), builder);
  }

  private static final class ProbeVisitor extends JavaParserBaseVisitor<Void> {
    private final TokenStreamRewriter out;
    private final Map<ParseTree, Node> nodes;
    private final DAGBuilder graph;
    private final List<String> unsupported;

    ProbeVisitor(TokenStreamRewriter out, Map<ParseTree, Node> nodes, DAGBuilder graph, List<String> unsupported) {
      this.out = out; this.nodes = nodes; this.graph = graph; this.unsupported = unsupported;
    }

    @Override public Void visitMethodDeclaration(JavaParser.MethodDeclarationContext ctx) {
      Node node = nodes.get(ctx);
      if (node != null && ctx.methodBody() != null && ctx.methodBody().block() != null)
        out.insertAfter(ctx.methodBody().block().start, probe(node, "METHOD_ENTER"));
      return visitChildren(ctx);
    }

    @Override public Void visitConstructorDeclaration(JavaParser.ConstructorDeclarationContext ctx) {
      Node node = nodes.get(ctx);
      if (node != null) out.insertAfter(ctx.block().start, probe(node, "METHOD_ENTER"));
      return visitChildren(ctx);
    }

    @Override public Void visitBlockStatement(JavaParser.BlockStatementContext ctx) {
      Node node = nodes.get(ctx);
      if (node != null && ctx.localVariableDeclaration() != null)
        out.insertBefore(ctx.start, callProbes(ctx) + probe(node, "NODE_HIT"));
      return visitChildren(ctx);
    }

    @Override public Void visitStatement(JavaParser.StatementContext ctx) {
      Node node = nodes.get(ctx);
      if (node == null) return visitChildren(ctx);
      if (ctx.IF() != null) {
        String calls=callProbes(ctx.expression(0));if(!calls.isEmpty())out.insertBefore(ctx.start,calls);
        rewritePredicate(ctx.expression(0), node, "BRANCH");
        return visitChildren(ctx);
      }
      if (ctx.WHILE() != null && ctx.DO() == null) {
        String calls=callProbes(ctx.expression(0));if(!calls.isEmpty())out.insertBefore(ctx.start,calls);
        rewriteLoopPredicate(ctx.expression(0), node);
        return visitChildren(ctx);
      }
      if (ctx.FOR() != null) {
        JavaParser.ForControlContext control = ctx.forControl();
        if (control != null && control.enhancedForControl() == null && control.expression() != null) {
          String calls=callProbes(control.expression());if(!calls.isEmpty())out.insertBefore(ctx.start,calls);
          rewriteLoopPredicate(control.expression(), node);
        } else {
          unsupported.add("Runtime predicate/branch marking is unsupported for enhanced or conditionless for loops at line " + ctx.start.getLine());
          insertStatementProbe(ctx, probe(node, "NODE_HIT"));
        }
        return visitChildren(ctx);
      }
      if (ctx.DO() != null) {
        unsupported.add("Runtime predicate/branch marking is unsupported for do loops at line " + ctx.start.getLine());
        insertStatementProbe(ctx, probe(node, "NODE_HIT"));
        return visitChildren(ctx);
      }
      if (ctx.TRY() != null) {
        out.insertAfter(ctx.block().start, probe(node, "NODE_HIT"));
        return visitChildren(ctx);
      }
      insertStatementProbe(ctx, callProbes(ctx)+probe(node, ctx.THROW() != null ? "EXCEPTION_THROW" : "NODE_HIT"));
      return visitChildren(ctx);
    }

    @Override public Void visitCatchClause(JavaParser.CatchClauseContext ctx) {
      Node node = nodes.get(ctx);
      if (node != null) out.insertAfter(ctx.block().start, probe(node, "EXCEPTION_CATCH"));
      return visitChildren(ctx);
    }

    private void rewritePredicate(JavaParser.ExpressionContext expression, Node node, String event) {
      Edge incoming = uniqueIncoming(node, "CONTROL_DEPENDENCE");
      Edge yes = branch(node, "true");
      Edge no = branch(node, "false");
      String original = out.getTokenStream().getText(expression.getSourceInterval());
      String replacement = "__CosdgRuntime.branch(\"" + node.id + "\",(" + original + "),\""
          + label(incoming) + "\",\"" + label(yes) + "\",\"" + label(no) + "\",\"" + event + "\")";
      out.replace(expression.start, expression.stop, replacement);
      if (no == null) unsupported.add("Graph has no false/exit control edge for predicate " + node.id);
    }

    private void insertStatementProbe(JavaParser.StatementContext ctx, String code) {
      if (ctx.getParent() instanceof JavaParser.BlockStatementContext) out.insertBefore(ctx.start, code);
      else { out.insertBefore(ctx.start, "{" + code); out.insertAfter(ctx.stop, "}"); }
    }

    private String probe(Node node, String event) {
      StringBuilder code = new StringBuilder();
      if ("METHOD_ENTER".equals(event)) code.append("__CosdgRuntime.enter(\"").append(node.id).append("\");");
      else code.append("__CosdgRuntime.hit(\"").append(event).append("\",\"").append(node.id).append("\");");
      Edge incoming = uniqueIncoming(node, "CONTROL_DEPENDENCE");
      if (incoming != null) code.append("__CosdgRuntime.hit(\"EDGE_HIT\",\"").append(incoming.label).append("\");");
      if ("METHOD_ENTER".equals(event)) {
        Edge member = uniqueIncoming(node, "CLASS_MEMBER");
        if (member != null) code.append("__CosdgRuntime.hit(\"EDGE_HIT\",\"").append(member.label).append("\");");
      }
      if ("EXCEPTION_THROW".equals(event)) {
        List<Edge> throwsEdges = outgoing(node, "EXCEPTION_THROW");
        if (throwsEdges.size() == 1) {
          Edge edge = throwsEdges.get(0);
          code.append("__CosdgRuntime.hit(\"EDGE_HIT\",\"").append(edge.label).append("\");");
          if (edge.exceptionType != null) code.append("__CosdgRuntime.hit(\"EXCEPTION_TYPE\",\"")
              .append(node.id).append("=").append(edge.exceptionType).append("\");");
        } else if (throwsEdges.size() > 1) unsupported.add("Runtime exception target resolution unsupported for " + node.id);
        else if (node.exceptionTypes.size() == 1) code.append("__CosdgRuntime.hit(\"EXCEPTION_TYPE\",\"")
            .append(node.id).append("=").append(node.exceptionTypes.iterator().next()).append("\");");
      }
      if ("EXCEPTION_CATCH".equals(event)) {
        Edge caught = uniqueIncoming(node, "EXCEPTION_CATCH");
        if (caught != null) code.append("__CosdgRuntime.hit(\"EDGE_HIT\",\"").append(caught.label).append("\");");
      }
      return code.toString();
    }

    private void rewriteLoopPredicate(JavaParser.ExpressionContext expression, Node node) {
      Edge incoming = incomingControl(node);
      Edge yes = branch(node, "true");
      Edge no = branch(node, "false");
      Edge repeat = graph.getAllEdges().stream().filter(e -> e.from == node && e.to == node
          && "CONTROL_DEPENDENCE".equals(e.type) && "loop".equals(e.branch)).findFirst().orElse(null);
      String original = out.getTokenStream().getText(expression.getSourceInterval());
      String replacement = "__CosdgRuntime.loop(\"" + node.id + "\",(" + original + "),\""
          + label(incoming) + "\",\"" + label(yes) + "\",\"" + label(no) + "\",\""
          + label(repeat) + "\")";
      out.replace(expression.start, expression.stop, replacement);
      if (no == null) unsupported.add("Graph has no false/exit control edge for loop predicate " + node.id);
      if (repeat == null) unsupported.add("Graph has no repeat control edge for loop predicate " + node.id);
    }

    /** Registers graph-backed calls in Java evaluation order, including calls nested
     * inside another invocation such as println(service.run()). */
    private String callProbes(ParseTree context){StringBuilder code=new StringBuilder();List<Node> ordered=new ArrayList<>();collectCalls(context,ordered);
      for(Node call:ordered)if(outgoingCallCount(call)>0)code.append(probe(call,"NODE_HIT")).append("__CosdgRuntime.call(\"").append(call.id).append("\");");return code.toString();}
    private void collectCalls(ParseTree tree,List<Node> ordered){for(int i=0;i<tree.getChildCount();i++)collectCalls(tree.getChild(i),ordered);if(tree instanceof JavaParser.MethodCallContext){Node call=nodes.get(tree);if(call!=null)ordered.add(call);}}

    private Edge uniqueIncoming(Node node, String type) {
      List<Edge> found = graph.getAllEdges().stream().filter(e -> e.to == node && type.equals(e.type)).toList();
      return found.size() == 1 ? found.get(0) : null;
    }
    private Edge incomingControl(Node node) { return graph.getAllEdges().stream()
        .filter(e -> e.to == node && e.from != node && "CONTROL_DEPENDENCE".equals(e.type))
        .findFirst().orElse(null); }
    private int outgoingCallCount(Node node) { return (int) graph.getAllEdges().stream().filter(e -> e.from == node && e.type.endsWith("METHOD_CALL")).count(); }
    private List<Edge> outgoing(Node node, String type) { return graph.getAllEdges().stream().filter(e -> e.from == node && type.equals(e.type)).toList(); }
    private Edge branch(Node node, String branch) { return graph.getAllEdges().stream().filter(e -> e.from == node && "CONTROL_DEPENDENCE".equals(e.type) && branch.equals(e.branch)).findFirst().orElse(null); }
    private String label(Edge edge) { return edge == null ? "" : edge.label; }
  }

  private static String collector(String pkg, DAGBuilder graph) {
    String packageLine = pkg.isBlank() ? "" : "package " + pkg + ";\n";
    StringBuilder mappings = new StringBuilder();
    for (Edge edge : graph.getAllEdges()) {
      if (!edge.type.endsWith("METHOD_CALL")) continue;
      Edge member = "INHERITED_METHOD_CALL".equals(edge.type)
          ? graph.getAllEdges().stream().filter(candidate -> candidate.to == edge.to && "CLASS_MEMBER".equals(candidate.type)).findFirst().orElse(null)
          : null;
      mappings.append("TARGETS.put(\"").append(edge.from.id).append("|").append(edge.to.id).append("\",\"")
          .append(edge.label).append("|").append(member == null ? "" : member.label).append("\");");
    }
    return packageLine + """
        import java.nio.charset.StandardCharsets;
        import java.util.Base64;
        import java.util.ArrayDeque;
        import java.util.HashMap;
        import java.util.Map;
        import java.util.LinkedHashSet;
        import java.util.Set;
        final class __CosdgRuntime {
          private static final Set<String> EVENTS = new LinkedHashSet<>();
          private static final Map<String,String> TARGETS = new HashMap<>();
          private static final Set<String> ACTIVE_LOOPS = new LinkedHashSet<>();
          private static final ThreadLocal<ArrayDeque<String>> CALLS = ThreadLocal.withInitial(ArrayDeque::new);
          static { %s Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            String payload;
            synchronized (__CosdgRuntime.class) { payload = String.join("\\n", EVENTS); }
            System.out.println("__COSDG_EVENTS__" + Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)));
          }, "cosdg-event-flush")); }
          static synchronized void hit(String type, String id) { if (id != null && !id.isEmpty()) EVENTS.add(type + "|" + id); }
          static void call(String callSite) { CALLS.get().addLast(callSite); hit("CALL_SITE", callSite); }
          static void enter(String method) {
            hit("METHOD_ENTER", method);
            ArrayDeque<String> calls = CALLS.get();
            if (calls.isEmpty()) return;
            String call = calls.removeFirst();
            String target = TARGETS.get(call + "|" + method);
            if (target == null) return;
            String[] parts = target.split("\\\\|", -1);
            hit("METHOD_CALL", parts[0]); hit("EDGE_HIT", parts[0]); hit("RUNTIME_TARGET", method);
            if (parts.length > 1 && !parts[1].isEmpty()) hit("INHERITANCE_MEMBER", parts[1]);
          }
          static boolean branch(String node, boolean value, String incoming, String yes, String no, String kind) {
            hit("NODE_HIT", node); hit("EDGE_HIT", incoming);
            String edge = value ? yes : no;
            hit(kind, edge); hit("EDGE_HIT", edge);
            return value;
          }
          static synchronized boolean loop(String node, boolean value, String incoming, String yes, String no, String repeat) {
            hit("NODE_HIT", node);
            if (ACTIVE_LOOPS.add(node)) hit("EDGE_HIT", incoming); else hit("EDGE_HIT", repeat);
            String edge = value ? yes : no;
            hit("LOOP", edge); hit("EDGE_HIT", edge);
            if (!value) ACTIVE_LOOPS.remove(node);
            return value;
          }
        }
        """.formatted(mappings);
  }
}
