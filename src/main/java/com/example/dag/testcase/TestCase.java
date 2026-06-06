package com.example.dag.testcase;

import java.util.Map;

public class TestCase {

  public String type;

  public Map<String, Object> inputs;

  public String expectedException;

  public TestCase(
      String type,
      Map<String, Object> inputs,
      String expectedException) {

    this.type = type;
    this.inputs = inputs;
    this.expectedException = expectedException;
  }
}
