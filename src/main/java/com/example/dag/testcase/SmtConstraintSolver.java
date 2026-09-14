package com.example.dag.testcase;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Bounded SMT-LIB translation and optional external Z3 process adapter. */
public final class SmtConstraintSolver {
  public enum Status { SAT, UNSAT, UNKNOWN, TIMEOUT, UNSUPPORTED }
  public record Variable(String name,String type) {}
  public record Constraint(String expression,boolean requiredTrue) {}
  public record Request(List<Variable> variables,List<Constraint> constraints,long timeoutMs,
                        int variableLimit,int constraintLimit,int modelLimit) {
    public Request {variables=List.copyOf(variables);constraints=List.copyOf(constraints);timeoutMs=Math.max(10,Math.min(timeoutMs,10_000));variableLimit=Math.max(1,Math.min(variableLimit,64));constraintLimit=Math.max(1,Math.min(constraintLimit,256));modelLimit=Math.max(1,Math.min(modelLimit,32));}
  }
  public record Result(Status status,Map<String,String> model,List<String> constraints,double solverTimeMs,String error) {}
  @FunctionalInterface public interface Provider { Result solve(Request request); }

  public Result solve(Request request){long start=System.nanoTime();if(Thread.currentThread().isInterrupted())return result(Status.UNKNOWN,Map.of(),List.of(),start,"Cancelled");if(request.variables().size()>request.variableLimit())return result(Status.UNSUPPORTED,Map.of(),List.of(),start,"Variable limit exceeded");if(request.constraints().size()>request.constraintLimit())return result(Status.UNSUPPORTED,Map.of(),List.of(),start,"Constraint limit exceeded");
    final Translation translation;try{translation=translate(request);}catch(UnsupportedOperationException unsupported){return result(Status.UNSUPPORTED,Map.of(),List.of(),start,unsupported.getMessage());}
    String executable=System.getProperty("cosdg.z3");if(executable==null||executable.isBlank())executable=System.getenv("COSDG_Z3");if(executable==null||executable.isBlank())executable="z3";
    Process process=null;try{process=new ProcessBuilder(executable,"-in","-smt2").redirectErrorStream(true).start();try(OutputStream output=process.getOutputStream()){output.write(translation.script.getBytes(StandardCharsets.UTF_8));}boolean done=process.waitFor(request.timeoutMs(),TimeUnit.MILLISECONDS);if(!done){process.destroyForcibly();return result(Status.TIMEOUT,Map.of(),translation.constraints,start,"Z3 timeout");}String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();if(output.startsWith("sat"))return result(Status.SAT,parseModel(output,request.variables(),request.modelLimit()),translation.constraints,start,null);if(output.startsWith("unsat"))return result(Status.UNSAT,Map.of(),translation.constraints,start,null);return result(Status.UNKNOWN,Map.of(),translation.constraints,start,output.isBlank()?"Empty Z3 response":output);}catch(IOException unavailable){return result(Status.UNSUPPORTED,Map.of(),translation.constraints,start,"Z3 unavailable: "+unavailable.getMessage());}catch(InterruptedException cancelled){Thread.currentThread().interrupt();if(process!=null)process.destroyForcibly();return result(Status.UNKNOWN,Map.of(),translation.constraints,start,"Cancelled");}}

  public String toSmtLib(Request request){return translate(request).script();}
  Translation translate(Request request){Map<String,Sort> variables=new LinkedHashMap<>();StringBuilder script=new StringBuilder("(set-option :produce-models true)\n");for(Variable variable:request.variables()){if(!variable.name().matches("[A-Za-z_$][A-Za-z0-9_$]*"))throw new UnsupportedOperationException("Unsupported variable name");Sort sort=switch(variable.type()){case "int","long"->Sort.INT;case "boolean"->Sort.BOOL;default->throw new UnsupportedOperationException("Unsupported SMT type: "+variable.type());};variables.put(variable.name(),sort);script.append("(declare-const ").append(variable.name()).append(' ').append(sort==Sort.INT?"Int":"Bool").append(")\n");}
    List<String> assertions=new ArrayList<>();for(Constraint constraint:request.constraints()){Parser parser=new Parser(constraint.expression(),variables);Expr expression=parser.parse();if(expression.sort!=Sort.BOOL)throw new UnsupportedOperationException("Constraint is not boolean: "+constraint.expression());String smt=constraint.requiredTrue()?expression.smt:"(not "+expression.smt+")";for(String safety:parser.divisorSafety)assertions.add("(not (= "+safety+" 0))");assertions.add(smt);}for(String assertion:assertions)script.append("(assert ").append(assertion).append(")\n");script.append("(check-sat)\n(get-value (");request.variables().stream().limit(request.modelLimit()).forEach(v->script.append(v.name()).append(' '));script.append("))\n");return new Translation(script.toString(),List.copyOf(assertions));}
  private Map<String,String> parseModel(String output,List<Variable> variables,int limit){Map<String,String> model=new LinkedHashMap<>();for(Variable variable:variables.stream().limit(limit).toList()){Matcher matcher=Pattern.compile("\\("+Pattern.quote(variable.name())+"\\s+((?:\\(-\\s+\\d+\\))|[^()\\s]+)\\)").matcher(output);if(matcher.find()){String value=matcher.group(1).replaceAll("\\(-\\s+(\\d+)\\)","-$1");model.put(variable.name(),value);}}return Map.copyOf(model);}
  private Result result(Status status,Map<String,String> model,List<String> constraints,long start,String error){double ms=Math.round((System.nanoTime()-start)/100_000.0)/10.0;return new Result(status,Map.copyOf(model),List.copyOf(constraints),ms,error);}
  record Translation(String script,List<String> constraints) {}
  enum Sort { INT, BOOL }
  record Expr(Sort sort,String smt) {}

  private static final class Parser {
    private static final Pattern TOKEN=Pattern.compile("\\s*(<=|>=|==|!=|&&|\\|\\||[()!+*/<>-]|\\d+|[A-Za-z_$][A-Za-z0-9_$]*)");
    private final List<String> tokens=new ArrayList<>();private final Map<String,Sort> variables;private int at;private final List<String> divisorSafety=new ArrayList<>();
    Parser(String source,Map<String,Sort> variables){this.variables=variables;Matcher matcher=TOKEN.matcher(source);int end=0;while(matcher.find()){if(!source.substring(end,matcher.start()).isBlank())throw new UnsupportedOperationException("Unsupported Java syntax: "+source.substring(end,matcher.start()));tokens.add(matcher.group(1));end=matcher.end();}if(!source.substring(end).isBlank())throw new UnsupportedOperationException("Unsupported Java syntax: "+source.substring(end));}
    Expr parse(){Expr value=or();if(at!=tokens.size())throw new UnsupportedOperationException("Unexpected token: "+tokens.get(at));return value;}
    Expr or(){Expr left=and();while(take("||")){Expr right=and();left=bool("or",left,right);}return left;}
    Expr and(){Expr left=equality();while(take("&&")){Expr right=equality();left=bool("and",left,right);}return left;}
    Expr equality(){Expr left=relation();while(peek("==")||peek("!=")){String op=next();Expr right=relation();same(left,right);left=new Expr(Sort.BOOL,(op.equals("==")?"(= ":"(not (= ")+left.smt+" "+right.smt+(op.equals("==")?")":"))"));}return left;}
    Expr relation(){Expr left=add();while(Set.of("<","<=",">",">=").contains(peek())){String op=next();Expr right=add();ints(left,right);left=new Expr(Sort.BOOL,"("+op+" "+left.smt+" "+right.smt+")");}return left;}
    Expr add(){Expr left=multiply();while(peek("+")||peek("-")){String op=next();Expr right=multiply();ints(left,right);left=new Expr(Sort.INT,"("+op+" "+left.smt+" "+right.smt+")");}return left;}
    Expr multiply(){Expr left=unary();while(peek("*")||peek("/")){String op=next();Expr right=unary();ints(left,right);if(op.equals("*"))left=new Expr(Sort.INT,"(* "+left.smt+" "+right.smt+")");else{divisorSafety.add(right.smt);String aa="(ite (>= "+left.smt+" 0) "+left.smt+" (- "+left.smt+"))",bb="(ite (>= "+right.smt+" 0) "+right.smt+" (- "+right.smt+"))",q="(div "+aa+" "+bb+")";left=new Expr(Sort.INT,"(ite (= (>= "+left.smt+" 0) (>= "+right.smt+" 0)) "+q+" (- "+q+"))");}}return left;}
    Expr unary(){if(take("!")){Expr value=unary();if(value.sort!=Sort.BOOL)throw new UnsupportedOperationException("! requires boolean");return new Expr(Sort.BOOL,"(not "+value.smt+")");}if(take("-")){Expr value=unary();if(value.sort!=Sort.INT)throw new UnsupportedOperationException("- requires numeric");return new Expr(Sort.INT,"(- "+value.smt+")");}return primary();}
    Expr primary(){if(take("(")){Expr value=or();expect(")");return value;}String token=next();if(token.matches("\\d+"))return new Expr(Sort.INT,token);if(token.equals("true")||token.equals("false"))return new Expr(Sort.BOOL,token);Sort sort=variables.get(token);if(sort==null)throw new UnsupportedOperationException("Unknown variable: "+token);return new Expr(sort,token);}
    Expr bool(String op,Expr a,Expr b){if(a.sort!=Sort.BOOL||b.sort!=Sort.BOOL)throw new UnsupportedOperationException(op+" requires booleans");return new Expr(Sort.BOOL,"("+op+" "+a.smt+" "+b.smt+")");}void ints(Expr a,Expr b){if(a.sort!=Sort.INT||b.sort!=Sort.INT)throw new UnsupportedOperationException("Numeric operands required");}void same(Expr a,Expr b){if(a.sort!=b.sort)throw new UnsupportedOperationException("Mismatched equality operands");}boolean take(String value){if(peek(value)){at++;return true;}return false;}boolean peek(String value){return at<tokens.size()&&tokens.get(at).equals(value);}String peek(){return at<tokens.size()?tokens.get(at):"";}String next(){if(at>=tokens.size())throw new UnsupportedOperationException("Unexpected end of expression");return tokens.get(at++);}void expect(String value){if(!take(value))throw new UnsupportedOperationException("Expected "+value);}
  }
}
