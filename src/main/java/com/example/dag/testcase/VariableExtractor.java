package com.example.dag.testcase;

import java.util.*;
import java.util.regex.*;

public class VariableExtractor {

  public static List<Map<String, String>> extract(String code) {

    List<Map<String, String>> variables = new ArrayList<>();

    // ====================================================
    // ONLY MATCH METHOD DECLARATIONS
    // ====================================================

    Pattern methodPattern = Pattern.compile(
        "(public|private|protected)?\\s*" +
            "[\\w<>\\[\\]]+\\s+" +
            "\\w+\\s*" +
            "\\(([^)]*)\\)");

    Matcher matcher = methodPattern.matcher(code);

    while (matcher.find()) {

      String params = matcher.group(2);

      if (params == null
          || params.trim().isEmpty()) {

        continue;
      }

      String[] split = params.split(",");

      for (String p : split) {

        p = p.trim();

        String[] tokens = p.split("\\s+");

        if (tokens.length >= 2) {

          String type = tokens[0];

          String name = tokens[1];

          Map<String, String> var = new HashMap<>();

          var.put("type", type);

          var.put("name", name);

          variables.add(var);
        }
      }
    }

    return variables;
  }
}
