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
  private record CallRecord(MethodInfo caller, List<MethodInfo> targets, List<TryRegion> handlers,
                            List<Node> actualIns, Node actualOut) {}

  private static final class ClassInfo {
    final String name;
    final String parentName;
    final List<String> directParents;
    final boolean interfaceType;
    final Node entry;
    final String sourceFile;
    final Map<String, List<MethodInfo>> methods = new LinkedHashMap<>();

    ClassInfo(String name, String parentName, List<String> directParents, boolean interfaceType,
              Node entry, String sourceFile) {
      this.name = name;
      this.parentName = parentName;
      this.directParents = List.copyOf(directParents);
      this.interfaceType = interfaceType;
      this.entry = entry;
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
  /** Reaching definitions are sets because branches and loop exits may merge. */
  private final Map<String, Set<Node>> lastDefinitions = new HashMap<>();
  private final Map<String, String> variableTypes = new HashMap<>();
  private final Map<String, Set<String>> variableRuntimeTypes = new HashMap<>();
  private final Map<String, Set<Integer>> variableParameterDependencies = new HashMap<>();
  private final List<CallRecord> callRecords = new ArrayList<>();
  private final List<ThrowRecord> throwRecords = new ArrayList<>();
  private final Set<String> exceptionEdgeKeys = new HashSet<>();
  private final Set<String> dataEdgeKeys = new HashSet<>();
  private final List<Node> pendingLoopExits = new ArrayList<>();
  private final Map<ParseTree, Node> instrumentationNodes = new IdentityHashMap<>();
  private final Map<JavaParser.MethodCallContext, Node> callActualOutputs = new IdentityHashMap<>();

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
        else if (declaration.interfaceDeclaration() != null) registerInterface(declaration.interfaceDeclaration(), sourceFiles.get(i));
      }
    }
    buildInheritanceHierarchy();
    for (ClassInfo classInfo : classes.values()) {
      currentClass = classInfo;
      for (List<MethodInfo> overloads : classInfo.methods.values()) {
        for (MethodInfo method : overloads) buildMethodBody(method);
      }
    }
    resolveSummaryEdges();
    resolveExceptionalFlows();
    currentClass = null;
    currentMethod = null;
    return startNode;
  }

  private void registerClass(JavaParser.ClassDeclarationContext ctx, String sourceFile) {
    String name = ctx.identifier().getText();
    String parentName = ctx.typeType() == null ? null : simpleType(ctx.typeType().getText());
    List<String> parents = new ArrayList<>();
    if (parentName != null) parents.add(parentName);
    if (ctx.IMPLEMENTS()!=null&&!ctx.typeList().isEmpty()) for (JavaParser.TypeTypeContext type : ctx.typeList(0).typeType()) {
      String implemented=simpleType(type.getText());if(!parents.contains(implemented))parents.add(implemented);
    }
    builder.setOwnershipContext(name, null);
    builder.setCurrentSourceLine(ctx.start.getLine());
    ClassInfo info = new ClassInfo(name, parentName, parents, false,
        builder.createNode("CLASS_ENTRY", name), sourceFile);
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

  private void registerInterface(JavaParser.InterfaceDeclarationContext ctx,String sourceFile){
    String name=ctx.identifier().getText();List<String> parents=new ArrayList<>();
    if(ctx.EXTENDS()!=null&&!ctx.typeList().isEmpty())for(JavaParser.TypeTypeContext type:ctx.typeList(0).typeType())parents.add(simpleType(type.getText()));
    builder.setOwnershipContext(name,null);builder.setCurrentSourceLine(ctx.start.getLine());
    ClassInfo info=new ClassInfo(name,parents.isEmpty()?null:parents.get(0),parents,true,
        builder.createNode("CLASS_ENTRY",name),sourceFile);bind(ctx,info.entry);if(startNode==null)startNode=info.entry;classes.put(name,info);
    for(JavaParser.InterfaceBodyDeclarationContext declaration:ctx.interfaceBody().interfaceBodyDeclaration()){
      if(declaration.interfaceMemberDeclaration()==null)continue;
      JavaParser.InterfaceMemberDeclarationContext member=declaration.interfaceMemberDeclaration();
      if(member.interfaceMethodDeclaration()!=null)registerInterfaceMethod(info,member.interfaceMethodDeclaration().interfaceCommonBodyDeclaration());
      else if(member.genericInterfaceMethodDeclaration()!=null)registerInterfaceMethod(info,member.genericInterfaceMethodDeclaration().interfaceCommonBodyDeclaration());
    }
  }

  private void registerInterfaceMethod(ClassInfo owner,JavaParser.InterfaceCommonBodyDeclarationContext ctx){
    String name=ctx.identifier().getText(),returnType=ctx.typeTypeOrVoid().getText();
    ParseTree body=ctx.methodBody()==null?null:ctx.methodBody().block();
    builder.setOwnershipContext(owner.name,name);builder.setCurrentSourceLine(ctx.start.getLine());
    MethodInfo method=new MethodInfo(name,returnType,builder.createNode("METHOD_ENTRY",owner.name+"."+name),owner,body,true);
    bind(ctx,method.entry);registerParameters(method,ctx.formalParameters());
    finishMethodRegistration(method, body != null);
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
    finishMethodRegistration(method, true);
  }

  private void finishMethodRegistration(MethodInfo method, boolean executable) {
    builder.connect(method.owner.entry, method.entry,
        executable ? "class_member" : "abstract_class_member");
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
    String bodyText = stripLiterals(method.body.getText());
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
      Set<String> overridden = new HashSet<>();
      for (List<MethodInfo> methods : child.methods.values()) {
        for (MethodInfo method : methods) overridden.add(method.signature());
      }
      for(String parentName:child.directParents){ClassInfo parent=classes.get(parentName);if(parent==null)continue;
        List<String> visible=new ArrayList<>();collectVisibleMethods(parent,overridden,visible,new HashSet<>());
        builder.connectInheritance(child.entry,parent.entry,visible);
      }
    }
  }

  private void collectVisibleMethods(ClassInfo owner, Set<String> overridden, List<String> result,Set<String> visited) {
    if(owner==null||!visited.add(owner.name))return;
    for (List<MethodInfo> methods : owner.methods.values()) {
      for (MethodInfo method : methods) {
        if (method.visibleToChildren && !method.name.equals(owner.name)
            && !overridden.contains(method.signature())) result.add(method.signature());
      }
    }
    for(String parentName:owner.directParents)collectVisibleMethods(classes.get(parentName),overridden,result,visited);
  }

  private void buildMethodBody(MethodInfo method) {
    builder.setSourceFile(method.owner.sourceFile);
    currentMethod = method;
    builder.setOwnershipContext(method.owner.name, method.name);
    controls.clear();
    pendingLoopExits.clear();
    controls.push(new ControlContext(method.entry, null));
    lastDefinitions.clear();
    variableTypes.clear();
    variableRuntimeTypes.clear();
    variableParameterDependencies.clear();
    activeTryRegions.clear();
    for (int i = 0; i < method.parameters.size(); i++) {
      ParameterInfo parameter = method.parameters.get(i);
      setDefinition(parameter.name(), parameter.formalIn());
      variableTypes.put(parameter.name(), parameter.type());
      variableParameterDependencies.put(parameter.name(), new LinkedHashSet<>(Set.of(i)));
    }
    if (method.body != null) visit(method.body);
    controls.clear();
    pendingLoopExits.clear();
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
    if (!pendingLoopExits.isEmpty()) {
      for (Node predicate : pendingLoopExits) {
        builder.connect(predicate, node, "control", "false");
      }
      pendingLoopExits.clear();
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
      Map<String, Set<Node>> before = copyDefinitions();
      visitControlled(ctx.statement(0), predicate, "true");
      Map<String, Set<Node>> trueDefinitions = copyDefinitions();
      restoreDefinitions(before);
      if (ctx.statement().size() > 1) visitControlled(ctx.statement(1), predicate, "false");
      Map<String, Set<Node>> falseDefinitions = copyDefinitions();
      restoreDefinitions(mergeDefinitions(trueDefinitions, falseDefinitions));
      return predicate;
    }
    if (ctx.WHILE() != null && ctx.DO() == null) {
      Node predicate = createControlledNode("LOOP_PREDICATE");
      bind(ctx, predicate);
      addUses(predicate, ctx.expression(0).getText());
      Map<String, Set<Node>> before = copyDefinitions();
      visitControlled(ctx.statement(0), predicate, "true");
      restoreDefinitions(mergeDefinitions(before, copyDefinitions()));
      addUses(predicate, ctx.expression(0).getText());
      builder.connect(predicate, predicate, "control", "loop");
      pendingLoopExits.add(predicate);
      return predicate;
    }
    if (ctx.FOR() != null) {
      Node predicate = createControlledNode("LOOP_PREDICATE");
      bind(ctx, predicate);
      if (ctx.forControl() != null) addUses(predicate, ctx.forControl().getText());
      Map<String, Set<Node>> before = copyDefinitions();
      visitControlled(ctx.statement(0), predicate, "true");
      restoreDefinitions(mergeDefinitions(before, copyDefinitions()));
      if (ctx.forControl() != null) addUses(predicate, ctx.forControl().getText());
      builder.connect(predicate, predicate, "control", "loop");
      pendingLoopExits.add(predicate);
      return predicate;
    }
    if (ctx.DO() != null) {
      Node predicate = createControlledNode("LOOP_PREDICATE");
      bind(ctx, predicate);
      if (!ctx.expression().isEmpty()) addUses(predicate, ctx.expression(0).getText());
      Map<String, Set<Node>> before = copyDefinitions();
      visitControlled(ctx.statement(0), predicate, "true");
      restoreDefinitions(mergeDefinitions(before, copyDefinitions()));
      if (!ctx.expression().isEmpty()) addUses(predicate, ctx.expression(0).getText());
      builder.connect(predicate, predicate, "control", "loop");
      pendingLoopExits.add(predicate);
      return predicate;
    }
    if (ctx.block() != null && ctx.TRY() == null) return visit(ctx.block());

    if (ctx.TRY() != null) {
      Node tryNode = createControlledNode("TRY_BLOCK_START", "try@" + ctx.start.getLine());
      bind(ctx, tryNode);
      List<CatchInfo> catches = new ArrayList<>();
      for (JavaParser.CatchClauseContext catchCtx : ctx.catchClause()) {
        List<String> types = splitCatchTypes(catchCtx.catchType().getText());
        builder.setCurrentSourceLine(catchCtx.start.getLine());
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

    List<JavaParser.MethodCallContext> calls = findCallsPostOrder(ctx);
    Node statement = calls.isEmpty()
        ? createControlledNode(ctx.THROW() != null ? "THROW_STMT" : "STMT")
        : createCalls(calls, isValueUsed(ctx.getText()));
    bind(ctx, statement);
    if (calls.isEmpty()) processAssignment(statement, ctx.getText());
    else processCallAssignment(statement, calls, ctx.getText());
    if (ctx.THROW() != null) recordThrow(statement, ctx.expression(0).getText());
    if (ctx.RETURN() != null && currentMethod.formalOut != null) {
      Node returnedValue = calls.isEmpty()
          ? statement : definitionNodeForInitializer(calls, statement);
      if (calls.isEmpty()) addUses(statement, ctx.expression(0).getText());
      currentMethod.returnParameterDependencies.addAll(parameterDependencies(ctx.expression(0).getText()));
      builder.connect(returnedValue, currentMethod.formalOut, "data");
    }
    return statement;
  }

  @Override
  public Node visitBlockStatement(JavaParser.BlockStatementContext ctx) {
    builder.setCurrentSourceLine(ctx.start.getLine());
    if (ctx.localVariableDeclaration() != null) {
      JavaParser.LocalVariableDeclarationContext declaration = ctx.localVariableDeclaration();
      List<JavaParser.MethodCallContext> calls = findCallsPostOrder(declaration);
      Node statement = calls.isEmpty()
          ? createControlledNode("VAR_DECL") : createCalls(calls, true);
      bind(ctx, statement);
      if (declaration.VAR() != null) {
        String name = declaration.identifier().getText();
        String initializer = declaration.expression().getText();
        if (calls.isEmpty()) addUses(statement, initializer);
        setDefinition(name, definitionNodeForInitializer(calls, statement));
        variableTypes.put(name, inferredType(initializer));
        variableParameterDependencies.put(name, parameterDependencies(initializer));
        addRuntimeType(name, initializer);
        return statement;
      }
      for (JavaParser.VariableDeclaratorContext variable : declaration.variableDeclarators().variableDeclarator()) {
        if (calls.isEmpty() && variable.variableInitializer() != null) {
          addUses(statement, variable.variableInitializer().getText());
        }
        String name = variable.variableDeclaratorId().getText();
        setDefinition(name, variable.variableInitializer() == null
            ? statement : definitionNodeForInitializer(calls, statement));
        variableTypes.put(name, simpleType(declaration.typeType().getText()));
        if (variable.variableInitializer() != null) {
          variableParameterDependencies.put(name,
              parameterDependencies(variable.variableInitializer().getText()));
          addRuntimeType(name, variable.variableInitializer().getText());
        } else {
          variableParameterDependencies.remove(name);
        }
      }
      return statement;
    }
    if (ctx.statement() != null) return visit(ctx.statement());
    return null;
  }

  /** A call result is represented by its actual-out vertex, which is the reaching
   * definition for a variable initialized from that call. */
  private Node definitionNodeForInitializer(List<JavaParser.MethodCallContext> calls, Node fallback) {
    if (calls.isEmpty()) return fallback;
    Node actualOut = callActualOutputs.get(calls.get(calls.size() - 1));
    return actualOut == null ? fallback : actualOut;
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
      List<JavaParser.MethodCallContext> nestedCalls = findCallsPostOrder(arguments.get(i));
      String residualArgument = arguments.get(i).getText();
      for (JavaParser.MethodCallContext nested : nestedCalls) {
        residualArgument = residualArgument.replace(nested.getText(), " ");
        Node nestedOutput = callActualOutputs.get(nested);
        if (nestedOutput != null) builder.connect(nestedOutput, actualIn, "data");
      }
      addUses(actualIn, residualArgument);
      actualIns.add(actualIn);
    }

    String receiver = receiverText(call);
    if (receiver != null && !"this".equals(receiver) && !"super".equals(receiver)) {
      addUses(callNode, receiver);
    }
    String receiverType = receiver == null || "this".equals(receiver)
        ? currentClass.name : "super".equals(receiver)
        ? currentClass.parentName : variableTypes.getOrDefault(receiver, simpleType(receiver));

    List<MethodInfo> targets = resolveTargets(receiverType, methodName, arguments.size(), receiver);
    boolean polymorphic = isPolymorphic(receiverType, methodName, arguments.size(), receiver);
    Node actualOut = valueUsed && targets.stream().anyMatch(target -> target.formalOut != null)
        ? builder.createNode("ACTUAL_OUT", methodName + ".return") : null;
    if (actualOut != null) builder.connect(callNode, actualOut, "control");
    if (actualOut != null) callActualOutputs.put(call, actualOut);
    callRecords.add(new CallRecord(currentMethod, new ArrayList<>(targets),
        new ArrayList<>(activeTryRegions), new ArrayList<>(actualIns), actualOut));
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
      }
    }
    return callNode;
  }

  private Node createCalls(List<JavaParser.MethodCallContext> calls, boolean outerValueUsed) {
    Node outer = null;
    for (int i = 0; i < calls.size(); i++) {
      outer = createCall(calls.get(i), i < calls.size() - 1 || outerValueUsed);
    }
    return outer;
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
    return isDescendant(candidate,ancestorName,new HashSet<>());
  }

  private boolean isDescendant(ClassInfo candidate,String ancestorName,Set<String> visited){
    if(candidate==null||!visited.add(candidate.name))return false;
    for(String parent:candidate.directParents){if(parent.equals(ancestorName)||isDescendant(classes.get(parent),ancestorName,visited))return true;}
    return false;
  }

  private MethodInfo lookupMethod(ClassInfo owner, String name, int arity) {
    return lookupMethod(owner,name,arity,new HashSet<>());
  }

  private MethodInfo lookupMethod(ClassInfo owner,String name,int arity,Set<String> visited){
    if(owner==null||!visited.add(owner.name))return null;MethodInfo local=localMethod(owner,name,arity);if(local!=null)return local;
    for(String parent:owner.directParents){MethodInfo inherited=lookupMethod(classes.get(parent),name,arity,visited);if(inherited!=null)return inherited;}
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

  private List<JavaParser.MethodCallContext> findCallsPostOrder(ParseTree tree) {
    List<JavaParser.MethodCallContext> calls = new ArrayList<>();
    collectCallsPostOrder(tree, calls);
    return calls;
  }

  private void collectCallsPostOrder(ParseTree tree, List<JavaParser.MethodCallContext> calls) {
    for (int i = 0; i < tree.getChildCount(); i++) collectCallsPostOrder(tree.getChild(i), calls);
    if (tree instanceof JavaParser.MethodCallContext call) calls.add(call);
  }

  private boolean isValueUsed(String text) {
    return assignmentIndex(text) >= 0 || text.startsWith("return");
  }

  private void processAssignment(Node statement, String text) {
    int assignment = assignmentIndex(text);
    if (assignment < 0) {
      addUses(statement, text);
      Matcher update = Pattern.compile("(?:\\+\\+|--)([A-Za-z_$][A-Za-z0-9_$]*)|([A-Za-z_$][A-Za-z0-9_$]*)(?:\\+\\+|--)")
          .matcher(text);
      if (update.find()) {
        String defined = update.group(1) != null ? update.group(1) : update.group(2);
        Set<Integer> dependencies = new LinkedHashSet<>(
            variableParameterDependencies.getOrDefault(defined, Set.of()));
        setDefinition(defined, statement);
        variableParameterDependencies.put(defined, dependencies);
      }
      return;
    }
    String left = text.substring(0, assignment);
    addUses(statement, text.substring(assignment + 1));
    Matcher matcher = IDENTIFIER.matcher(left);
    String defined = null;
    while (matcher.find()) defined = matcher.group();
    if (defined != null && !NON_VARIABLE_WORDS.contains(defined)) {
      setDefinition(defined, statement);
      variableParameterDependencies.put(defined,
          parameterDependencies(text.substring(assignment + 1)));
      addRuntimeType(defined, text.substring(assignment + 1));
    }
  }

  /** Calls own their argument uses through ACTUAL_IN nodes. This method records
   * only the value defined by an assignment whose right-hand side contains a call. */
  private void processCallAssignment(Node callNode, List<JavaParser.MethodCallContext> calls, String text) {
    int assignment = assignmentIndex(text);
    if (assignment < 0) return;
    Matcher matcher = IDENTIFIER.matcher(text.substring(0, assignment));
    String defined = null;
    while (matcher.find()) defined = matcher.group();
    if (defined == null || NON_VARIABLE_WORDS.contains(defined)) return;
    setDefinition(defined, definitionNodeForInitializer(calls, callNode));
    String expression = text.substring(assignment + 1);
    variableParameterDependencies.put(defined, parameterDependencies(expression));
    addRuntimeType(defined, expression);
  }

  private Set<Integer> parameterDependencies(String expression) {
    Set<Integer> dependencies = new LinkedHashSet<>();
    Matcher matcher = IDENTIFIER.matcher(stripLiterals(expression));
    while (matcher.find()) {
      Set<Integer> variableDependencies = variableParameterDependencies.get(matcher.group());
      if (variableDependencies != null) dependencies.addAll(variableDependencies);
    }
    return dependencies;
  }

  private void resolveSummaryEdges() {
    Set<String> connected = new HashSet<>();
    for (CallRecord call : callRecords) {
      if (call.actualOut() == null) continue;
      for (MethodInfo target : call.targets()) {
        for (int index = 0; index < target.parameters.size(); index++) {
          if (!target.returnParameterDependencies.contains(index)
              && !hasDependencePath(target.parameters.get(index).formalIn(), target.formalOut, target)) continue;
          if (index >= call.actualIns().size()) continue;
          String key = call.actualIns().get(index).id + "->" + call.actualOut().id;
          if (connected.add(key)) builder.connect(call.actualIns().get(index), call.actualOut(), "summary");
        }
      }
    }
  }

  private boolean hasDependencePath(Node start, Node destination, MethodInfo method) {
    if (start == null || destination == null) return false;
    Deque<Node> work = new ArrayDeque<>();
    Set<Node> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    work.add(start);
    visited.add(start);
    while (!work.isEmpty()) {
      Node current = work.removeFirst();
      for (com.example.dag.graph.Edge edge : current.edges) {
        if (!"DATA_DEPENDENCE".equals(edge.type) && !"CONTROL_DEPENDENCE".equals(edge.type)) continue;
        Node next = edge.to;
        if (next == destination) return true;
        if (!method.owner.name.equals(next.classId) || !method.name.equals(next.methodId)) continue;
        if (visited.add(next)) work.addLast(next);
      }
    }
    return false;
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
    Matcher matcher = IDENTIFIER.matcher(stripLiterals(expression));
    while (matcher.find()) {
      for (Node definition : lastDefinitions.getOrDefault(matcher.group(), Set.of())) {
        String key = definition.id + "->" + useNode.id;
        if (definition != useNode && connected.add(definition.id) && dataEdgeKeys.add(key)) {
          builder.connect(definition, useNode, "data");
        }
      }
    }
  }

  /** Replaces Java string, character, and text-block contents before lexical
   * identifier matching so words inside literals cannot become variable uses. */
  private String stripLiterals(String text) {
    StringBuilder result = new StringBuilder(text.length());
    boolean quoted = false;
    boolean character = false;
    boolean escaped = false;
    for (int i = 0; i < text.length(); i++) {
      char current = text.charAt(i);
      if (quoted || character) {
        result.append(' ');
        if (escaped) {
          escaped = false;
        } else if (current == '\\') {
          escaped = true;
        } else if ((quoted && current == '"') || (character && current == '\'')) {
          quoted = false;
          character = false;
        }
      } else if (current == '"') {
        quoted = true;
        result.append(' ');
      } else if (current == '\'') {
        character = true;
        result.append(' ');
      } else {
        result.append(current);
      }
    }
    return result.toString();
  }

  private void setDefinition(String variable, Node definition) {
    lastDefinitions.put(variable, new LinkedHashSet<>(Set.of(definition)));
  }

  private Map<String, Set<Node>> copyDefinitions() {
    Map<String, Set<Node>> copy = new HashMap<>();
    for (Map.Entry<String, Set<Node>> entry : lastDefinitions.entrySet()) {
      copy.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
    }
    return copy;
  }

  private void restoreDefinitions(Map<String, Set<Node>> definitions) {
    lastDefinitions.clear();
    for (Map.Entry<String, Set<Node>> entry : definitions.entrySet()) {
      lastDefinitions.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
    }
  }

  @SafeVarargs
  private final Map<String, Set<Node>> mergeDefinitions(Map<String, Set<Node>>... states) {
    Map<String, Set<Node>> merged = new HashMap<>();
    for (Map<String, Set<Node>> state : states) {
      for (Map.Entry<String, Set<Node>> entry : state.entrySet()) {
        merged.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>()).addAll(entry.getValue());
      }
    }
    return merged;
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
