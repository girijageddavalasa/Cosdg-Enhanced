package com.example.dag.testcase;

import com.example.dag.coverage.CoverageAnalyzer;
import com.example.dag.coverage.CoverageObligationEngine;
import com.example.dag.coverage.CoverageObligationEngine.Evidence;
import com.example.dag.coverage.RuntimeCoverageSession;
import com.example.dag.runtime.RuntimeExecutionService;
import com.example.dag.server.GraphWorkspace;
import com.example.dag.server.GraphWorkspace.Snapshot;
import com.example.dag.testcase.DeterministicTestGenerationService.GenerationStatus;
import com.example.dag.testcase.DeterministicTestGenerationService.TestCaseRecord;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional bounded adapter for official EvoSuite release artifacts. */
public final class EvoSuiteCandidateProvider {
  private static final int OUTPUT_LIMIT = 1_048_576;
  private static final Pattern TYPE = Pattern.compile("\\b(?:public\\s+)?(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
  private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
  private static final Pattern TEST_METHOD = Pattern.compile("@Test(?:\\([^)]*\\))?\\s*(?:public\\s+)?void\\s+([A-Za-z_$][\\w$]*)\\s*\\(");

  public record Options(int searchBudgetSeconds, int maxClasses, int maxTests, long executionTimeoutMs) {
    public Options {
      searchBudgetSeconds=Math.max(1,Math.min(searchBudgetSeconds,60));
      maxClasses=Math.max(1,Math.min(maxClasses,100));maxTests=Math.max(1,Math.min(maxTests,500));
      executionTimeoutMs=Math.max(100,Math.min(executionTimeoutMs,30_000));
    }
    public static Options defaults(){return new Options(5,25,100,5_000);}
  }
  public record Result(boolean available,String status,int classesProcessed,int generatedTests,int retainedTests,
                       double generationMs,String message,List<TestCaseRecord> records){}

  private final Path executable;
  private final Path runtime;
  private final RuntimeExecutionService execution;

  public EvoSuiteCandidateProvider(){this(Path.of("tools","evosuite","evosuite.jar"),
      Path.of("tools","evosuite","evosuite-standalone-runtime.jar"),new RuntimeExecutionService());}
  public EvoSuiteCandidateProvider(Path executable,Path runtime,RuntimeExecutionService execution){
    this.executable=executable.toAbsolutePath().normalize();this.runtime=runtime.toAbsolutePath().normalize();this.execution=execution;
  }

  public boolean available(){return Files.isRegularFile(executable)&&Files.isRegularFile(runtime);}

  public Result generate(Snapshot snapshot,RuntimeCoverageSession session,Options options){
    if(!available())return new Result(false,"UNAVAILABLE",0,0,0,0,
        "EvoSuite JARs are not configured under tools/evosuite",List.of());
    if(snapshot==null||session==null)return new Result(true,"BLOCKED",0,0,0,0,"Graph/session is required",List.of());
    long started=System.nanoTime();Path work=null;List<TestCaseRecord> records=new ArrayList<>();int classes=0,generated=0,retained=0;
    try{
      Path base=Path.of("target","evosuite-runs").toAbsolutePath().normalize();Files.createDirectories(base);work=Files.createTempDirectory(base,"run-");
      Path compiled=Files.createDirectories(work.resolve("classes"));
      ProcessResult compilation=compileTarget(snapshot,work,compiled);
      if(compilation.timedOut||compilation.exitCode!=0)return result("UNSUPPORTED",classes,generated,retained,started,
          "EvoSuite requires Java-11-compatible source: "+brief(compilation.stderr),records);
      for(String target:targetClasses(snapshot).stream().limit(options.maxClasses()).toList()){
        if(generated>=options.maxTests())break;classes++;
        Path classDir=Files.createDirectories(work.resolve("generated-"+classes));
        Path reports=Files.createDirectories(work.resolve("reports-"+classes));
        Path evoJavaHome=evoSuiteJavaHome();
        Path evoTmp=Files.createDirectories(work.resolve("tmp"));
        List<String> command=List.of(evoJavaHome.resolve("bin").resolve(javaExecutable()).toString(),"-Xmx512m","-Djava.io.tmpdir="+evoTmp,
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED","--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED","--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
            "-jar",executable.toString(),"-class",target,
            "-projectCP",compiled.toString(),"-Dsearch_budget="+options.searchBudgetSeconds(),
            "-Dtest_dir="+classDir,"-Dreport_dir="+reports,"-Dtest_format=JUNIT4","-Dassertions=true",
            "-Dminimize=true","-Dno_runtime_dependency=true","-seed","1");
        System.out.printf("[COSDG] EVOSUITE called: class=%s budget=%ds%n",target,options.searchBudgetSeconds());
        ProcessResult generatedProcess=run(command,work,(options.searchBudgetSeconds()+25L)*1_000L,evoJavaHome);
        if(generatedProcess.timedOut||generatedProcess.exitCode!=0){
          System.out.printf("[COSDG] EVOSUITE failed: class=%s exit=%d timedOut=%s output=%s error=%s%n",target,
              generatedProcess.exitCode,generatedProcess.timedOut,brief(generatedProcess.stdout),brief(generatedProcess.stderr));continue;
        }
        List<GraphWorkspace.SourceFile> generatedSources=readJava(classDir);
        GraphWorkspace.SourceFile suite=generatedSources.stream().filter(file->file.name().endsWith("_ESTest.java")&&!file.name().contains("scaffolding")).findFirst().orElse(null);
        if(suite==null){System.out.printf("[COSDG] EVOSUITE produced no suite: class=%s files=%s output=%s error=%s%n",
            target,generatedSources.stream().map(GraphWorkspace.SourceFile::name).toList(),brief(generatedProcess.stdout),brief(generatedProcess.stderr));continue;}
        String packageName=packageName(suite.source());String suiteClass=suite.name().substring(0,suite.name().length()-5);
        Matcher methods=TEST_METHOD.matcher(suite.source());
        while(methods.find()&&generated<options.maxTests()){
          generated++;String method=methods.group(1),testId="TC_EVO_"+hash(snapshot.program()+"|"+target+"|"+method);
          String runner="__CosdgEvoRunner_"+hash(testId).substring(0,8);String qualifiedSuite=packageName.isBlank()?suiteClass:packageName+"."+suiteClass;
          String qualifiedRunner=packageName.isBlank()?runner:packageName+"."+runner;
          String runnerSource=(packageName.isBlank()?"":"package "+packageName+";\n")+
              "public class "+runner+"{public static void main(String[]a){org.junit.runner.Result r="+
              "new org.junit.runner.JUnitCore().run(org.junit.runner.Request.method("+qualifiedSuite+".class,\""+method+"\"));"+
              "if(!r.wasSuccessful()){for(org.junit.runner.notification.Failure f:r.getFailures())System.err.println(f.getTrace());"+
              "throw new AssertionError(\"EvoSuite test failed\");}}}";
          List<GraphWorkspace.SourceFile> harnesses=new ArrayList<>(generatedSources);harnesses.add(new GraphWorkspace.SourceFile(runner+".java",runnerSource));
          RuntimeExecutionService.Result run=execution.execute(snapshot,new RuntimeExecutionService.Request(qualifiedRunner,List.of(),options.executionTimeoutMs(),testId),harnesses,List.of(executable,runtime));
          Set<String> obligations=observed(snapshot,run);boolean accepted=run.compilationSuccess()&&!run.timedOut()&&"PASSED".equals(run.status())&&!obligations.isEmpty();
          GenerationStatus status=accepted?GenerationStatus.RETAINED:GenerationStatus.REJECTED;
          List<Evidence> evidence=accepted?List.of(new Evidence("RUNTIME_OBSERVED",session.id(),testId,"EVOSUITE:"+target+"#"+method,
              run.runtimeTargetMethods().stream().findFirst().orElse(null),flattenTypes(run).stream().findFirst().orElse(null))):List.of();
          TestCaseRecord record=new TestCaseRecord(testId,"EVOSUITE:"+target+"#"+method,"EVOSUITE_BROAD",CoverageObligationEngine.fingerprint(snapshot),
              List.of(),suite.source(),"EVOSUITE",List.of(),run.compilationSuccess()?"PASSED":"FAILED",run.status(),run.compileMs(),run.executionMs(),
              run.stdout(),run.stderr(),accepted,evidence,List.copyOf(obligations),1,status,accepted?null:failure(run,obligations),-1,"EvoSuite 1.2.0",null,
              run.runtimeTargetMethods().stream().findFirst().orElse(null),session.id(),obligations,run.visitedNodes(),run.visitedEdges(),flattenTypes(run),
              run.runtimeTargetMethods(),"EVOSUITE_REGRESSION_ASSERTIONS",false,"NOT_REPLAYED",generatedSources.size()+1,null);
          System.out.printf("[COSDG] EVOSUITE validation: test=%s compile=%s execution=%s obligations=%d reason=%s%n",
              testId,run.compilationSuccess(),run.status(),obligations.size(),accepted?"retained":brief(failure(run,obligations)));
          records.add(record);if(accepted){session.add(testId,run);retained++;}
        }
        if(generated==0)System.out.printf("[COSDG] EVOSUITE suite had no recognized @Test methods: class=%s source=%s%n",target,brief(suite.source()));
      }
      return result("COMPLETED",classes,generated,retained,started,"EvoSuite candidate generation completed",records);
    }catch(Exception failed){return result("FAILED",classes,generated,retained,started,brief(failed.getMessage()),records);}
    finally{if(work!=null)deleteTree(work);}
  }

  private ProcessResult compileTarget(Snapshot snapshot,Path work,Path classes)throws IOException{
    List<String> command=new ArrayList<>(List.of(javaTool("javac"),"--release","11","-proc:none","-encoding","UTF-8","-d",classes.toString()));int index=0;
    for(GraphWorkspace.SourceFile file:snapshot.sources()){
      String declared=declaredType(file.source());Path source=work.resolve((declared==null?"CosdgSource"+(++index):declared)+".java");Files.writeString(source,file.source(),StandardCharsets.UTF_8);command.add(source.toString());
    }
    return run(command,work,30_000);
  }
  private List<String> targetClasses(Snapshot snapshot){LinkedHashSet<String> classes=new LinkedHashSet<>();snapshot.graph().nodes().stream().filter(node->"CLASS_ENTRY".equals(node.type())).forEach(node->{String pkg=snapshot.sources().stream().filter(f->Objects.equals(f.name(),node.file())).map(f->packageName(f.source())).findFirst().orElse("");classes.add(pkg.isBlank()?node.classId():pkg+"."+node.classId());});return List.copyOf(classes);}
  private Set<String> observed(Snapshot snapshot,RuntimeExecutionService.Result run){if(run==null||!run.compilationSuccess())return Set.of();CoverageAnalyzer.CoverageMarking marking=new CoverageAnalyzer.CoverageMarking(run.visitedNodes(),run.visitedEdges(),run.taggedClassMemberEdges(),run.exceptionTypes(),true,Set.of("statement","method","polymorphic","throw","catch","exceptionType","exceptionFlow"));LinkedHashSet<String> ids=new LinkedHashSet<>();new CoverageObligationEngine().build(snapshot,marking,null).obligations().stream().filter(o->o.status()==CoverageObligationEngine.Status.COVERED).forEach(o->ids.add(o.id()));return Set.copyOf(ids);}
  private Set<String> flattenTypes(RuntimeExecutionService.Result run){LinkedHashSet<String> types=new LinkedHashSet<>();run.exceptionTypes().values().forEach(types::addAll);return Set.copyOf(types);}
  private List<GraphWorkspace.SourceFile> readJava(Path root)throws IOException{List<GraphWorkspace.SourceFile> files=new ArrayList<>();try(var paths=Files.walk(root)){for(Path path:paths.filter(p->p.toString().endsWith(".java")).sorted().toList())files.add(new GraphWorkspace.SourceFile(path.getFileName().toString(),Files.readString(path)));}return files;}
  private String failure(RuntimeExecutionService.Result run,Set<String> obligations){if(!run.compilationSuccess())return"Compilation failed: "+brief(run.stderr());if(run.timedOut())return"Execution timed out";if(!"PASSED".equals(run.status()))return"JUnit execution failed: "+brief(run.stderr());if(obligations.isEmpty())return"No runtime-observed COSDG/ACSD obligation";return null;}
  private Result result(String status,int classes,int generated,int retained,long started,String message,List<TestCaseRecord> records){double ms=Math.round((System.nanoTime()-started)/100_000.0)/10.0;return new Result(true,status,classes,generated,retained,ms,message,List.copyOf(records));}
  private static ProcessResult run(List<String> command,Path directory,long timeoutMs)throws IOException{return run(command,directory,timeoutMs,null);}
  private static ProcessResult run(List<String> command,Path directory,long timeoutMs,Path javaHome)throws IOException{ProcessBuilder builder=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(false);if(javaHome!=null){builder.environment().put("JAVA_HOME",javaHome.toString());String inherited=builder.environment().getOrDefault("JAVA_TOOL_OPTIONS","");builder.environment().put("JAVA_TOOL_OPTIONS",inherited+" --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED");}Process process=builder.start();CompletableFuture<String> out=CompletableFuture.supplyAsync(()->read(process.getInputStream())),err=CompletableFuture.supplyAsync(()->read(process.getErrorStream()));boolean done;try{done=process.waitFor(timeoutMs,TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();done=false;}if(!done){process.descendants().forEach(child->child.destroyForcibly());process.destroyForcibly();try{process.waitFor(2,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}return new ProcessResult(done?process.exitValue():-1,!done,out.join(),err.join());}
  private static String read(InputStream stream){try(stream;ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] b=new byte[8192];int total=0,n;while((n=stream.read(b))>=0){int accepted=Math.min(n,OUTPUT_LIMIT-total);if(accepted>0)bytes.write(b,0,accepted);total+=accepted;}return bytes.toString(StandardCharsets.UTF_8);}catch(IOException e){return e.getMessage();}}
  private static String declaredType(String source){Matcher matcher=TYPE.matcher(source);return matcher.find()?matcher.group(1):null;}
  private static String packageName(String source){Matcher matcher=PACKAGE.matcher(source);return matcher.find()?matcher.group(1):"";}
  private static String javaTool(String name){String suffix=System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?".exe":"";return Path.of(System.getProperty("java.home"),"bin",name+suffix).toString();}
  private static String javaExecutable(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java";}
  private static Path evoSuiteJavaHome(){String configured=System.getenv("EVOSUITE_JAVA_HOME");if(validJavaHome(configured==null||configured.isBlank()?null:Path.of(configured)))return Path.of(configured);Path current=Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();List<Path> roots=new ArrayList<>();if(current.getParent()!=null)roots.add(current.getParent());String local=System.getenv("LOCALAPPDATA"),programFiles=System.getenv("ProgramFiles");if(local!=null)roots.add(Path.of(local,"Programs","Eclipse Adoptium"));if(programFiles!=null){roots.add(Path.of(programFiles,"Eclipse Adoptium"));roots.add(Path.of(programFiles,"Java"));}for(Path root:roots)if(Files.isDirectory(root))try(var candidates=Files.list(root)){var jdk11=candidates.filter(Files::isDirectory).filter(path->path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("jdk-11")).filter(EvoSuiteCandidateProvider::validJavaHome).sorted().findFirst();if(jdk11.isPresent())return jdk11.get();}catch(IOException ignored){}return current;}
  private static boolean validJavaHome(Path home){return home!=null&&Files.isRegularFile(home.resolve("bin").resolve(javaExecutable()));}
  private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0,16);}catch(Exception e){throw new IllegalStateException(e);}}
  private static String brief(String value){if(value==null||value.isBlank())return"unknown error";String one=value.replaceAll("\\s+"," ").trim();return one.length()>1200?one.substring(0,1200)+"…":one;}
  private static void deleteTree(Path root){try(var paths=Files.walk(root)){paths.sorted((a,b)->b.compareTo(a)).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});}catch(IOException ignored){}}
  private record ProcessResult(int exitCode,boolean timedOut,String stdout,String stderr){}
}
