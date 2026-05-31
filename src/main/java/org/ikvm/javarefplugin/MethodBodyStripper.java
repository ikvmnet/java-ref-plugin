package org.ikvm.javarefplugin;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

final class MethodBodyStripper extends TreeTranslator {

    private final TreeMaker maker;
    private final Names names;
    private boolean inAnnotationType;

    MethodBodyStripper(Context context) {
        this.maker = TreeMaker.instance(context);
        this.names = Names.instance(context);
    }

    void strip(Object compilationUnit) {
        ((JCTree) compilationUnit).accept(this);
    }

    @Override
    public void visitClassDef(JCTree.JCClassDecl tree) {
        boolean previousInAnnotationType = inAnnotationType;
        inAnnotationType = (tree.mods.flags & Flags.ANNOTATION) != 0;

        super.visitClassDef(tree);

        // Rewrite static initializers to set fields to default values
        if (tree.defs != null && !inAnnotationType) {
            for (JCTree def : tree.defs) {
                if (def instanceof JCTree.JCBlock) {
                    JCTree.JCBlock block = (JCTree.JCBlock) def;
                    if ((block.flags & Flags.STATIC) != 0) {
                        // Replace with minimal field assignments
                        block.stats = generateDefaultFieldAssignments(tree);
                    }
                }
            }
        }

        inAnnotationType = previousInAnnotationType;
        result = tree;
    }

    private List<JCTree.JCStatement> generateDefaultFieldAssignments(JCTree.JCClassDecl classTree) {
        ListBuffer<JCTree.JCStatement> statements = new ListBuffer<>();

        if (classTree.defs == null) {
            return statements.toList();
        }

        // Collect all static fields
        for (JCTree def : classTree.defs) {
            if (def instanceof JCTree.JCVariableDecl) {
                JCTree.JCVariableDecl varDecl = (JCTree.JCVariableDecl) def;
                if ((varDecl.mods.flags & Flags.STATIC) != 0) {
                    // Generate assignment: field = defaultValue;
                    JCTree.JCExpression defaultValue = generateDefaultValue(varDecl.vartype);
                    JCTree.JCAssign assignment = maker.Assign(
                        maker.Ident(varDecl.name),
                        defaultValue
                    );
                    statements.append(maker.Exec(assignment));
                }
            }
        }

        return statements.toList();
    }

    private JCTree.JCExpression generateDefaultValue(JCTree.JCExpression typeExpr) {
        // For simplicity: 0 for numeric types, false for boolean, null for everything else
        String typeStr = typeExpr.toString();
        if ("int".equals(typeStr) || "byte".equals(typeStr) || "short".equals(typeStr) || "long".equals(typeStr)
            || "float".equals(typeStr) || "double".equals(typeStr)) {
            return maker.Literal(TypeTag.INT, 0);
        } else if ("boolean".equals(typeStr)) {
            return maker.Literal(TypeTag.BOOLEAN, Boolean.FALSE);
        } else if ("char".equals(typeStr)) {
            return maker.Literal(TypeTag.CHAR, 0);
        }
        // Reference type or unknown: null
        return maker.Literal(TypeTag.BOT, null);
    }

    @Override
    public void visitMethodDef(JCTree.JCMethodDecl tree) {
        super.visitMethodDef(tree);

        if (tree.body == null || inAnnotationType || shouldKeepBody(tree)) {
            result = tree;
            return;
        }

        tree.body = maker.Block(0, replacementStatements(tree));
        result = tree;
    }

    private boolean shouldKeepBody(JCTree.JCMethodDecl tree) {
        long flags = tree.mods.flags;
        return (flags & (Flags.ABSTRACT | Flags.NATIVE)) != 0;
    }

    private boolean isConstructor(JCTree.JCMethodDecl tree) {
        return tree.name == names.init;
    }

    private boolean isClassInitializer(JCTree.JCMethodDecl tree) {
        return tree.name == names.clinit;
    }


    private List<JCTree.JCStatement> replacementStatements(JCTree.JCMethodDecl tree) {
        ListBuffer<JCTree.JCStatement> statements = new ListBuffer<>();

        if (isConstructor(tree)) {
            JCTree.JCStatement constructorInvocation = findConstructorInvocation(tree.body.stats);
            if (constructorInvocation != null) {
                statements.append(constructorInvocation);
            }
        }

        statements.append(
            maker.Throw(maker.Literal(TypeTag.BOT, null))
        );

        return statements.toList();
    }

    private JCTree.JCStatement findConstructorInvocation(List<JCTree.JCStatement> statements) {
        if (statements.isEmpty()) {
            return null;
        }

        JCTree.JCStatement first = statements.head;
        if (first instanceof JCTree.JCExpressionStatement) {
            JCTree.JCExpressionStatement expressionStatement = (JCTree.JCExpressionStatement) first;
            if (!(expressionStatement.expr instanceof JCTree.JCMethodInvocation)) {
                return null;
            }

            JCTree.JCMethodInvocation invocation = (JCTree.JCMethodInvocation) expressionStatement.expr;
            Name invokedName = null;
            if (invocation.meth instanceof JCTree.JCIdent) {
                JCTree.JCIdent ident = (JCTree.JCIdent) invocation.meth;
                invokedName = ident.name;
            } else if (invocation.meth instanceof JCTree.JCFieldAccess) {
                JCTree.JCFieldAccess fieldAccess = (JCTree.JCFieldAccess) invocation.meth;
                invokedName = fieldAccess.name;
            }

            if (invokedName == names._this || invokedName == names._super) {
                return first;
            }
        }

        return null;
    }

}
