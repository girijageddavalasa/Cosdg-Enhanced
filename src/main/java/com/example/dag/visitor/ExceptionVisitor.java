package com.example.dag.visitor;

import com.example.dag.DAGBuilder;
import com.example.dag.JavaParser;
import com.example.dag.JavaParserBaseVisitor;
import com.example.dag.graph.Node;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.IdentityHashMap;

/** Builds the static COSDG structure currently supported by the project. */
public class ExceptionVisitor extends JavaParserBaseVisitor<Node> {

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
  private static final Set<String> NON_VARIABLE_WORDS = Set.of(
      "byte", "short", "int", "long", "float", "double", "boolean", "char",
      "var", "new", "this", "super", "true", "false", "null", "return", "throw");

  private record ControlContext(Node controller, String branch) {}
  private record ParameterInfo(String type, String name, Node formalIn) {}
  private record CatchInfo(Node node, List<String> types) {}
  private record TryRegion(Node node, List<CatchInfo> catches) {}
  private record ThrowOrigin(Node node, String type) {}
  private record ThrowRecord(MethodInfo method, ThrowOrigin origin, List<TryRegion> handlers) {}
  private record CallRecord(MethodInfo caller, List<MethodInfo> targets, List<TryRegion> handlers) {}

  private static final class ClassInfo {
    final String name;
    final String parentName;
    final Node entry;
    final JavaParser.ClassDeclarationContext context;
    final String sourceFile;
    final Map<String, List<MethodInfo>> methods = new LinkedHashMap<>();

    ClassInfo(String name, String parentName, Node entry, JavaParser.ClassDeclarationContext context, String sourceFile) {
      this.name = name;
      this.parentName = parentName;
      this.entry = entry;
      this.context = context;
      this.sourceFile = sourceFile;
    }
  }

  private static final class MethodInfo {
    final String name;
    final String returnType;
    final Node entry;
    final ClassInfo owner;
    final ParseTree body;
    final boolean visibleToChildren;
    final List<ParameterInfo> parameters = new ArrayList<>();
    final Set<Integer> returnParameterDependencies = new LinkedHashSet<>();
    final Set<ThrowOrigin> localEscapes = new LinkedHashSet<>();
    final Set<ThrowOrigin> escapingThrows = new LinkedHashSet<>();
    Node formalOut;

    MethodInfo(String name, String returnType, Node entry, ClassInfo owner,
               ParseTree body, boolean visibleToChildren) {
      this.name = name;
      this.returnType = returnType;
      this.entry = entry;
      this.owner = owner;
      this.body = body;
      this.visibleToChildren = visibleToChildren;
    }

    String signature() {
      return name + "/" + parameters.size();
    }
  }

  private final DAGBuilder builder;
  private final Map<String, ClassInfo> classes = new LinkedHashMap<>();
  private final Deque<ControlContext> controls = new ArrayDeque<>();
  private final Deque<TryRegion> activeTryRegions = new ArrayDeque<>();
  private final Map<String, Node> lastDefinitions = new HashMap<>();
  private final Map<String, String> variableTypes = new HashMap<>();
  private final Map<String, Set<String>> variableRuntimeTypes = new HashMap<>();
  private final List<CallRecord> callRecords = new ArrayList<>();
  private final List<ThrowRecord> throwRecords = new ArrayList<>();
  private final Set<String> exceptionEdgeKeys = new HashSet<>();
  private final Map<ParseTree, Node> instrumentationNodes = new IdentityHashMap<>();

  private static final Map<String, String> BUILTIN_EXCEPTION_PARENTS = Map.ofEntries(
      Map.entry("Throwable", "Object"),
      Map.entry("Exception", "Throwable"),
      Map.entry("RuntimeException", "Exception"),
      Map.entry("IOException", "Exception"),
      Map.entry("ReflectiveOperationException", "Exception"),
      Map.entry("NullPointerException", "RuntimeException"),
      Map.entry("ArithmeticException", "RuntimeException"),
      Map.entry("IllegalArgumentException", "RuntimeException"),
      Map.entry("IllegalStateException", "RuntimeException"),
      Map.entry("IndexOutOfBoundsException", "RuntimeException"),
      Map.entry("ClassCastException", "RuntimeException")
  );

  private Node startNode;
  private ClassInfo currentClass;
  private MethodInfo currentMethod;

  public ExceptionVisitor(DAGBuilder builder, Object ignored) {
    this.builder = builder;
  }

  public Node getStartNode() {
    return startNode;
  }

  public Map<ParseTree, Node> getInstrumentationNodes() {
    return java.util.Collections.unmodifiableMap(instrumentationNodes);
  }

  private Node bind(ParseTree context, Node node) {
    if (context != null && node != null) instrumentationNodes.put(context, node);
    return node;
  }

  @Override
  public Node visitCompilationUnit(JavaParser.CompilationUnitContext ctx) {
    return visitCompilationUnits(List.of(ctx), java.util.Collections.singletonList(builder.getSourceFile()));
  }

  /** Two-pass whole-program build used for cross-file OO and exception resolution. */
  public Node visitCompilationUnits(List<JavaParser.CompilationUnitContext> units, List<String> sourceFiles) {
    if (units.size() != sourceFiles.size()) throw new IllegalArgumentException("A source file is required for every compilation unit");
    for (int i = 0; i < units.size(); i++) {
      builder.setSourceFile(sourceFiles.get(i));
      for (JavaParser.TypeDeclarationContext declaration : units.get(i).typeDeclaration()) {
        if (declaration.classDeclaration() != null) registerClass(declaration.classDeclaration(), sourceFiles.get(i));
      }
    }
    buildInheritanceHierarchy();
    for (ClassInfo classInfo : classes.values()) {
      currentClass = classInfo;
      for (List<MethodInfo> overloads : classInfo.methods.values()) {
        for (MethodInfo method : overloads) buildMethodBody(method);
      }
    }
    resolveExceptionalFlows();
    currentClass = null;
    currentMethod = null;
    return startNode;
  }

  private void registerClass(JavaParser.ClassDeclarationContext ctx, String sourceFile) {
    String name = ctx.identifier().getText();
    String parentName = ctx.typeType() == null ? null : simpleType(ctx.typeType().getText());
    builder.setOwnershipContext(name, null);
    builder.setCurrentSourceLine(ctx.start.getLine());
    ClassInfo info = new ClassInfo(name, parentName,
        builder.createNode("CLASS_ENTRY", name), ctx, sourceFile);
    bind(ctx, info.entry);
    if (startNode == null) startNode = info.entry;
    classes.put(name, info);

    for (JavaParser.ClassBodyDeclarationContext bodyDeclaration : ctx.classBody().classBodyDeclaration()) {
      if (bodyDeclaration.memberDeclaration() == null) continue;
      JavaParser.MemberDeclarationContext member = bodyDeclaration.memberDeclaration();
      boolean visible = !bodyDeclaration.getText().startsWith("private");
      if (member.methodDeclaration() != null) {
        registerMethod(info, member.methodDeclaration(), visible);
      } else if (member.genericMethodDeclaration() != null) {
        registerMethod(info, member.genericMethodDeclaration().methodDeclaration(), visible);
      } else if (member.constructorDeclaration() != null) {
        registerConstructor(info, member.constructorDeclaration(), visible);
      } else if (member.genericConstructorDeclaration() != null) {
        registerConstructor(info, member.genericConstructorDeclaration().constructorDeclaration(), visible);
      }
    }
  }

  private void registerMethod(ClassInfo owner, JavaParser.MethodDeclarationContext ctx, boolean visible) {
    String name = ctx.identifier().getText();
    String returnType = ctx.typeTypeOrVoid().getText();
    ParseTree body = ctx.methodBody() == null ? null : ctx.methodBody().block();
    builder.setOwnershipContext(owner.name, name);
    builder.setCurrentSourceLine(ctx.start.getLine());
    MethodInfo method = new MethodInfo(name, returnType,
        builder.createNode("METHOD_ENTRY", owner.name + "." + name), owner, body, visible);
    bind(ctx, method.entry);
    registerParameters(method, ctx.formalParameters());
    finishMethodRegistration(method);
  }

  private void registerConstructor(ClassInfo owner, JavaParser.ConstructorDeclarationContext ctx, boolean visible) {
    builder.setOwnershipContext(owner.name, owner.name);
    builder.setCurrentSourceLine(ctx.start.getLine());
    MethodInfo method = new MethodInfo(owner.name, "void",
        builder.createNode("METHOD_ENTRY", owner.name + "." + owner.name),
        owner, ctx.block(), visible);
    bind(ctx, method.entry);
    registerParameters(method, ctx.formalParameters());
    finishMethodRegistration(method);
  }

  private void finishMethodRegistration(MethodInfo method) {
    builder.connect(method.owner.entry, method.entry, "class_member");
    if (!"void".equals(method.returnType)) {
      method.formalOut = builder.createNode("FORMAL_OUT", method.name + ".return");
      builder.connect(method.entry, method.formalOut, "control");
    }
    identifyReturnDependencies(method);
    method.owner.methods.computeIfAbsent(method.name, ignored -> new ArrayList<>()).add(method);
  }

  private void registerParameters(MethodInfo method, JavaParser.FormalParametersContext ctx) {
    if (ctx == null) return;
    List<JavaParser.FormalParameterContext> parameters = new ArrayList<>();
    if (ctx.formalParameter() != null) parameters.add(ctx.formalParameter());
    for (JavaParser.FormalParameterListContext list : ctx.formalParameterList()) {
      parameters.addAll(list.formalParameter());
    }
    for (JavaParser.FormalParameterContext parameter : parameters) {
      String name = parameter.variableDeclaratorId().getText();
      String type = simpleType(parameter.typeType().getText());
      Node formalIn = builder.createNode("FORMAL_IN", name);
      builder.connect(method.entry, formalIn, "control");
      method.parameters.add(new ParameterInfo(type, name, formalIn));
    }
  }

  private void identifyReturnDependencies(MethodInfo method) {
    if (method.body == null || method.formalOut == null) return;
    String bodyText = method.body.getText();
    for (int i = 0; i < method.parameters.size(); i++) {
      String name = method.parameters.get(i).name();
      if (Pattern.compile("return[^;]*\\b" + Pattern.quote(name) + "\\b").matcher(bodyText).find()
          || bodyText.contains("return" + name)) {
        method.returnParameterDependencies.add(i);
      }
    }
  }

  private void buildInheritanceHierarchy() {
    for (ClassInfo child : classes.values()) {
      ClassInfo parent = classes.get(child.parentName);
      if (parent == null) continue;
      Set<String> overridden = new HashSet<>();
      for (List<MethodInfo> methods : child.methods.values()) {
        for (MethodInfo method : methods) overridden.add(method.signature());
      }
      List<String> visible = new ArrayList<>();
      collectVisibleMethods(parent, overridden, visible);
      builder.connectInheritance(child.entry, parent.entry, visible);
    }
  }

  private void collectVisibleMethods(ClassInfo owner, Set<String> overridden, List<String> result) {
    for (List<MethodInfo> methods : owner.methods.values()) {
      for (MethodInfo method : methods) {
        if (method.visibleToChildren && !method.name.equals(owner.name)
            && !overridden.contains(method.signature())) result.add(method.signature());
      }
    }
    ClassInfo parent = classes.get(owner.parentName);
    if (parent != null) collectVisibleMethods(parent, overridden, result);
  }

  private void buildMethodBody(MethodInfo method) {
    builder.setSourceFile(method.owner.sourceFile);
    currentMethod = method;
    builder.setOwnershipContext(method.owner.name, method.name);
    controls.clear();
    controls.push(new ControlContext(method.entry, null));
    lastDefinitions.clear();
    variableTypes.clear();
    variableRuntimeTypes.clear();
    activeTryRegions.clear();
    for (ParameterInfo parameter : method.parameters) {
      lastDefinitions.put(parameter.name(), parameter.formalIn());
      variableTypes.put(parameter.name(), parameter.type());
    }
    if (method.body != null) visit(method.body);
    controls.clear();
    lastDefinitions.clear();
    variableTypes.clear();
    variableRuntimeTypes.clear();
    activeTryRegions.clear();
    builder.setOwnershipContext(null, null);
  }

  private Node createControlledNode(String type) {
    return createControlledNode(type, null);
  }

  private Node createControlledNode(String type, String detail) {
    Node node = builder.createNode(type, detail);
    if (!controls.isEmpty()) {
      ControlContext control = controls.peek();
      builder.connect(control.controller(), node, "control", control.branch());
    }
    return node;
  }

  private void visitControlled(JavaParser.StatementContext statement, Node controller, String branch) {
    controls.push(new ControlContext(controller, branch));
    visit(statement);
    controls.pop();
  }

  @Override
  public Node visitStatement(JavaParser.StatementContext ctx) {
    if (currentMethod == null) return null;
    builder.setCurrentSourceLine(ctx.start.getLine());

    if (ctx.IF() != null) {
      Node predicate = createControlledNode("IF_PREDICATE");
      bind(ctx, predicate);
      addUses(predicate, ctx.expression(0).getText());
      visitControlled(ctx.statement(0), predicate, "true");
      if (ctx.statement().size() > 1) visitControlled(ctx.statement(1), predicate, "false");
      return predicate;
    }
    if (ctx.WHILE() != null && ctx.DO() == null) {
      Node predicate = createControlledNode("LOOP_PREDICATE");
      bind(ctx, predicate);
      addUses(predicate, ctx.expression(0).getText());
      visitControlled(ctx.statement(0), predicate, "true");
      builder.connect(predicate, predicate, "control", "loop");
      return predicate;
    }
    if (ctx.FOR() != null) {
      Node predicate = createControlledNode("LOOP_PREDICATE");
      bind(ctx, predicate);
      if (ctx.forControl() != null) addUses(predicate, ctx.forControl().getText());
      visitControlled(ctx.statement(0), predicate, "true");
      builder.connect(predicate, predicate, "control", "loop");
      return predicate;
    }
    if (ctx.DO() != null) {
      Node predicate = createControlledNode("LOOP_PREDICATE");
      bind(ctx, predicate);
      if (!ctx.expression().isEmpty()) addUses(predicate, ctx.expression(0).getText());
      visitControlled(ctx.statement(0), predicate, "true");
      builder.connect(predicate, predicate, "control", "loop");
      return predicate;
    }
    if (ctx.block() != null && ctx.TRY() == null) return visit(ctx.block());

    if (ctx.TRY() != null) {
      Node tryNode = createControlledNode("TRY_BLOCK_START", "try@" + ctx.start.getLine());
      bind(ctx, tryNode);
      List<CatchInfo> catches = new ArrayList<>();
      for (JavaParser.CatchClauseContext catchCtx : ctx.catchClause()) {
        List<String> types = splitCatchTypes(catchCtx.catchType().getText());
        Node catchNode = builder.createCatchNode("CATCH_START", String.join("|", types));
        bind(catchCtx, catchNode);
        catchNode.exceptionTypes.addAll(types);
        builder.connectExceptionCatch(tryNode, catchNode, String.join("|", types));
        catches.add(new CatchInfo(catchNode, types));
      }

      TryRegion region = new TryRegion(tryNode, catches);
      activeTryRegions.push(region);
      controls.push(new ControlContext(tryNode, "true"));
      visit(ctx.block());
      controls.pop();
      activeTryRegions.pop();

      for (int i = 0; i < ctx.catchClause().size(); i++) {
        JavaParser.CatchClauseContext catchCtx = ctx.catchClause(i);
        CatchInfo catchInfo = catches.get(i);
        String variable = catchCtx.identifier().getText();
        String previousType = variableTypes.put(variable, catchInfo.types().get(0));
        Set<String> previousRuntimeTypes = variableRuntimeTypes.put(variable,
            new LinkedHashSet<>(catchInfo.types()));
        controls.push(new ControlContext(catchInfo.node(), "true"));
        visit(catchCtx.block());
        controls.pop();
        if (previousType == null) variableTypes.remove(variable); else variableTypes.put(variable, previousType);
        if (previousRuntimeTypes == null) variableRuntimeTypes.remove(variable);
        else variableRuntimeTypes.put(variable, previousRuntimeTypes);
      }
      if (ctx.finallyBlock() != null) {
        controls.push(new ControlContext(tryNode, "finally"));
        visit(ctx.finallyBlock().block());
        controls.pop();
      }
      return tryNode;
    }

    JavaParser.MethodCallContext call = findFirstCall(ctx);
    Node statement = call == null
        ? createControlledNode(ctx.THROW() != null ? "THROW_STMT" : "STMT")
        : createCall(call, isValueUsed(ctx.getText()));
    bind(ctx, statement);
    processAssignment(statement, ctx.getText());
    if (ctx.THROW() != null) recordThrow(statement, ctx.expression(0).getText());
    if (ctx.RETURN() != null && currentMethod.formalOut != null) {
      builder.connect(statement, currentMethod.formalOut, "data");
    }
    return statement;
  }

  @Override
  public Node visitBlockStatement(JavaParser.BlockStatementContext ctx) {
    builder.setCurrentSourceLine(ctx.start.getLine());
    if (ctx.localVariableDeclaration() != null) {
      JavaParser.LocalVariableDeclarationContext declaration = ctx.localVariableDeclaration();
      JavaParser.MethodCallContext call = findFirstCall(declaration);
      Node statement = call == null
          ? createControlledNode("VAR_DECL") : createCall(call, true);
      bind(ctx, statement);
      if (declaration.VAR() != null) {
        String name = declaration.identifier().getText();
        String initializer = declaration.expression().getText();
        addUses(statement, initializer);
        lastDefinitions.put(name, statement);
        variableTypes.put(name, inferredType(initializer));
        addRuntimeType(name, initializer);
        return statement;
      }
      for (JavaParser.VariableDeclaratorContext variable : declaration.variableDeclarators().variableDeclarator()) {
        if (variable.variableInitializer() != null) addUses(statement, variable.variableInitializer().getText());
        String name = variable.variableDeclaratorId().getText();
        lastDefinitions.put(name, statement);
        variableTypes.put(name, simpleType(declaration.typeType().getText()));
        if (variable.variableInitializer() != null) {
          addRuntimeType(name, variable.variableInitializer().getText());
        }
      }
      return statement;
    }
    if (ctx.statement() != null) return visit(ctx.statement());
    return null;
  }

  private String inferredType(String expression) {
    Matcher created = Pattern.compile("new([A-Za-z_$][A-Za-z0-9_$]*)").matcher(expression);
    if (created.find()) return simpleType(created.group(1));
    if (expression.matches("[-+]?\\d+[lL]?")) return "int";
    if (expression.startsWith("\"") || expression.startsWith("\"\"\"")) return "String";
    if ("true".equals(expression) || "false".equals(expression)) return "boolean";
    return "Object";
  }

  private Node createCall(JavaParser.MethodCallContext call, boolean valueUsed) {
    String methodName = call.identifier() == null ? call.getText() : call.identifier().getText();
    List<JavaParser.ExpressionContext> arguments = call.arguments().expressionList() == null
        ? List.of() : call.arguments().expressionList().expression();
    Node callNode = createControlledNode("CALL", methodName);
    bind(call, callNode);

    List<Node> actualIns = new ArrayList<>();
    for (int i = 0; i < arguments.size(); i++) {
      Node actualIn = builder.createNode("ACTUAL_IN", methodName + ".arg" + i);
      builder.connect(callNode, actualIn, "control");
      addUses(actualIn, arguments.get(i).getText());
      actualIns.add(actualIn);
    }

    String receiver = receiverText(call);
    String receiverType = receiver == null || "this".equals(receiver)
        ? currentClass.name : "super".equals(receiver)
        ? currentClass.parentName : variableTypes.getOrDefault(receiver, simpleType(receiver));

    List<MethodInfo> targets = resolveTargets(receiverType, methodName, arguments.size(), receiver);
    callRecords.add(new CallRecord(currentMethod, new ArrayList<>(targets),
        new ArrayList<>(activeTryRegions)));
    boolean polymorphic = isPolymorphic(receiverType, methodName, arguments.size(), receiver);
    Node actualOut = valueUsed && targets.stream().anyMatch(target -> target.formalOut != null)
        ? builder.createNode("ACTUAL_OUT", methodName + ".return") : null;
    if (actualOut != null) builder.connect(callNode, actualOut, "control");
    Set<Integer> summarizedInputs = new HashSet<>();
    for (MethodInfo target : targets) {
      String relationship = polymorphic ? "polymorphic_call"
          : target.owner.name.equals(receiverType) || receiver == null || "this".equals(receiver)
          ? "simple_call" : "inherited_call";
      if ((receiver == null || "this".equals(receiver)) && target.owner != currentClass) {
        relationship = "inherited_call";
      }
      if ("super".equals(receiver)) relationship = "inherited_call";
      builder.connect(callNode, target.entry, relationship);

      for (int i = 0; i < Math.min(actualIns.size(), target.parameters.size()); i++) {
        builder.connect(actualIns.get(i), target.parameters.get(i).formalIn(), "parameter_in");
      }
      if (actualOut != null && target.formalOut != null) {
        builder.connect(target.formalOut, actualOut, "parameter_out");
        for (Integer index : target.returnParameterDependencies) {
          if (index < actualIns.size() && summarizedInputs.add(index)) {
            builder.connect(actualIns.get(index), actualOut, "summary");
          }
        }
      }
    }
    return callNode;
  }

  private List<MethodInfo> resolveTargets(String receiverType, String name, int arity, String receiver) {
    if (receiverType == null) return List.of();
    if (isPolymorphic(receiverType, name, arity, receiver)) {
      LinkedHashSet<MethodInfo> targets = new LinkedHashSet<>();
      MethodInfo base = lookupMethod(classes.get(receiverType), name, arity);
      if (base != null && base.body != null) targets.add(base);
      for (ClassInfo candidate : classes.values()) {
        if (isDescendant(candidate, receiverType)) {
          MethodInfo target = lookupMethod(candidate, name, arity);
          if (target != null && target.body != null) targets.add(target);
        }
      }
      return new ArrayList<>(targets);
    }
    MethodInfo target = lookupMethod(classes.get(receiverType), name, arity);
    return target == null ? List.of() : List.of(target);
  }

  private boolean isPolymorphic(String receiverType, String name, int arity, String receiver) {
    if (receiverType == null || receiver == null || "this".equals(receiver) || "super".equals(receiver)) return false;
    MethodInfo base = lookupMethod(classes.get(receiverType), name, arity);
    if (base == null) return false;
    for (ClassInfo candidate : classes.values()) {
      if (isDescendant(candidate, receiverType)) {
        MethodInfo local = localMethod(candidate, name, arity);
        if (local != null && local != base) return true;
      }
    }
    return false;
  }

  private boolean isDescendant(ClassInfo candidate, String ancestorName) {
    String parent = candidate.parentName;
    while (parent != null) {
      if (parent.equals(ancestorName)) return true;
      ClassInfo parentInfo = classes.get(parent);
      parent = parentInfo == null ? null : parentInfo.parentName;
    }
    return false;
  }

  private MethodInfo lookupMethod(ClassInfo owner, String name, int arity) {
    ClassInfo current = owner;
    while (current != null) {
      MethodInfo local = localMethod(current, name, arity);
      if (local != null) return local;
      current = classes.get(current.parentName);
    }
    return null;
  }

  private MethodInfo localMethod(ClassInfo owner, String name, int arity) {
    if (owner == null) return null;
    for (MethodInfo method : owner.methods.getOrDefault(name, List.of())) {
      if (method.parameters.size() == arity) return method;
    }
    return null;
  }

  private String receiverText(JavaParser.MethodCallContext call) {
    ParseTree parent = call.getParent();
    if (parent instanceof JavaParser.MemberReferenceExpressionContext member) {
      String receiver = member.expression().getText();
      return receiver.endsWith(".") ? receiver.substring(0, receiver.length() - 1) : receiver;
    }
    return null;
  }

  private JavaParser.MethodCallContext findFirstCall(ParseTree tree) {
    if (tree instanceof JavaParser.MethodCallContext call) return call;
    for (int i = 0; i < tree.getChildCount(); i++) {
      JavaParser.MethodCallContext found = findFirstCall(tree.getChild(i));
      if (found != null) return found;
    }
    return null;
  }

  private boolean isValueUsed(String text) {
    return assignmentIndex(text) >= 0 || text.startsWith("return");
  }

  private void processAssignment(Node statement, String text) {
    int assignment = assignmentIndex(text);
    if (assignment < 0) {
      addUses(statement, text);
      return;
    }
    String left = text.substring(0, assignment);
    addUses(statement, text.substring(assignment + 1));
    Matcher matcher = IDENTIFIER.matcher(left);
    String defined = null;
    while (matcher.find()) defined = matcher.group();
    if (defined != null && !NON_VARIABLE_WORDS.contains(defined)) {
      lastDefinitions.put(defined, statement);
      addRuntimeType(defined, text.substring(assignment + 1));
    }
  }

  private void addRuntimeType(String variable, String expression) {
    Matcher matcher = Pattern.compile("new([A-Za-z_$][A-Za-z0-9_$]*)").matcher(expression);
    if (matcher.find()) {
      variableRuntimeTypes.computeIfAbsent(variable, ignored -> new LinkedHashSet<>())
          .add(simpleType(matcher.group(1)));
    }
  }

  private int assignmentIndex(String text) {
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) != '=') continue;
      char previous = i == 0 ? '\0' : text.charAt(i - 1);
      char next = i + 1 >= text.length() ? '\0' : text.charAt(i + 1);
      if (previous != '=' && previous != '!' && previous != '<' && previous != '>' && next != '=') return i;
    }
    return -1;
  }

  private void addUses(Node useNode, String expression) {
    Set<String> connected = new HashSet<>();
    Matcher matcher = IDENTIFIER.matcher(expression);
    while (matcher.find()) {
      Node definition = lastDefinitions.get(matcher.group());
      if (definition != null && definition != useNode && connected.add(definition.id)) {
        builder.connect(definition, useNode, "data");
      }
    }
  }

  private void recordThrow(Node throwNode, String expression) {
    Set<String> types = possibleExceptionTypes(expression);
    throwNode.exceptionTypes.addAll(types);
    for (String type : types) {
      throwRecords.add(new ThrowRecord(currentMethod, new ThrowOrigin(throwNode, type),
          new ArrayList<>(activeTryRegions)));
    }
  }

  private Set<String> possibleExceptionTypes(String expression) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    Matcher created = Pattern.compile("new([A-Za-z_$][A-Za-z0-9_$]*)").matcher(expression);
    if (created.find()) result.add(simpleType(created.group(1)));
    if (!result.isEmpty()) return result;

    Matcher identifier = IDENTIFIER.matcher(expression);
    if (identifier.find()) {
      String variable = identifier.group();
      Set<String> runtimeTypes = variableRuntimeTypes.get(variable);
      if (runtimeTypes != null && !runtimeTypes.isEmpty()) result.addAll(runtimeTypes);
      else if (variableTypes.containsKey(variable)) result.add(variableTypes.get(variable));
    }
    return result;
  }

  private List<String> splitCatchTypes(String catchType) {
    List<String> result = new ArrayList<>();
    for (String type : catchType.split("\\|")) result.add(simpleType(type));
    return result;
  }

  private void resolveExceptionalFlows() {
    for (ThrowRecord record : throwRecords) {
      CatchInfo handler = findNearestHandler(record.origin().type(), record.handlers());
      if (handler == null) record.method().localEscapes.add(record.origin());
      else connectException(record.origin(), handler.node());
    }

    for (ClassInfo classInfo : classes.values()) {
      for (List<MethodInfo> methods : classInfo.methods.values()) {
        for (MethodInfo method : methods) method.escapingThrows.addAll(method.localEscapes);
      }
    }

    boolean changed;
    do {
      changed = false;
      for (CallRecord call : callRecords) {
        for (MethodInfo target : call.targets()) {
          for (ThrowOrigin origin : new ArrayList<>(target.escapingThrows)) {
            CatchInfo handler = findNearestHandler(origin.type(), call.handlers());
            if (handler != null) {
              connectException(origin, handler.node());
            } else if (call.caller().escapingThrows.add(origin)) {
              changed = true;
            }
          }
        }
      }
    } while (changed);

    for (ClassInfo classInfo : classes.values()) {
      for (List<MethodInfo> methods : classInfo.methods.values()) {
        for (MethodInfo method : methods) {
          for (ThrowOrigin origin : method.escapingThrows) method.entry.exceptionTypes.add(origin.type());
        }
      }
    }
  }

  private void connectException(ThrowOrigin origin, Node catchNode) {
    String key = origin.node().id + "->" + catchNode.id + ":" + origin.type();
    if (exceptionEdgeKeys.add(key)) {
      builder.connectExceptionThrow(origin.node(), catchNode, origin.type());
    }
  }

  private CatchInfo findNearestHandler(String thrownType, List<TryRegion> handlers) {
    for (TryRegion region : handlers) {
      for (CatchInfo handler : region.catches()) {
        for (String caughtType : handler.types()) {
          if (isTypeCompatible(thrownType, caughtType)) return handler;
        }
      }
    }
    return null;
  }

  private boolean isTypeCompatible(String thrownType, String caughtType) {
    if (thrownType == null || caughtType == null) return false;
    if (thrownType.equals(caughtType)) return true;

    String current = thrownType;
    Set<String> visited = new HashSet<>();
    while (current != null && visited.add(current)) {
      ClassInfo sourceClass = classes.get(current);
      current = sourceClass != null ? sourceClass.parentName : BUILTIN_EXCEPTION_PARENTS.get(current);
      if (caughtType.equals(current)) return true;
    }

    // Conservative fallback for external exception classes absent from the source model.
    return ("Exception".equals(caughtType) || "Throwable".equals(caughtType))
        && thrownType.endsWith("Exception");
  }

  private String simpleType(String text) {
    if (text == null) return null;
    String withoutGenerics = text.replaceAll("<.*>", "").replace("[]", "");
    Matcher matcher = IDENTIFIER.matcher(withoutGenerics);
    String result = withoutGenerics;
    while (matcher.find()) result = matcher.group();
    return result;
  }
}
