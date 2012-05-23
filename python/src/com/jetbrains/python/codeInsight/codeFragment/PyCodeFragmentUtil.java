package com.jetbrains.python.codeInsight.codeFragment;

import com.intellij.codeInsight.codeFragment.CannotCreateCodeFragmentException;
import com.intellij.codeInsight.codeFragment.CodeFragmentUtil;
import com.intellij.codeInsight.codeFragment.Position;
import com.intellij.codeInsight.controlflow.ControlFlow;
import com.intellij.codeInsight.controlflow.Instruction;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.PyBundle;
import com.jetbrains.python.codeInsight.controlflow.ControlFlowCache;
import com.jetbrains.python.codeInsight.controlflow.ReadWriteInstruction;
import com.jetbrains.python.codeInsight.controlflow.ScopeOwner;
import com.jetbrains.python.codeInsight.dataflow.scope.Scope;
import com.jetbrains.python.codeInsight.dataflow.scope.ScopeUtil;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyForStatementNavigator;
import com.jetbrains.python.psi.impl.PyPsiUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author oleg
 */
public class PyCodeFragmentUtil {
  private PyCodeFragmentUtil() {
  }

  @NotNull
  public static PyCodeFragment createCodeFragment(@NotNull final ScopeOwner owner,
                                                  @NotNull final PsiElement startInScope,
                                                  @NotNull final PsiElement endInScope) throws CannotCreateCodeFragmentException {
    final int start = startInScope.getTextOffset();
    final int end = endInScope.getTextOffset() + endInScope.getTextLength();
    final ControlFlow flow = ControlFlowCache.getControlFlow(owner);
    if (flow == null) {
      throw new CannotCreateCodeFragmentException("Cannot determine execution flow for the code fragment");
    }
    final List<Instruction> graph = Arrays.asList(flow.getInstructions());
    final List<Instruction> subGraph = getFragmentSubGraph(graph, start, end);
    final AnalysisResult subGraphAnalysis = analyseSubGraph(subGraph, start, end);
    if ((subGraphAnalysis.regularExits > 0 && subGraphAnalysis.returns > 0) ||
        subGraphAnalysis.targetInstructions > 1 ||
        subGraphAnalysis.outerLoopBreaks > 0) {
      throw new CannotCreateCodeFragmentException(
        PyBundle.message("refactoring.extract.method.error.cannot.perform.refactoring.when.execution.flow.is.interrupted"));
    }
    if (subGraphAnalysis.starImports > 0) {
      throw new CannotCreateCodeFragmentException(
        PyBundle.message("refactoring.extract.method.error.cannot.perform.refactoring.when.from.import.inside"));
    }

    final Set<String> globalWrites = getGlobalWrites(subGraph, owner);

    final Set<String> inputNames = new HashSet<String>();
    for (PsiElement element : filterElementsInScope(getInputElements(subGraph, graph), owner)) {
      final String name = getName(element);
      if (name != null) {
        // Ignore "self", it is generated automatically when extracting any method fragment
        if (PyPsiUtils.isMethodContext(element) && "self".equals(name)) {
          continue;
        }
        if (globalWrites.contains(name)) {
          continue;
        }
        inputNames.add(name);
      }
    }

    final Set<String> outputNames = new HashSet<String>();
    for (PsiElement element : getOutputElements(subGraph, graph)) {
      final String name = getName(element);
      if (name != null) {
        outputNames.add(name);
        globalWrites.remove(name);
      }
    }

    return new PyCodeFragment(inputNames, outputNames, globalWrites, subGraphAnalysis.returns > 0);
  }

  @Nullable
  private static String getName(@NotNull PsiElement element) {
    if (element instanceof PsiNamedElement) {
      return ((PsiNamedElement)element).getName();
    }
    else if (element instanceof PyImportElement) {
      return ((PyImportElement)element).getVisibleName();
    }
    else if (element instanceof PyElement) {
      return ((PyElement)element).getName();
    }
    return null;
  }

  @NotNull
  private static List<Instruction> getFragmentSubGraph(@NotNull List<Instruction> graph, int start, int end) {
    List<Instruction> instructions = new ArrayList<Instruction>();
    for (Instruction instruction : graph) {
      final PsiElement element = instruction.getElement();
      if (element != null) {
        if (CodeFragmentUtil.getPosition(element, start, end) == Position.INSIDE) {
          instructions.add(instruction);
        }
      }
    }
    // Hack for including inner assert type instructions that can point to elements outside of the selected scope
    for (Instruction instruction : graph) {
      if (instruction instanceof ReadWriteInstruction) {
        final ReadWriteInstruction readWriteInstruction = (ReadWriteInstruction)instruction;
        if (readWriteInstruction.getAccess().isAssertTypeAccess()) {
          boolean innerAssertType = true;
          for (Instruction next : readWriteInstruction.allSucc()) {
            if (!instructions.contains(next)) {
              innerAssertType = false;
              break;
            }
          }
          if (innerAssertType && !instructions.contains(instruction)) {
            instructions.add(instruction);
          }
        }
      }
    }
    return instructions;
  }

  private static class AnalysisResult {
    private final int starImports;
    private final int targetInstructions;
    private final int regularExits;
    private final int returns;
    private final int outerLoopBreaks;

    public AnalysisResult(int starImports, int targetInstructions, int returns, int regularExits, int outerLoopBreaks) {
      this.starImports = starImports;
      this.targetInstructions = targetInstructions;
      this.regularExits = regularExits;
      this.returns = returns;
      this.outerLoopBreaks = outerLoopBreaks;
    }
  }

  @NotNull
  private static AnalysisResult analyseSubGraph(@NotNull List<Instruction> subGraph, int start, int end) {
    int returnSources = 0;
    int regularSources = 0;
    final Set<Instruction> targetInstructions = new HashSet<Instruction>();
    int starImports = 0;
    int outerLoopBreaks = 0;

    for (Pair<Instruction, Instruction> edge : getOutgoingEdges(subGraph)) {
      final Instruction sourceInstruction = edge.getFirst();
      final Instruction targetInstruction = edge.getSecond();
      final PsiElement source = sourceInstruction.getElement();
      final PsiElement target = targetInstruction.getElement();

      final PyReturnStatement returnStatement = PsiTreeUtil.getParentOfType(source, PyReturnStatement.class, false);
      final boolean isExceptTarget = target instanceof PyExceptPart || target instanceof PyFinallyPart;
      final boolean isLoopTarget = target instanceof PyWhileStatement || PyForStatementNavigator.getPyForStatementByIterable(target) != null;

      if (target != null && !isExceptTarget && !isLoopTarget) {
        targetInstructions.add(targetInstruction);
      }

      if (returnStatement != null && CodeFragmentUtil.getPosition(returnStatement, start, end) == Position.INSIDE) {
        returnSources++;
      }
      else if (!isExceptTarget) {
        regularSources++;
      }
    }

    final Set<PsiElement> subGraphElements = getSubGraphElements(subGraph);
    for (PsiElement element : subGraphElements) {
      if (element instanceof PyFromImportStatement) {
        final PyFromImportStatement fromImportStatement = (PyFromImportStatement)element;
        if (fromImportStatement.getStarImportElement() != null) {
          starImports++;
        }
      }
      if (element instanceof PyContinueStatement || element instanceof PyBreakStatement) {
        final PyLoopStatement loopStatement = PsiTreeUtil.getParentOfType(element, PyLoopStatement.class);
        if (loopStatement != null && !subGraphElements.contains(loopStatement)) {
          outerLoopBreaks++;
        }
      }
    }

    return new AnalysisResult(starImports, targetInstructions.size(), returnSources, regularSources, outerLoopBreaks);
  }

  @NotNull
  private static Set<Pair<Instruction, Instruction>> getOutgoingEdges(@NotNull Collection<Instruction> subGraph) {
    final Set<Pair<Instruction, Instruction>> outgoing = new HashSet<Pair<Instruction, Instruction>>();
    for (Instruction instruction : subGraph) {
      for (Instruction next : instruction.allSucc()) {
        if (!subGraph.contains(next)) {
          outgoing.add(Pair.create(instruction, next));
        }
      }
    }
    return outgoing;
  }

  @NotNull
  private static List<PsiElement> getInputElements(@NotNull List<Instruction> subGraph, @NotNull List<Instruction> graph) {
    final List<PsiElement> result = new ArrayList<PsiElement>();
    final Set<PsiElement> subGraphElements = getSubGraphElements(subGraph);
    for (Instruction instruction : getReadInstructions(subGraph)) {
      final PsiElement element = instruction.getElement();
      if (element != null) {
        final PsiReference reference = element.getReference();
        if (reference != null) {
          for (PsiElement resolved : multiResolve(reference)) {
            if (!subGraphElements.contains(resolved)) {
              result.add(element);
            }
          }
        }
      }
    }
    final List<PsiElement> outputElements = getOutputElements(subGraph, graph);
    for (Instruction instruction : getWriteInstructions(subGraph)) {
      final PsiElement element = instruction.getElement();
      if (element != null) {
        final PsiReference reference = element.getReference();
        if (reference != null) {
          for (PsiElement resolved : multiResolve(reference)) {
            if (!subGraphElements.contains(resolved) && outputElements.contains(element)) {
              result.add(element);
            }
          }
        }
      }
    }
    return result;
  }

  @NotNull
  private static List<PsiElement> getOutputElements(@NotNull List<Instruction> subGraph, @NotNull List<Instruction> graph) {
    final List<PsiElement> result = new ArrayList<PsiElement>();
    final List<Instruction> outerGraph = new ArrayList<Instruction>();
    for (Instruction instruction : graph) {
      if (!subGraph.contains(instruction)) {
        outerGraph.add(instruction);
      }
    }
    final Set<PsiElement> subGraphElements = getSubGraphElements(subGraph);
    for (Instruction instruction : getReadInstructions(outerGraph)) {
      final PsiElement element = instruction.getElement();
      if (element != null) {
        final PsiReference reference = element.getReference();
        if (reference != null) {
          for (PsiElement resolved : multiResolve(reference)) {
            if (subGraphElements.contains(resolved)) {
              result.add(resolved);
            }
          }
        }
      }
    }
    return result;
  }

  private static List<PsiElement> filterElementsInScope(@NotNull Collection<PsiElement> elements, @NotNull ScopeOwner owner) {
    final List<PsiElement> result = new ArrayList<PsiElement>();
    for (PsiElement element : elements) {
      final PsiReference reference = element.getReference();
      if (reference != null) {
        final PsiElement resolved = reference.resolve();
        if (resolved != null && ScopeUtil.getScopeOwner(resolved) == owner && !(owner instanceof PsiFile)) {
          result.add(element);
        }
      }
    }
    return result;
  }

  private static Set<PsiElement> getSubGraphElements(@NotNull List<Instruction> subGraph) {
    final Set<PsiElement> result = new HashSet<PsiElement>();
    for (Instruction instruction : subGraph) {
      final PsiElement element = instruction.getElement();
      if (element != null) {
        result.add(element);
      }
    }
    return result;
  }

  @NotNull
  private static Set<String> getGlobalWrites(@NotNull List<Instruction> instructions, @NotNull ScopeOwner owner) {
    final Scope scope = ControlFlowCache.getScope(owner);
    final Set<String> globalWrites = new LinkedHashSet<String>();
    for (Instruction instruction : getWriteInstructions(instructions)) {
      if (instruction instanceof ReadWriteInstruction) {
        final String name = ((ReadWriteInstruction)instruction).getName();
        if (scope.isGlobal(name) || owner instanceof PsiFile) {
          globalWrites.add(name);
        }
      }
    }
    return globalWrites;
  }

  @NotNull
  private static List<PsiElement> multiResolve(@NotNull PsiReference reference) {
    if (reference instanceof PsiPolyVariantReference) {
      final ResolveResult[] results = ((PsiPolyVariantReference)reference).multiResolve(false);
      final List<PsiElement> resolved = new ArrayList<PsiElement>();
      for (ResolveResult result : results) {
        final PsiElement element = result.getElement();
        if (element != null) {
          resolved.add(element);
        }
      }
      for (PsiElement element : resolved) {
        if (element instanceof PyClass) {
          return Collections.singletonList(element);
        }
      }
      return resolved;
    }
    final PsiElement element = reference.resolve();
    if (element != null) {
      return Collections.singletonList(element);
    }
    return Collections.emptyList();
  }

  private static List<Instruction> getReadInstructions(@NotNull List<Instruction> subGraph) {
    final List<Instruction> result = new ArrayList<Instruction>();
    for (Instruction instruction : subGraph) {
      if (instruction instanceof ReadWriteInstruction) {
        final ReadWriteInstruction readWriteInstruction = (ReadWriteInstruction)instruction;
        if (readWriteInstruction.getAccess().isReadAccess()) {
          result.add(readWriteInstruction);
        }
      }
    }
    return result;
  }

  private static List<Instruction> getWriteInstructions(@NotNull List<Instruction> subGraph) {
    final List<Instruction> result = new ArrayList<Instruction>();
    for (Instruction instruction : subGraph) {
      if (instruction instanceof ReadWriteInstruction) {
        final ReadWriteInstruction readWriteInstruction = (ReadWriteInstruction)instruction;
        if (readWriteInstruction.getAccess().isWriteAccess()) {
          result.add(readWriteInstruction);
        }
      }
    }
    return result;
  }
}