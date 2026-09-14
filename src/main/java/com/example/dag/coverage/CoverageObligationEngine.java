package com.example.dag.coverage;

import com.example.dag.coverage.CoverageAnalyzer.CoverageMarking;
import com.example.dag.export.CanonicalGraphExporter.CanonicalGraph;
import com.example.dag.export.CanonicalGraphExporter.EdgeRecord;
import com.example.dag.export.CanonicalGraphExporter.NodeRecord;
import com.example.dag.server.GraphWorkspace.Snapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Typed, graph-versioned projection of audited coverage populations. */
public final class CoverageObligationEngine {
  public enum Criterion { STATEMENT_CONTROL, METHOD, METHOD_CALL_EM1, METHOD_CALL_EM2,
    POLYMORPHIC_CALL_EM3, INHERITANCE, EXCEPTION_THROW_FLOW, EXCEPTION_CATCH, EXCEPTION_TYPE }
  public enum Status { UNCOVERED, COVERED, INFEASIBLE, UNSUPPORTED, BLOCKED, STALE }
  public record SourceMetadata(String program, String file, String classId, String methodId, Integer line) {}
  public record ElementTarget(String elementId, String edgeId, String nodeId,
                              String sourceNodeId, String targetNodeId) {}
  public record TargetDetails(String requiredBranch, String callSiteId, String methodEntryId,
                              String methodCallType, String inheritanceEdgeId, String visibleMethod,
                              String classMemberEdgeId, String throwNodeId, String catchNodeId,
                              String exceptionType) {}
  public record Evidence(String source, String sessionId, String testCaseId, String markedElementId,
                         String runtimeTargetMethod, String exceptionType) {}
  public record Obligation(String id, String graphId, Criterion criterion, String elementId,
                           ElementTarget target, SourceMetadata source, TargetDetails details,
                           boolean applicable, Status status, String coverageSource,
                           List<Evidence> evidence) {}
  public record Summary(int total, int covered, int uncovered, int unsupported,
                        int blocked, int infeasible, int stale) {}
  public record Catalog(String graphId, String coverageSource, Summary summary,
                        List<Obligation> obligations) {}

  public Catalog build(Snapshot snapshot, CoverageMarking marking, RuntimeCoverageSession.Summary session) {
    return build(snapshot.graph(), fingerprint(snapshot), marking, session, List.of());
  }
  public Catalog build(CanonicalGraph graph, String canonicalJson, CoverageMarking marking) {
    return build(graph, fingerprint(canonicalJson), marking, null, List.of());
  }

  public Catalog refresh(Catalog previous, Snapshot snapshot, CoverageMarking marking,
                         RuntimeCoverageSession.Summary session) {
    String graphId = fingerprint(snapshot);
    List<Obligation> stale = previous == null ? List.of() : previous.graphId().equals(graphId)
        ? previous.obligations().stream().filter(o -> o.status()==Status.STALE).toList()
        : previous.obligations().stream().map(this::stale).toList();
    return build(snapshot.graph(), graphId, marking, session, stale);
  }

  Catalog build(CanonicalGraph graph, String graphId, CoverageMarking marking,
                RuntimeCoverageSession.Summary session, List<Obligation> carried) {
    String source = marking.runtimeObserved() ? CoverageAnalyzer.RUNTIME_OBSERVED : CoverageAnalyzer.STATIC_PREDICTED;
    Map<String, NodeRecord> nodes = new LinkedHashMap<>(); graph.nodes().forEach(n -> nodes.put(n.id(), n));
    Map<String, EdgeRecord> edges = new LinkedHashMap<>(); graph.edges().forEach(e -> edges.put(e.id(), e));
    List<Obligation> obligations = new ArrayList<>(carried);

    for (EdgeRecord edge : graph.edges()) {
      Criterion criterion = criterion(edge, nodes);
      if (criterion == null) continue;
      NodeRecord location = locationNode(edge, criterion, nodes);
      boolean covered = marking.visitedEdgeLabels().contains(edge.internalId());
      TargetDetails details = details(edge, criterion);
      boolean available = !(criterion==Criterion.POLYMORPHIC_CALL_EM3 && marking.runtimeObserved()
          && marking.availableRuntimeMetrics()!=null && !marking.availableRuntimeMetrics().contains("polymorphic"));
      obligations.add(obligation(graphId, criterion, edge.id(), edgeTarget(edge), source(location, graph),
          details, !available ? Status.UNSUPPORTED : covered ? Status.COVERED : Status.UNCOVERED, source,
          edgeEvidence(edge, covered, source, session, nodes)));
    }

    for (EdgeRecord inheritance : graph.edges().stream().filter(e -> "INHERITANCE".equals(e.type())).toList()) {
      for (String visible : inheritance.tags()) {
        String memberId = visibleMemberEdge(inheritance, visible, graph, nodes);
        EdgeRecord member = memberId == null ? null : edges.get(memberId);
        boolean available = marking.taggedClassMemberEdgeLabels() != null;
        boolean covered = available && member != null
            && marking.taggedClassMemberEdgeLabels() != null
            && marking.taggedClassMemberEdgeLabels().contains(member.internalId());
        Status status = available ? covered ? Status.COVERED : Status.UNCOVERED : Status.UNSUPPORTED;
        String element = inheritance.id() + "#visible=" + visible;
        TargetDetails details = new TargetDetails(null, null, null, null, inheritance.id(), visible,
            memberId, null, null, null);
        obligations.add(obligation(graphId, Criterion.INHERITANCE, element,
            new ElementTarget(element, inheritance.id(), null, inheritance.source(), inheritance.target()),
            source(nodes.get(inheritance.source()), graph), details, status, source,
            inheritanceEvidence(member, covered, source, session)));
      }
    }

    for (NodeRecord node : graph.nodes()) {
      if (!"THROW_STMT".equals(node.type())) continue;
      for (String exceptionType : node.exceptionTypes()) {
        boolean available = marking.markedExceptionTypesByThrowNode() != null;
        boolean covered = available && marking.markedExceptionTypesByThrowNode()
            .getOrDefault(node.internalId(), Set.of()).contains(exceptionType);
        Status status = available ? covered ? Status.COVERED : Status.UNCOVERED : Status.UNSUPPORTED;
        String element = node.id() + "#exception=" + exceptionType;
        TargetDetails details = new TargetDetails(null, null, null, null, null, null, null,
            node.id(), null, exceptionType);
        obligations.add(obligation(graphId, Criterion.EXCEPTION_TYPE, element,
            new ElementTarget(element, null, node.id(), null, null), source(node, graph), details,
            status, source, exceptionTypeEvidence(node, exceptionType, covered, source, session)));
      }
    }
    return catalog(graphId, source, obligations);
  }

  private Criterion criterion(EdgeRecord edge, Map<String, NodeRecord> nodes) {
    return switch (edge.type()) {
      case "CLASS_MEMBER" -> Criterion.METHOD;
      case "SIMPLE_METHOD_CALL" -> Criterion.METHOD_CALL_EM1;
      case "INHERITED_METHOD_CALL" -> Criterion.METHOD_CALL_EM2;
      case "POLYMORPHIC_METHOD_CALL" -> Criterion.POLYMORPHIC_CALL_EM3;
      case "EXCEPTION_THROW" -> Criterion.EXCEPTION_THROW_FLOW;
      case "EXCEPTION_CATCH" -> Criterion.EXCEPTION_CATCH;
      case "CONTROL_DEPENDENCE" -> isStatementControl(edge, nodes) ? Criterion.STATEMENT_CONTROL : null;
      default -> null;
    };
  }
  private boolean isStatementControl(EdgeRecord edge, Map<String, NodeRecord> nodes) {
    NodeRecord from=nodes.get(edge.source()),to=nodes.get(edge.target()); if(from==null||to==null)return false;
    return Set.of("METHOD_ENTRY","IF_PREDICATE","LOOP_PREDICATE").contains(from.type())
        && !(to.type().equals("CLASS_ENTRY")||to.type().equals("METHOD_ENTRY")||to.type().startsWith("FORMAL_")
        ||to.type().startsWith("ACTUAL_")||to.type().equals("CATCH_START")||to.type().equals("TRY_BLOCK_START"));
  }
  private TargetDetails details(EdgeRecord edge, Criterion criterion) {
    return new TargetDetails(criterion==Criterion.STATEMENT_CONTROL?edge.branch():null,
        criterion.name().contains("METHOD_CALL")||criterion==Criterion.POLYMORPHIC_CALL_EM3?edge.source():null,
        criterion.name().contains("METHOD_CALL")||criterion==Criterion.POLYMORPHIC_CALL_EM3?edge.target():null,
        criterion.name().contains("METHOD_CALL")||criterion==Criterion.POLYMORPHIC_CALL_EM3?edge.type():null,
        null,null,null,criterion==Criterion.EXCEPTION_THROW_FLOW?edge.source():null,
        criterion==Criterion.EXCEPTION_THROW_FLOW||criterion==Criterion.EXCEPTION_CATCH?edge.target():null,
        criterion==Criterion.EXCEPTION_THROW_FLOW||criterion==Criterion.EXCEPTION_CATCH?edge.exceptionType():null);
  }
  private NodeRecord locationNode(EdgeRecord edge, Criterion criterion, Map<String,NodeRecord> nodes) {
    return nodes.get(criterion.name().contains("METHOD_CALL")||criterion==Criterion.POLYMORPHIC_CALL_EM3
        ||criterion==Criterion.EXCEPTION_THROW_FLOW
        ?edge.source():edge.target());
  }
  private ElementTarget edgeTarget(EdgeRecord edge) { return new ElementTarget(edge.id(),edge.id(),null,edge.source(),edge.target()); }
  private SourceMetadata source(NodeRecord node, CanonicalGraph graph) { return node==null?new SourceMetadata(graph.metadata().program(),null,null,null,null)
      :new SourceMetadata(graph.metadata().program(),node.file(),node.classId(),node.methodId(),node.line()); }
  private Obligation obligation(String graphId,Criterion criterion,String element,ElementTarget target,SourceMetadata source,
      TargetDetails details,Status status,String coverageSource,List<Evidence> evidence){return new Obligation(
      "OBL_"+hash(graphId+"|"+criterion+"|"+element).substring(0,16),graphId,criterion,element,target,source,details,true,status,coverageSource,List.copyOf(evidence));}
  private Obligation stale(Obligation o){return new Obligation(o.id(),o.graphId(),o.criterion(),o.elementId(),o.target(),o.source(),o.details(),o.applicable(),Status.STALE,o.coverageSource(),o.evidence());}

  private List<Evidence> edgeEvidence(EdgeRecord edge,boolean covered,String source,RuntimeCoverageSession.Summary session,Map<String,NodeRecord> nodes){
    if(!covered)return List.of(); if(session==null||!CoverageAnalyzer.RUNTIME_OBSERVED.equals(source))return List.of(new Evidence(source,null,null,edge.id(),null,edge.exceptionType()));
    List<Evidence> result=new ArrayList<>();for(var run:session.tests())if(run.visitedEdges().contains(edge.internalId())){
      String target=null;if("POLYMORPHIC_METHOD_CALL".equals(edge.type())&&run.runtimeTargets().contains(nodes.get(edge.target()).internalId()))target=nodes.get(edge.target()).classId()+"."+nodes.get(edge.target()).methodId()+"()";
      result.add(new Evidence(source,session.id(),run.testCase(),edge.id(),target,edge.exceptionType()));}return result;
  }
  private List<Evidence> inheritanceEvidence(EdgeRecord member,boolean covered,String source,RuntimeCoverageSession.Summary session){if(!covered)return List.of();if(session==null)return List.of(new Evidence(source,null,null,member==null?null:member.id(),null,null));List<Evidence> out=new ArrayList<>();for(var run:session.tests())if(member!=null&&run.taggedClassMemberEdges().contains(member.internalId()))out.add(new Evidence(source,session.id(),run.testCase(),member.id(),null,null));return out;}
  private List<Evidence> exceptionTypeEvidence(NodeRecord node,String type,boolean covered,String source,RuntimeCoverageSession.Summary session){if(!covered)return List.of();if(session==null)return List.of(new Evidence(source,null,null,node.id(),null,type));List<Evidence> out=new ArrayList<>();for(var run:session.tests())if(run.exceptionTypes().getOrDefault(node.internalId(),Set.of()).contains(type))out.add(new Evidence(source,session.id(),run.testCase(),node.id(),null,type));return out;}
  private String visibleMemberEdge(EdgeRecord inheritance,String visible,CanonicalGraph graph,Map<String,NodeRecord> nodes){String method=visible.contains("/")?visible.substring(0,visible.indexOf('/')):visible;String parentClass=nodes.get(inheritance.target()).classId();return graph.edges().stream().filter(e->"CLASS_MEMBER".equals(e.type())).filter(e->{NodeRecord n=nodes.get(e.target());return n!=null&&Objects.equals(parentClass,n.classId())&&Objects.equals(method,n.methodId());}).map(EdgeRecord::id).findFirst().orElse(null);}
  private Catalog catalog(String graphId,String source,List<Obligation> obligations){Summary s=new Summary(obligations.size(),count(obligations,Status.COVERED),count(obligations,Status.UNCOVERED),count(obligations,Status.UNSUPPORTED),count(obligations,Status.BLOCKED),count(obligations,Status.INFEASIBLE),count(obligations,Status.STALE));return new Catalog(graphId,source,s,List.copyOf(obligations));}
  private int count(List<Obligation> values,Status status){return(int)values.stream().filter(o->o.status()==status).count();}
  public static String fingerprint(String canonicalJson){return "sha256:"+hash(canonicalJson);}
  public static String fingerprint(Snapshot snapshot){
    StringBuilder versioned=new StringBuilder(snapshot.canonicalJson());
    for(var source:snapshot.sources())versioned.append('\u0000').append(source.name()).append('\u0000').append(source.source());
    return fingerprint(versioned.toString());
  }
  private static String hash(String value){try{byte[]digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(digest);}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
