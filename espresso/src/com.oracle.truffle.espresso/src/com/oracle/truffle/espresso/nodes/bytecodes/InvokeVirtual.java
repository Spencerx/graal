/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.nodes.bytecodes;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.espresso.analysis.hierarchy.AssumptionGuardedValue;
import com.oracle.truffle.espresso.analysis.hierarchy.ClassHierarchyAssumption;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.nodes.EspressoNode;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * INVOKEVIRTUAL bytecode.
 *
 * <p>
 * The receiver must be the first element of the arguments passed to {@link #execute(Object[])}.
 * </p>
 *
 * <p>
 * (Virtual) method resolution does not perform any access checks, the caller is responsible to pass
 * a compatible receiver.
 * </p>
 *
 * <h3>Exceptions</h3>
 * <ul>
 * <li>Throws guest {@link AbstractMethodError} if the resolved method is abstract.</li>
 * </ul>
 */
@NodeInfo(shortName = "INVOKEVIRTUAL")
public abstract class InvokeVirtual extends EspressoNode {
    final Method resolutionSeed;

    InvokeVirtual(Method resolutionSeed) {
        assert resolutionSeed.getVTableIndex() >= 0;
        this.resolutionSeed = resolutionSeed;
    }

    protected abstract Object execute(Object[] args);

    @Specialization
    Object doWithNullCheck(Object[] args,
                    @Cached NullCheck nullCheck,
                    @Cached("create(resolutionSeed)") WithoutNullCheck invokeVirtual) {
        StaticObject receiver = (StaticObject) args[0];
        nullCheck.execute(receiver);
        return invokeVirtual.execute(args);
    }

    static StaticObject getReceiver(Object[] args) {
        return (StaticObject) args[0];
    }

    @ImportStatic({InvokeVirtual.class, Utils.class})
    @NodeInfo(shortName = "INVOKEVIRTUAL !nullcheck")
    public abstract static class WithoutNullCheck extends EspressoNode {
        protected static final int LIMIT = 8;

        final Method resolutionSeed;

        WithoutNullCheck(Method resolutionSeed) {
            assert resolutionSeed.getVTableIndex() >= 0;
            this.resolutionSeed = resolutionSeed;
        }

        public abstract Object execute(Object[] args);

        @ImportStatic(Utils.class)
        abstract static class LazyDirectCallNode extends EspressoNode {
            final Method.MethodVersion resolvedMethod;

            LazyDirectCallNode(Method.MethodVersion resolvedMethod) {
                this.resolvedMethod = resolvedMethod;
            }

            public abstract Object execute(Object[] args);

            @Specialization
            Object doCached(Object[] args, @Cached("createAndMaybeForceInline(resolvedMethod)") DirectCallNode directCallNode) {
                return directCallNode.call(args);
            }
        }

        protected AssumptionGuardedValue<ObjectKlass> readSingleImplementor() {
            return getContext().getClassHierarchyOracle().readSingleImplementor(resolutionSeed.getDeclaringKlass());
        }

        protected ClassHierarchyAssumption readLeafAssumption() {
            return getContext().getClassHierarchyOracle().isLeafMethod(resolutionSeed.getMethodVersion());
        }

        // The implementor assumption might be invalidated right between the assumption check and
        // the value retrieval. To ensure that the single implementor value is safe to use, check
        // that it's not null.
        @Specialization(assumptions = {"maybeSingleImplementor.hasValue()", "resolvedMethod.getRedefineAssumption()"}, guards = "implementor != null")
        Object callSingleImplementor(Object[] args,
                        @Bind("getReceiver(args)") StaticObject receiver,
                        @SuppressWarnings("unused") @Cached("readSingleImplementor()") AssumptionGuardedValue<ObjectKlass> maybeSingleImplementor,
                        @SuppressWarnings("unused") @Cached("maybeSingleImplementor.get()") ObjectKlass implementor,
                        @SuppressWarnings("unused") @Cached("methodLookup(resolutionSeed, implementor)") Method.MethodVersion resolvedMethod,
                        @Cached("create(resolvedMethod)") LazyDirectCallNode directCallNode) {
            assert args[0] == receiver;
            assert !StaticObject.isNull(receiver);
            return directCallNode.execute(args);
        }

        @Specialization(guards = {"!resolutionSeed.isAbstract()", "resolvedMethod.getMethod() == resolutionSeed"}, //
                        assumptions = { //
                                        "readLeafAssumption().getAssumption()",
                                        "resolvedMethod.getRedefineAssumption()"
                        })
        Object callLeafMethod(Object[] args,
                        @Bind("getReceiver(args)") StaticObject receiver,
                        @Cached("methodLookup(resolutionSeed, receiver.getKlass())") Method.MethodVersion resolvedMethod,
                        @Cached("create(resolvedMethod)") LazyDirectCallNode directCallNode) {
            assert args[0] == receiver;
            assert !StaticObject.isNull(receiver);
            assert resolvedMethod.getMethod() == resolutionSeed;
            return directCallNode.execute(args);
        }

        @SuppressWarnings("unused")
        @Specialization(limit = "LIMIT", //
                        replaces = {"callSingleImplementor", "callLeafMethod"}, //
                        guards = "receiver.getKlass() == cachedKlass", //
                        assumptions = "resolvedMethod.getRedefineAssumption()")
        Object callDirect(Object[] args,
                        @Bind("getReceiver(args)") StaticObject receiver,
                        @Cached("receiver.getKlass()") Klass cachedKlass,
                        @Cached("methodLookup(resolutionSeed, cachedKlass)") Method.MethodVersion resolvedMethod,
                        @Cached("createAndMaybeForceInline(resolvedMethod)") DirectCallNode directCallNode) {
            assert args[0] == receiver;
            assert !StaticObject.isNull(receiver);
            return directCallNode.call(args);
        }

        @Specialization
        @ReportPolymorphism.Megamorphic
        Object callIndirect(Object[] args,
                        @Bind Node node,
                        @Cached InlinedBranchProfile error,
                        @Cached IndirectCallNode indirectCallNode) {
            StaticObject receiver = (StaticObject) args[0];
            assert args[0] == receiver;
            assert !StaticObject.isNull(receiver);
            // vtable lookup.
            Method.MethodVersion target = genericMethodLookup(node, resolutionSeed, receiver.getKlass(), error);
            return indirectCallNode.call(target.getCallTarget(), args);
        }
    }

    static Method.MethodVersion methodLookup(Method resolutionSeed, Klass receiverKlass) {
        CompilerAsserts.neverPartOfCompilation();
        return genericMethodLookup(null, // OK for uncached branch profile.
                        resolutionSeed, receiverKlass, InlinedBranchProfile.getUncached());
    }

    static Method.MethodVersion genericMethodLookup(Node node, Method resolutionSeed, Klass receiverKlass, InlinedBranchProfile error) {
        if (resolutionSeed.isRemovedByRedefinition()) {
            /*
             * Accept a slow path once the method has been removed put method behind a boundary to
             * avoid a deopt loop.
             */
            return resolutionSeed.getContext().getClassRedefinition().handleRemovedMethod(resolutionSeed, receiverKlass).getMethodVersion();
        }
        /*
         * Surprisingly, INVOKEVIRTUAL can try to invoke interface methods, even non-default ones.
         * Good thing is, miranda methods are taken care of at vtable creation !
         */
        int vtableIndex = resolutionSeed.getVTableIndex();
        Method.MethodVersion target;
        if (receiverKlass.isArray()) {
            target = resolutionSeed.getMeta().java_lang_Object.vtableLookup(vtableIndex).getMethodVersion();
        } else {
            target = receiverKlass.vtableLookup(vtableIndex).getMethodVersion();
        }
        if (!target.getMethod().hasCode()) {
            error.enter(node);
            Meta meta = resolutionSeed.getMeta();
            throw meta.throwException(meta.java_lang_AbstractMethodError);
        }
        return target;
    }
}
