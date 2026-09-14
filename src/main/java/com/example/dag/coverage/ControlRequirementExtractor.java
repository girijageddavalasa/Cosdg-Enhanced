package com.example.dag.coverage;

import com.example.dag.JavaLexer;
import com.example.dag.JavaParser;
import com.example.dag.JavaParserBaseVisitor;
import com.example.dag.coverage.CoverageObligationEngine.Obligation;
import com.example.dag.coverage.CoverageObligationEngine.Status;
import com.example.dag.export.CanonicalGraphExporter.CanonicalGraph;
import com.example.dag.export.CanonicalGraphExporter.EdgeRecord;
import com.example.dag.export.CanonicalGraphExporter.NodeRecord;
import com.example.dag.server.GraphWorkspace.Snapshot;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts required predicate outcomes from existing COSDG control-dependence edges. */
public final class ControlRequirementExtractor {
  public enum Kind { CONTROL_REQUIREMENT, SAFETY_PRECONDITION, TARGET_REQUIREMENT }
  public enum Outcome { TRUE, FALSE, NOT_APPLICABLE, UNKNOWN }
  public enum RequirementStatus { SUPPORTED, UNSUPPORTED, STALE }
  public record Requirement(Kind kind, String expression, Outcome requiredOutcome,
                            String controllerNodeId, String controllerEdgeId, int depth,
                            CoverageObligationEngine.SourceMetadata source,
                            List<String> referencedVariables, RequirementStatus status,
                            String targetElementId, CoverageObligationEngine.TargetDetails targetDetails) {}
  public record Result(String graphId, String obligationId, RequirementStatus status,
                       List<Requirement> requirements) {}

  private static final Pattern IDENTIFIER=Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
  private static final Set<String> WORDS=Set.of("true","false","null","this","super","new",
      "int","long","short","byte","double","float","boolean","char","instanceof");

  public Result extract(Snapshot snapshot, Obligation obligation) {
    if (obligation==null) throw new IllegalArgumentException("Obligation is required");
    String currentGraphId=CoverageObligationEngine.fingerprint(snapshot);
    if (obligation.status()==Status.STALE || !obligation.graphId().equals(currentGraphId))
      return new Result(currentGraphId,obligation.id(),RequirementStatus.STALE,List.of(target(obligation,RequirementStatus.STALE)));
    CanonicalGraph graph=snapshot.graph(); Map<String,NodeRecord> nodes=new LinkedHashMap<>();graph.nodes().forEach(n->nodes.put(n.id(),n));
    Map<String,List<EdgeRecord>> incoming=new HashMap<>();for(EdgeRecord edge:graph.edges())if("CONTROL_DEPENDENCE".equals(edge.type()))incoming.computeIfAbsent(edge.target(),ignored->new ArrayList<>()).add(edge);
    Map<String,String> expressions=expressions(snapshot,graph);
    String anchor=anchor(obligation);
    List<EdgeRecord> reverse=new ArrayList<>(); Set<String> visited=new HashSet<>(); String current=anchor;
    while(current!=null&&visited.add(current)){
      List<EdgeRecord> candidates=incoming.getOrDefault(current,List.of());
      if(candidates.isEmpty())break;
      EdgeRecord control=candidates.get(0); reverse.add(control); current=control.source();
      NodeRecord controller=nodes.get(current);if(controller!=null&&("METHOD_ENTRY".equals(controller.type())||"CLASS_ENTRY".equals(controller.type())))break;
    }
    Collections.reverse(reverse); List<Requirement> result=new ArrayList<>(); int depth=0; boolean unsupported=false;
    for(EdgeRecord edge:reverse){NodeRecord controller=nodes.get(edge.source());if(controller==null||!("IF_PREDICATE".equals(controller.type())||"LOOP_PREDICATE".equals(controller.type())))continue;
      Outcome outcome=outcome(edge.branch());String expression=expressions.get(controller.id());RequirementStatus status=expression==null||outcome==Outcome.UNKNOWN?RequirementStatus.UNSUPPORTED:RequirementStatus.SUPPORTED;
      unsupported|=status==RequirementStatus.UNSUPPORTED;result.add(new Requirement(Kind.CONTROL_REQUIREMENT,expression,outcome,controller.id(),edge.id(),depth++,source(snapshot,controller),variables(expression),status,null,null));}
    // Safety preconditions are deliberately absent unless directly represented; none are inferred from array/member syntax.
    result.add(target(obligation,RequirementStatus.SUPPORTED));
    return new Result(obligation.graphId(),obligation.id(),unsupported?RequirementStatus.UNSUPPORTED:RequirementStatus.SUPPORTED,List.copyOf(result));
  }

  private String anchor(Obligation obligation){var d=obligation.details();return switch(obligation.criterion()){
    case STATEMENT_CONTROL -> obligation.target().targetNodeId();
    case METHOD_CALL_EM1,METHOD_CALL_EM2,POLYMORPHIC_CALL_EM3 -> d.callSiteId();
    case EXCEPTION_THROW_FLOW,EXCEPTION_TYPE -> d.throwNodeId();
    case EXCEPTION_CATCH -> obligation.target().sourceNodeId();
    case METHOD -> obligation.target().targetNodeId();
    case INHERITANCE -> obligation.target().sourceNodeId();};}
  private Outcome outcome(String branch){if("true".equalsIgnoreCase(branch))return Outcome.TRUE;if("false".equalsIgnoreCase(branch))return Outcome.FALSE;return Outcome.UNKNOWN;}
  private Requirement target(Obligation obligation,RequirementStatus status){return new Requirement(Kind.TARGET_REQUIREMENT,null,Outcome.NOT_APPLICABLE,null,null,-1,obligation.source(),List.of(),status,obligation.elementId(),obligation.details());}
  private CoverageObligationEngine.SourceMetadata source(Snapshot snapshot,NodeRecord node){return new CoverageObligationEngine.SourceMetadata(snapshot.program(),node.file(),node.classId(),node.methodId(),node.line());}
  private List<String> variables(String expression){if(expression==null)return List.of();String lexical=expression.replaceAll("\"(?:\\\\.|[^\"\\\\])*\""," ").replaceAll("'(?:\\\\.|[^'\\\\])'"," ");LinkedHashSet<String> values=new LinkedHashSet<>();Matcher matcher=IDENTIFIER.matcher(lexical);while(matcher.find()){String value=matcher.group();int end=matcher.end();boolean method=end<lexical.length()&&lexical.charAt(end)=='(';boolean member=matcher.start()>0&&lexical.charAt(matcher.start()-1)=='.';if(!WORDS.contains(value)&&!method&&!member&&!Character.isUpperCase(value.charAt(0)))values.add(value);}return List.copyOf(values);}

  private Map<String,String> expressions(Snapshot snapshot,CanonicalGraph graph){Map<Key,List<String>> parsed=new LinkedHashMap<>();for(var file:snapshot.sources()){
    JavaParser parser=new JavaParser(new CommonTokenStream(new JavaLexer(CharStreams.fromString(file.source()))));JavaParser.CompilationUnitContext tree=parser.compilationUnit();new JavaParserBaseVisitor<Void>(){
      @Override public Void visitStatement(JavaParser.StatementContext ctx){if(ctx.IF()!=null)parsed.computeIfAbsent(new Key(file.name(),ctx.start.getLine(),"IF_PREDICATE"),ignored->new ArrayList<>()).add(ctx.expression(0).getText());else if(ctx.WHILE()!=null&&ctx.DO()==null)parsed.computeIfAbsent(new Key(file.name(),ctx.start.getLine(),"LOOP_PREDICATE"),ignored->new ArrayList<>()).add(ctx.expression(0).getText());return visitChildren(ctx);}}.visit(tree);}
    Map<Key,Integer> indexes=new HashMap<>();Map<String,String> result=new HashMap<>();for(NodeRecord node:graph.nodes()){if(!("IF_PREDICATE".equals(node.type())||"LOOP_PREDICATE".equals(node.type()))||node.line()==null)continue;Key key=new Key(node.file(),node.line(),node.type());int index=indexes.getOrDefault(key,0);List<String> values=parsed.getOrDefault(key,List.of());if(index<values.size())result.put(node.id(),values.get(index));indexes.put(key,index+1);}return result;}
  private record Key(String file,int line,String type){}
}
