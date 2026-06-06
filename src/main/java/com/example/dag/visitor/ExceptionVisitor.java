package com.example.dag.visitor;

import com.example.dag.DAGBuilder;
import com.example.dag.graph.Node;
import com.example.dag.JavaParser;
import com.example.dag.JavaParserBaseVisitor;

import org.antlr.v4.runtime.Token;
import java.util.Stack;
import java.util.List;

public class ExceptionVisitor extends JavaParserBaseVisitor<Node> {

  private DAGBuilder builder;
  private Node lastNode = null;
  private Node startNode = null;

  private Stack<List<Node>> currentCatchNodesList = new Stack<>();

  public ExceptionVisitor(DAGBuilder builder, Object ignored) {
    this.builder = builder;
  }

  public Node getStartNode() {
    return startNode;
  }

  private Node appendNode(String type, String label, Token start) {
    Node n = builder.createNode(type);
    if (startNode == null) {
      startNode = n;
    }
    if (lastNode != null) {
      builder.connect(lastNode, n, label);
    }
    lastNode = n;
    return n;
  }

  @Override
  public Node visitStatement(JavaParser.StatementContext ctx) {

    // ========================================================
    // THROW STATEMENT HANDLER (Captures true Covet data dependencies)
    // ========================================================
    if (ctx.THROW() != null) {
      Node throwStmtNode = appendNode("THROW_STMT", "normal", ctx.start);

      if (!currentCatchNodesList.isEmpty()) {
        List<Node> innerCatchNodes = currentCatchNodesList.peek();
        for (Node catchNode : innerCatchNodes) {
          builder.connect(throwStmtNode, catchNode, "exception");
        }
      }
      return throwStmtNode;
    }

    // ========================================================
    // TRY-CATCH-FINALLY
    // ========================================================
    if (ctx.TRY() != null) {
      Node tryEnter = appendNode("TRY_BLOCK_START", "normal", ctx.start);
      java.util.List<Node> catchNodes = new java.util.ArrayList<>();

      if (ctx.catchClause() != null && !ctx.catchClause().isEmpty()) {
        for (JavaParser.CatchClauseContext catchCtx : ctx.catchClause()) {
          String exceptionTypeName = "Exception";
          if (catchCtx.catchType() != null) {
            exceptionTypeName = catchCtx.catchType().getText().replace("|", "_OR_");
          }
          Node catchNode = builder.createCatchNode("CATCH_START", exceptionTypeName);
          catchNodes.add(catchNode);
        }
      }

      currentCatchNodesList.push(catchNodes);

      if (ctx.block() != null) {
        visit(ctx.block());
      }

      currentCatchNodesList.pop();

      Node tryEndPathNode = lastNode;
      Node catchEndPathNode = null;

      if (ctx.catchClause() != null && !ctx.catchClause().isEmpty()) {
        int idx = 0;
        for (JavaParser.CatchClauseContext catchCtx : ctx.catchClause()) {
          Node catchStartNode = catchNodes.get(idx++);
          lastNode = catchStartNode;
          visit(catchCtx.block());
          catchEndPathNode = lastNode;
        }
      }

      Node finallyEnter = builder.createNode("FINALLY_START");

      if (tryEndPathNode != null) {
        builder.connect(tryEndPathNode, finallyEnter, "normal");
      }
      if (catchEndPathNode != null) {
        builder.connect(catchEndPathNode, finallyEnter, "normal");
      }

      lastNode = finallyEnter;

      if (ctx.finallyBlock() != null) {
        visit(ctx.finallyBlock().block());
      }

      return finallyEnter;
    }

    // ========================================================
    // IF-ELSE
    // ========================================================
    else if (ctx.IF() != null) {
      Node ifNode = appendNode("IF_EVAL", "normal", ctx.start);
      visit(ctx.statement(0));
      Node thenEndNode = lastNode;
      Node elseEndNode = null;

      if (ctx.statement().size() > 1 && ctx.statement(1) != null) {
        lastNode = ifNode;
        visit(ctx.statement(1));
        elseEndNode = lastNode;
      }

      Node mergeNode = builder.createNode("END_IF");

      if (thenEndNode != null) {
        builder.connect(thenEndNode, mergeNode, "normal");
      }
      if (elseEndNode != null) {
        builder.connect(elseEndNode, mergeNode, "normal");
      } else {
        builder.connect(ifNode, mergeNode, "false");
      }

      lastNode = mergeNode;
      return mergeNode;
    }

    // ========================================================
    // GENERIC STATEMENT CALL
    // ========================================================
    Node stmt = appendNode("STMT", "normal", ctx.start);

    if (!currentCatchNodesList.isEmpty()) {
      List<Node> innerCatchNodes = currentCatchNodesList.peek();
      for (Node catchNode : innerCatchNodes) {
        builder.connect(stmt, catchNode, "exception");
      }
    }

    return stmt;
  }

  @Override
  public Node visitBlockStatement(JavaParser.BlockStatementContext ctx) {
    if (ctx.localVariableDeclaration() != null) {
      Node stmt = appendNode("VAR_DECL", "normal", ctx.start);
      if (!currentCatchNodesList.isEmpty()) {
        List<Node> innerCatchNodes = currentCatchNodesList.peek();
        for (Node catchNode : innerCatchNodes) {
          builder.connect(stmt, catchNode, "exception");
        }
      }
      return stmt;
    } else if (ctx.statement() != null) {
      return visit(ctx.statement());
    }
    return visitChildren(ctx);
  }
}