package dev.skidfuscator.obfuscator.transform.impl.hash;

import dev.skidfuscator.obfuscator.Skidfuscator;
import dev.skidfuscator.obfuscator.event.annotation.Listen;
import dev.skidfuscator.obfuscator.event.impl.transform.method.RunMethodTransformEvent;
import dev.skidfuscator.obfuscator.skidasm.SkidMethodNode;
import dev.skidfuscator.obfuscator.skidasm.cfg.SkidBlock;
import dev.skidfuscator.obfuscator.transform.AbstractTransformer;
import dev.skidfuscator.obfuscator.util.cfg.Variables;
import org.mapleir.ir.cfg.BasicBlock;
import org.mapleir.ir.cfg.ControlFlowGraph;
import org.mapleir.ir.code.Expr;
import org.mapleir.ir.code.Stmt;
import org.mapleir.ir.code.expr.ConstantExpr;
import org.mapleir.ir.code.expr.VarExpr;
import org.mapleir.ir.code.expr.invoke.InvocationExpr;
import org.mapleir.ir.code.expr.invoke.StaticInvocationExpr;
import org.mapleir.ir.code.expr.invoke.VirtualInvocationExpr;
import sdk.LongHashFunction;

import java.util.HashSet;

public class StringEqualsIgnoreCaseHashTranformer extends AbstractTransformer {
    public StringEqualsIgnoreCaseHashTranformer(final Skidfuscator skidfuscator) {
        super(skidfuscator, "String EqIgCase Hash");
    }

    @Listen
    void handle(final RunMethodTransformEvent event) {
        final SkidMethodNode methodNode = event.getMethodNode();

        if (methodNode.isAbstract()
                || methodNode.isInit()) {
            this.skip();
            return;
        }

        if (methodNode.node.instructions.size() > 10000) {
            this.fail();
            return;
        }

        final ControlFlowGraph cfg = methodNode.getCfg();

        if (cfg == null) {
            this.fail();
            return;
        }

        for (BasicBlock vertex : new HashSet<>(cfg.vertices())) {
            if (vertex.isFlagSet(SkidBlock.FLAG_NO_OPAQUE))
                continue;

            if (methodNode.isClinit() && this.heuristicSizeSkip(methodNode, 8.f)) {
                continue;
            }

            for (Stmt stmt : new HashSet<>(vertex)) {
                for (Expr expr : stmt.enumerateOnlyChildren()) {
                    if (expr instanceof InvocationExpr invocationExpr) {
                        final boolean isEqualsMethod =
                                invocationExpr.getOwner().equals("java/lang/String")
                                && invocationExpr.getName().equals("equalsIgnoreCase")
                                && invocationExpr.getDesc().equals("(Ljava/lang/String;)Z");

                        if (!isEqualsMethod)
                            continue;

                        System.out.println(String.format("Found %s with matching", expr));

                        final Expr[] args = invocationExpr.getArgumentExprs();
                        Expr arg0 = args[0];
                        Expr arg1 = args[1];

                        if (arg0 instanceof VarExpr var0) {
                            arg0 = Variables.getDefinition(cfg, var0);
                        }

                        if (arg1 instanceof VarExpr var1) {
                            arg1 = Variables.getDefinition(cfg, var1);
                        }

                        final boolean isArg0Constant = arg0 instanceof ConstantExpr;
                        final boolean isArg1Constant = arg1 instanceof ConstantExpr;

                        if (isArg0Constant == isArg1Constant) {
                            continue;
                        }

                        ConstantExpr constantExpr = isArg0Constant ? (ConstantExpr) arg0 : (ConstantExpr) arg1;
                        Expr otherExpr = isArg0Constant ? arg1 : arg0;

                        constantExpr.setConstant(
                                "" + LongHashFunction.xx3().hashChars(((String) constantExpr.getConstant()).toLowerCase())
                        );
                        otherExpr.getParent().overwrite(otherExpr, new StaticInvocationExpr(
                                new Expr[] { new VirtualInvocationExpr(
                                        InvocationExpr.CallType.VIRTUAL,
                                        new Expr[]{otherExpr.copy()},
                                        "java/lang/String",
                                        "toLowerCase",
                                        "()Ljava/lang/String;"
                                )},
                                "sdk/SDK",
                                "hash",
                                "(Ljava/lang/String;)Ljava/lang/String;"
                        ));
                        this.success();
                    }
                }
            }
        }
    }
}
