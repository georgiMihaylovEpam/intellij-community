/*
 * Copyright 2003-2009 Dave Griffith, Bas Leijdekkers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.siyeh.ig.psiutils;

import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiBinaryExpression;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiJavaToken;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ComparisonUtils {

    private ComparisonUtils() {
        super();
    }

    private static final Set<IElementType> s_comparisonTokens =
            new HashSet<IElementType>(6);

    private static final Map<IElementType,String> s_swappedComparisons =
            new HashMap<IElementType, String>(6);

    private static final Map<IElementType,String> s_invertedComparisons =
            new HashMap<IElementType, String>(6);


    static {
        s_comparisonTokens.add(JavaTokenType.EQEQ);
        s_comparisonTokens.add(JavaTokenType.NE);
        s_comparisonTokens.add(JavaTokenType.GT);
        s_comparisonTokens.add(JavaTokenType.LT);
        s_comparisonTokens.add(JavaTokenType.GE);
        s_comparisonTokens.add(JavaTokenType.LE);

        s_swappedComparisons.put(JavaTokenType.EQEQ, "==");
        s_swappedComparisons.put(JavaTokenType.NE, "!=");
        s_swappedComparisons.put(JavaTokenType.GT, "<");
        s_swappedComparisons.put(JavaTokenType.LT, ">");
        s_swappedComparisons.put(JavaTokenType.GE, "<=");
        s_swappedComparisons.put(JavaTokenType.LE, ">=");

        s_invertedComparisons.put(JavaTokenType.EQEQ, "!=");
        s_invertedComparisons.put(JavaTokenType.NE, "==");
        s_invertedComparisons.put(JavaTokenType.GT, "<=");
        s_invertedComparisons.put(JavaTokenType.LT, ">=");
        s_invertedComparisons.put(JavaTokenType.GE, "<");
        s_invertedComparisons.put(JavaTokenType.LE, ">");

    }

    public static boolean isComparison(@Nullable PsiExpression expression) {
        if(!(expression instanceof PsiBinaryExpression)) {
            return false;
        }
        final PsiBinaryExpression binaryExpression =
                (PsiBinaryExpression) expression;
        final PsiJavaToken sign = binaryExpression.getOperationSign();
        final IElementType tokenType = sign.getTokenType();
        return isComparisonOperation(tokenType);
    }

    public static boolean isComparisonOperation(IElementType tokenType) {
        return s_comparisonTokens.contains(tokenType);
    }

    public static String getFlippedComparison(@NotNull PsiJavaToken token) {
        final IElementType tokenType = token.getTokenType();
        return s_swappedComparisons.get(tokenType);
    }

    public static boolean isEqualityComparison(
            @NotNull PsiBinaryExpression expression) {
        final IElementType tokenType = expression.getOperationTokenType();
        return tokenType.equals(JavaTokenType.EQEQ) ||
               tokenType.equals(JavaTokenType.NE);
    }

    public static String getNegatedComparison(@NotNull PsiJavaToken token) {
        final IElementType tokenType = token.getTokenType();
        return s_invertedComparisons.get(tokenType);
    }
}
