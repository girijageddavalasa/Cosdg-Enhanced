package com.example.dag.testcase;

import com.example.dag.graph.Edge;
import com.example.dag.graph.Node;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Resource-bounded enumeration of the project's dependency-graph walks. */
public final class PathEnumerator {
  public static final int DEFAULT_PATH_LIMIT = 5_000;
  public static final int DEFAULT_PATH_LENGTH_LIMIT = 1_000;
  public static final long DEFAULT_STATE_LIMIT = 250_000;
  public static final long DEFAULT_TIME_LIMIT_MS = 2_000;
  public static final int HARD_PATH_LIMIT = 5_000;
  public static final int HARD_PATH_LENGTH_LIMIT = 2_000;
  public static final long HARD_STATE_LIMIT = 1_000_000;
  public static final long HARD_TIME_LIMIT_MS = 30_000;
  public enum Status { COMPLETE, TRUNCATED, CANCELLED }
  public enum StopReason { NONE, PATH_LIMIT, PATH_LENGTH_LIMIT, TRAVERSAL_STATE_LIMIT, TIME_LIMIT, CANCELLED }
  public record Options(int maxPaths, int maxPathLength, long maxTraversalStates,
                        long timeBudgetMillis, BooleanSupplier cancelled) {
    public Options {
      maxPaths = Math.max(1, Math.min(maxPaths, HARD_PATH_LIMIT));
      maxPathLength = Math.max(1, Math.min(maxPathLength, HARD_PATH_LENGTH_LIMIT));
      maxTraversalStates = Math.max(1, Math.min(maxTraversalStates, HARD_STATE_LIMIT));
      timeBudgetMillis = Math.max(1, Math.min(timeBudgetMillis, HARD_TIME_LIMIT_MS));
      cancelled = cancelled == null ? () -> false : cancelled;
    }
    public static Options defaults() { return new Options(DEFAULT_PATH_LIMIT, DEFAULT_PATH_LENGTH_LIMIT,
        DEFAULT_STATE_LIMIT, DEFAULT_TIME_LIMIT_MS, () -> false); }
  }
  public static final class EnumeratedPath {
    public final List<String> nodeIds; public final List<String> edgeLabels;
    public EnumeratedPath(List<String> nodes, List<String> edges) { nodeIds=List.copyOf(nodes); edgeLabels=List.copyOf(edges); }
    public String toPathString() { StringBuilder out=new StringBuilder(); for(int i=0;i<nodeIds.size();i++){
      out.append(nodeIds.get(i)); if(i<edgeLabels.size())out.append(" --(").append(edgeLabels.get(i)).append(")--> ");} return out.toString(); }
  }
  public record EnumerationResult(List<EnumeratedPath> paths, Status status, StopReason stopReason,
      int pathLimit, int pathLengthLimit, long traversalStateLimit, long timeLimitMillis,
      long traversalStates, int maximumPathLength, double generationTimeMs, int componentsStarted) {
    public boolean truncated() { return status != Status.COMPLETE; }
  }
  private static final class Frame { final Node node; final boolean incomingEdge; int nextEdge; boolean entered,terminal;
    Frame(Node node,boolean incomingEdge){this.node=node;this.incomingEdge=incomingEdge;} }
  private PathEnumerator() {}
  public static List<EnumeratedPath> enumerate(Node startNode) {
    return enumerate(startNode==null?List.of():List.of(startNode),Options.defaults()).paths();
  }
  public static EnumerationResult enumerate(List<Node> roots, Options options) {
    long started=System.nanoTime(),states=0; int maximumLength=0,components=0;
    List<EnumeratedPath> results=new ArrayList<>(); List<String> pathNodes=new ArrayList<>(),pathEdges=new ArrayList<>();
    Map<String,Integer> visits=new HashMap<>(); Status status=Status.COMPLETE; StopReason reason=StopReason.NONE;
    outer: for(Node root:roots==null?List.<Node>of():roots){
      if(root==null)continue; if(results.size()>=options.maxPaths()){status=Status.TRUNCATED;reason=StopReason.PATH_LIMIT;break;}
      components++; ArrayDeque<Frame> stack=new ArrayDeque<>(); stack.push(new Frame(root,false));
      while(!stack.isEmpty()){
        if(options.cancelled().getAsBoolean()){status=Status.CANCELLED;reason=StopReason.CANCELLED;break outer;}
        if(elapsedMs(started)>=options.timeBudgetMillis()){status=Status.TRUNCATED;reason=StopReason.TIME_LIMIT;break outer;}
        Frame frame=stack.peek();
        if(!frame.entered){
          if(states>=options.maxTraversalStates()){status=Status.TRUNCATED;reason=StopReason.TRAVERSAL_STATE_LIMIT;break outer;}
          states++;frame.entered=true;pathNodes.add(frame.node.id.replace('_','.'));
          int count=visits.getOrDefault(frame.node.id,0)+1;visits.put(frame.node.id,count);maximumLength=Math.max(maximumLength,pathNodes.size());
          List<Edge> outgoing=frame.node.edges==null?List.of():frame.node.edges;
          boolean natural=outgoing.isEmpty()||count>1;
          if(natural||pathNodes.size()>=options.maxPathLength()){
            results.add(new EnumeratedPath(pathNodes,pathEdges));frame.terminal=true;
            if(!natural){status=Status.TRUNCATED;reason=StopReason.PATH_LENGTH_LIMIT;}
          }
        }
        List<Edge> outgoing=frame.node.edges==null?List.of():frame.node.edges;
        if(frame.terminal||frame.nextEdge>=outgoing.size()){
          stack.pop();int count=visits.get(frame.node.id)-1;if(count==0)visits.remove(frame.node.id);else visits.put(frame.node.id,count);
          pathNodes.remove(pathNodes.size()-1);if(frame.incomingEdge)pathEdges.remove(pathEdges.size()-1);continue;
        }
        if(results.size()>=options.maxPaths()){status=Status.TRUNCATED;reason=StopReason.PATH_LIMIT;break outer;}
        Edge edge=outgoing.get(frame.nextEdge++);pathEdges.add(edge.label);stack.push(new Frame(edge.to,true));
      }
    }
    return new EnumerationResult(List.copyOf(results),status,reason,options.maxPaths(),options.maxPathLength(),
        options.maxTraversalStates(),options.timeBudgetMillis(),states,maximumLength,elapsedMs(started),components);
  }
  private static double elapsedMs(long start){return Math.round((System.nanoTime()-start)/100_000.0)/10.0;}
}
