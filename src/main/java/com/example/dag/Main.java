package com.example.dag;

import com.example.dag.server.ApiServer;
import com.example.dag.server.GraphWorkspace;
import com.example.dag.server.GraphWorkspace.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    Path sourcePath = Path.of(args.length > 0 ? args[0] : "input_code.txt");
    if (!Files.exists(sourcePath) && Files.exists(Path.of("code.txt"))) sourcePath = Path.of("code.txt");
    sourcePath = sourcePath.toAbsolutePath().normalize();
    GraphWorkspace workspace = new GraphWorkspace(List.of(
        new SourceFile(sourcePath.getFileName().toString(), Files.readString(sourcePath))));
    GraphWorkspace.Statistics stats = workspace.current().statistics();
    System.out.printf("COSDG/ACSD ready: %,d statements, %,d nodes, %,d edges%n",
        stats.statements(), stats.nodes(), stats.edges());
    ApiServer.start(workspace);
  }
}
