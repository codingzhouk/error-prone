/*
 * Copyright 2012 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.errorprone;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Verify.verify;

import com.google.common.base.Throwables;
import com.google.errorprone.scanner.ErrorProneScannerTransformer;
import com.google.errorprone.scanner.Scanner;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskEvent.Kind;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.api.ClientCodeWrapper.Trusted;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Symbol.CompletionFailure;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;

import java.util.HashSet;
import java.util.Set;

/** A {@link TaskListener} that runs Error Prone over attributed compilation units. */
@Trusted
public class ErrorProneAnalyzer implements TaskListener {

  // The set of trees that have already been scanned.
  private final Set<Tree> seen = new HashSet<>();

  private final CodeTransformer transformer;
  private final ErrorProneOptions errorProneOptions;
  private final Context context;

  public ErrorProneAnalyzer(
      CodeTransformer transformer, ErrorProneOptions errorProneOptions, Context context) {
    this.transformer = checkNotNull(transformer);
    this.errorProneOptions = checkNotNull(errorProneOptions);
    this.context = checkNotNull(context);
  }

  @Override
  public void finished(TaskEvent taskEvent) {
    if (taskEvent.getKind() != Kind.ANALYZE) {
      return;
    }
    if (JavaCompiler.instance(context).errorCount() > 0) {
      return;
    }
    TreePath path = JavacTrees.instance(context).getPath(taskEvent.getTypeElement());
    if (path == null) {
      path = new TreePath(taskEvent.getCompilationUnit());
    }
    // Assert that the event is unique and scan the current tree.
    verify(seen.add(path.getLeaf()), "Duplicate FLOW event for: %s", taskEvent.getTypeElement());
    Context subContext = new SubContext(context);
    subContext.put(ErrorProneOptions.class, errorProneOptions);
    Log log = Log.instance(context);
    JCCompilationUnit compilation = (JCCompilationUnit) path.getCompilationUnit();
    DescriptionListener logReporter =
        new JavacErrorDescriptionListener(
            log, compilation.endPositions, compilation.getSourceFile());
    try {
      if (path.getLeaf().getKind() == Tree.Kind.COMPILATION_UNIT) {
        // We only get TaskEvents for compilation units if they contain no package declarations
        // (e.g. package-info.java files).  In this case it's safe to analyze the
        // CompilationUnitTree immediately.
        transformer.apply(path, subContext, logReporter);
      } else if (finishedCompilation(path.getCompilationUnit())) {
        // Otherwise this TaskEvent is for a ClassTree, and we can scan the whole
        // CompilationUnitTree once we've seen all the enclosed classes.
        transformer.apply(new TreePath(compilation), subContext, logReporter);
      }
    } catch (ErrorProneError e) {
      e.logFatalError(log);
      // let the exception propagate to javac's main, where it will cause the compilation to
      // terminate with Result.ABNORMAL
      throw e;
    } catch (CompletionFailure e) {
      // A CompletionFailure can be triggered when error-prone tries to complete a symbol
      // that isn't on the compilation classpath. This can occur when a check performs an
      // instanceof test on a symbol, which requires inspecting the transitive closure of the
      // symbol's supertypes. If javac didn't need to check the symbol's assignability
      // then a normal compilation would have succeeded, and no diagnostics will have been
      // reported yet, but we don't want to crash javac.
      log.error("proc.cant.access", e.sym, e.getDetailValue(), Throwables.getStackTraceAsString(e));
    }
  }

  /**
   * Returns true if all declarations inside the given compilation unit have been visited.
   */
  private boolean finishedCompilation(CompilationUnitTree tree) {
    OUTER:
    for (Tree decl : tree.getTypeDecls()) {
      switch (decl.getKind()) {
        case EMPTY_STATEMENT:
          // ignore ";" at the top level, which counts as an empty type decl
          continue OUTER;
        case IMPORT:
          // The spec disallows mixing imports and empty top-level declarations (";"), but
          // javac has a bug that causes it to accept empty declarations interspersed with imports:
          // http://mail.openjdk.java.net/pipermail/compiler-dev/2013-August/006968.html
          //
          // Any import declarations after the first semi are incorrectly added to the list
          // of type declarations, so we have to skip over them here.
          continue OUTER;
        default:
          break;
      }
      if (!seen.contains(decl)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Temporary shim to avoid breaking Bazel, which expects the API to look like
   * {@code ErrorProneAnalyzer.create(scanner).init(options, context);}.
   */
  public static Builder create(Scanner scanner) {
    final CodeTransformer transformer = ErrorProneScannerTransformer.create(scanner);
    return new Builder() {
      public ErrorProneAnalyzer init(Context context, ErrorProneOptions errorProneOptions) {
        return new ErrorProneAnalyzer(transformer, errorProneOptions, context);
      }
    };
  }

  /** Temporary intermediate class for building {@link ErrorProneAnalyzer}s, see {@link #create}. */
  public interface Builder {
    ErrorProneAnalyzer init(Context context, ErrorProneOptions errorProneOptions);
  }
}
