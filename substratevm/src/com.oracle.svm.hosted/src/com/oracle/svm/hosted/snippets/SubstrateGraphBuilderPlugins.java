/*
 * Copyright (c) 2012, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.snippets;

import java.lang.reflect.Array;
import java.util.Arrays;

import org.graalvm.compiler.api.replacements.SnippetReflectionProvider;
import org.graalvm.compiler.bytecode.BytecodeProvider;
import org.graalvm.compiler.core.common.type.IntegerStamp;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.core.common.type.StampPair;
import org.graalvm.compiler.core.common.type.TypeReference;
import org.graalvm.compiler.graph.Edges;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.NodeList;
import org.graalvm.compiler.nodes.CallTargetNode.InvokeKind;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.DynamicPiNode;
import org.graalvm.compiler.nodes.FixedGuardNode;
import org.graalvm.compiler.nodes.LogicNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.calc.ConditionalNode;
import org.graalvm.compiler.nodes.extended.FixedValueAnchorNode;
import org.graalvm.compiler.nodes.extended.GetClassNode;
import org.graalvm.compiler.nodes.extended.LoadHubNode;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugin;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugin.Receiver;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugins;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugins.Registration;
import org.graalvm.compiler.nodes.java.ArrayLengthNode;
import org.graalvm.compiler.nodes.java.InstanceOfDynamicNode;
import org.graalvm.compiler.replacements.nodes.BasicObjectCloneNode;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.UnsignedWord;

import com.oracle.graal.pointsto.nodes.AnalysisArraysCopyOfNode;
import com.oracle.graal.pointsto.nodes.AnalysisUnsafePartitionLoadNode;
import com.oracle.graal.pointsto.nodes.AnalysisUnsafePartitionStoreNode;
import com.oracle.graal.pointsto.nodes.ConvertUnknownValueNode;
import com.oracle.svm.core.graal.jdk.SubstrateArraysCopyOfNode;
import com.oracle.svm.core.graal.jdk.SubstrateObjectCloneNode;
import com.oracle.svm.core.graal.nodes.FarReturnNode;
import com.oracle.svm.core.graal.nodes.FormatArrayNode;
import com.oracle.svm.core.graal.nodes.FormatObjectNode;
import com.oracle.svm.core.graal.nodes.NewPinnedArrayNode;
import com.oracle.svm.core.graal.nodes.NewPinnedInstanceNode;
import com.oracle.svm.core.graal.nodes.ReadCallerStackPointerNode;
import com.oracle.svm.core.graal.nodes.ReadInstructionPointerNode;
import com.oracle.svm.core.graal.nodes.ReadRegisterFixedNode;
import com.oracle.svm.core.graal.nodes.ReadReturnAddressNode;
import com.oracle.svm.core.graal.nodes.ReadStackPointerNode;
import com.oracle.svm.core.graal.nodes.SubstrateDynamicNewArrayNode;
import com.oracle.svm.core.graal.nodes.SubstrateDynamicNewInstanceNode;
import com.oracle.svm.core.graal.nodes.TestDeoptimizeNode;
import com.oracle.svm.core.graal.nodes.WriteStackPointerNode;
import com.oracle.svm.core.graal.stackvalue.StackValueNode;
import com.oracle.svm.core.graal.stackvalue.StackValueNode.StackSlotIdentity;
import com.oracle.svm.core.heap.PinnedAllocator;
import com.oracle.svm.core.meta.SharedMethod;
import com.oracle.svm.core.snippets.KnownIntrinsics;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.hosted.GraalEdgeUnsafePartition;

import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.Local;
import jdk.vm.ci.meta.LocalVariableTable;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import sun.misc.Unsafe;

public class SubstrateGraphBuilderPlugins {
    public static void registerInvocationPlugins(MetaAccessProvider metaAccess, ConstantReflectionProvider constantReflection,
                    SnippetReflectionProvider snippetReflection, InvocationPlugins plugins, BytecodeProvider bytecodeProvider, boolean analysis) {

        // register the substratevm plugins
        registerObjectPlugins(plugins);
        registerUnsafePlugins(plugins);
        registerKnownIntrinsicsPlugins(plugins, analysis);
        registerStackValuePlugins(plugins);
        registerPinnedAllocatorPlugins(constantReflection, plugins);
        registerArraysPlugins(plugins, analysis);
        registerArrayPlugins(plugins);
        registerClassPlugins(plugins);
        registerEdgesPlugins(metaAccess, plugins, analysis);
        registerJFRThrowablePlugins(plugins, bytecodeProvider);
        registerJFREventTokenPlugins(plugins, bytecodeProvider);
        registerVMConfigurationPlugins(snippetReflection, plugins);
        registerPlatformPlugins(snippetReflection, plugins);
        registerSizeOfPlugins(snippetReflection, plugins);
    }

    private static void registerObjectPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, Object.class);
        r.register1("clone", Receiver.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                ValueNode object = receiver.get();
                b.addPush(JavaKind.Object, objectCloneNode(b.getInvokeKind(), b.bci(), b.getInvokeReturnStamp(b.getAssumptions()), targetMethod, object));
                return true;
            }
        });
    }

    public static BasicObjectCloneNode objectCloneNode(InvokeKind invokeKind, int bci, StampPair invokeReturnStamp, ResolvedJavaMethod targetMethod, ValueNode receiver) {
        return new SubstrateObjectCloneNode(invokeKind, targetMethod, bci, invokeReturnStamp, receiver);
    }

    private static void registerUnsafePlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, Unsafe.class).setAllowOverwrite(true);
        r.register2("allocateInstance", Receiver.class, Class.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode clazz) {
                ValueNode clazzNonNull = b.nullCheckedValue(clazz, DeoptimizationAction.None);
                b.addPush(JavaKind.Object, new SubstrateDynamicNewInstanceNode(clazzNonNull));
                return true;
            }
        });

    }

    private static void registerArrayPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, Array.class).setAllowOverwrite(true);
        r.register2("newInstance", Class.class, int.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode clazz, ValueNode length) {
                b.addPush(JavaKind.Object, new SubstrateDynamicNewArrayNode(clazz, length));
                return true;
            }
        });

        /*
         * We have our own Java-level implementation of Array.getLength(), so we just disable the
         * plugin defined in StandardGraphBuilderPlugins.
         */
        r.register1("getLength", Object.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver type, ValueNode array) {
                return false;
            }
        });
    }

    private static void registerArraysPlugins(InvocationPlugins plugins, boolean analysis) {

        Registration r = new Registration(plugins, Arrays.class).setAllowOverwrite(true);

        r.register2("copyOf", Object[].class, int.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode original, ValueNode newLength) {
                if (analysis) {
                    b.addPush(JavaKind.Object, new AnalysisArraysCopyOfNode(b.getInvokeReturnStamp(b.getAssumptions()).getTrustedStamp(), original, newLength));
                } else {
                    /* Get the class from the original node. */
                    GetClassNode originalArrayType = b.add(new GetClassNode(original.stamp(NodeView.DEFAULT), b.nullCheckedValue(original)));

                    ValueNode originalLength = b.add(ArrayLengthNode.create(original, b.getConstantReflection()));
                    Stamp stamp = b.getInvokeReturnStamp(b.getAssumptions()).getTrustedStamp().join(original.stamp(NodeView.DEFAULT));

                    b.addPush(JavaKind.Object, new SubstrateArraysCopyOfNode(stamp, original, originalLength, newLength, originalArrayType));
                }
                return true;
            }
        });

        r.register3("copyOf", Object[].class, int.class, Class.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode original, ValueNode newLength, ValueNode newArrayType) {
                if (analysis) {
                    /*
                     * If the new array type comes from a GetClassNode or is a constant we can infer
                     * the concrete type of the new array, otherwise we conservatively assume that
                     * the new array can have any of the instantiated array types.
                     */
                    b.addPush(JavaKind.Object, new AnalysisArraysCopyOfNode(b.getInvokeReturnStamp(b.getAssumptions()).getTrustedStamp(), original, newLength, newArrayType));
                } else {
                    Stamp stamp;
                    if (newArrayType.isConstant()) {
                        ResolvedJavaType newType = b.getConstantReflection().asJavaType(newArrayType.asConstant());
                        stamp = StampFactory.objectNonNull(TypeReference.createExactTrusted(newType));
                    } else {
                        stamp = b.getInvokeReturnStamp(b.getAssumptions()).getTrustedStamp();
                    }

                    ValueNode originalLength = b.add(ArrayLengthNode.create(original, b.getConstantReflection()));
                    b.addPush(JavaKind.Object, new SubstrateArraysCopyOfNode(stamp, original, originalLength, newLength, newArrayType));
                }
                return true;
            }
        });

    }

    private static void registerKnownIntrinsicsPlugins(InvocationPlugins plugins, boolean analysis) {
        Registration r = new Registration(plugins, KnownIntrinsics.class);
        r.register0("heapBase", new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                b.addPush(JavaKind.Object, ReadRegisterFixedNode.forHeapBase());
                return true;
            }
        });
        r.register1("readArrayLength", Object.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode array) {
                b.addPush(JavaKind.Int, new ArrayLengthNode(array));
                return true;
            }
        });
        r.register1("readHub", Object.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode object) {
                ValueNode nonNullObject = b.nullCheckedValue(object);
                b.addPush(JavaKind.Object, new LoadHubNode(b.getStampProvider(), nonNullObject));
                return true;
            }
        });
        r.register3("formatObject", Pointer.class, Class.class, boolean.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode memory, ValueNode hub, ValueNode rememberedSet) {
                b.addPush(JavaKind.Object, new FormatObjectNode(memory, hub, rememberedSet));
                return true;
            }
        });
        r.register5("formatArray", Pointer.class, Class.class, int.class, boolean.class, boolean.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode memory, ValueNode hub, ValueNode length, ValueNode rememberedSet,
                            ValueNode unaligned) {
                b.addPush(JavaKind.Object, new FormatArrayNode(memory, hub, length, rememberedSet, unaligned));
                return true;
            }
        });
        r.register2("unsafeCast", Object.class, Class.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode object, ValueNode toTypeNode) {
                /*
                 * We need to make sure that the updated type information does not flow up, because
                 * it can depend on any condition before (and we do not know which condition, so we
                 * cannot anchor at a particular block).
                 */
                ResolvedJavaType toType = typeValue(b.getConstantReflection(), b, targetMethod, toTypeNode, "toType");
                TypeReference toTypeRef = TypeReference.createTrustedWithoutAssumptions(toType);
                b.addPush(JavaKind.Object, new FixedValueAnchorNode(object, StampFactory.object(toTypeRef)));
                return true;
            }
        });

        r.register1("nonNullPointer", Pointer.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode object) {
                b.addPush(JavaKind.Object, new PiNode(object, nonZeroWord()));
                return true;
            }
        });

        r.register0("readStackPointer", new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                b.addPush(JavaKind.Object, new ReadStackPointerNode());
                return true;
            }
        });
        r.register1("writeStackPointer", Pointer.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode value) {
                b.add(new WriteStackPointerNode(value));
                return true;
            }
        });
        r.register0("readInstructionPointer", new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                b.addPush(JavaKind.Object, new ReadInstructionPointerNode());
                return true;
            }
        });
        r.register0("readCallerStackPointer", new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                b.addPush(JavaKind.Object, new ReadCallerStackPointerNode());
                return true;
            }
        });
        r.register0("readReturnAddress", new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                b.addPush(JavaKind.Object, new ReadReturnAddressNode());
                return true;
            }
        });
        r.register3("farReturn", Object.class, Pointer.class, CodePointer.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode result, ValueNode sp, ValueNode ip) {
                b.add(new FarReturnNode(result, sp, ip));
                return true;
            }
        });
        r.register0("testDeoptimize", new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                b.add(new TestDeoptimizeNode());
                return true;
            }
        });

        r.register0("isDeoptimizationTarget", new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                if (b.getGraph().method() instanceof SharedMethod) {
                    SharedMethod method = (SharedMethod) b.getGraph().method();
                    if (method.isDeoptTarget()) {
                        b.addPush(JavaKind.Boolean, ConstantNode.forBoolean(true));
                    } else {
                        b.addPush(JavaKind.Boolean, ConstantNode.forBoolean(false));
                    }
                } else {
                    // In analysis the value is always true.
                    b.addPush(JavaKind.Boolean, ConstantNode.forBoolean(true));
                }
                return true;
            }
        });

        r.register2("convertUnknownValue", Object.class, Class.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode object, ValueNode typeNode) {
                ResolvedJavaType type = typeValue(b.getConstantReflection(), b, targetMethod, typeNode, "type");
                TypeReference typeRef = TypeReference.createTrustedWithoutAssumptions(type);
                Stamp stamp = StampFactory.object(typeRef);
                if (analysis) {
                    b.addPush(JavaKind.Object, new ConvertUnknownValueNode(object, stamp));
                } else {
                    b.addPush(JavaKind.Object, PiNode.create(object, stamp));
                }
                return true;
            }
        });
    }

    private static IntegerStamp nonZeroWord() {
        return StampFactory.forUnsignedInteger(64, 1, 0xffffffffffffffffL);
    }

    private static void registerStackValuePlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, StackValue.class);

        r.register1("get", int.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode sizeNode) {
                long size = longValue(b, targetMethod, sizeNode, "size");
                StackSlotIdentity slotIdentity = new StackSlotIdentity(b.getGraph().method().asStackTraceElement(b.bci()).toString());
                b.addPush(JavaKind.Object, new StackValueNode(1, size, slotIdentity));
                return true;
            }
        });
        r.register2("get", int.class, int.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode numElementsNode, ValueNode elementSizeNode) {
                long numElements = longValue(b, targetMethod, numElementsNode, "numElements");
                long elementSize = longValue(b, targetMethod, elementSizeNode, "elementSize");
                StackSlotIdentity slotIdentity = new StackSlotIdentity(b.getGraph().method().asStackTraceElement(b.bci()).toString());
                b.addPush(JavaKind.Object, new StackValueNode(numElements, elementSize, slotIdentity));
                return true;
            }
        });
    }

    private static void registerPinnedAllocatorPlugins(ConstantReflectionProvider constantReflection, InvocationPlugins plugins) {
        Registration r = new Registration(plugins, PinnedAllocator.class);

        r.register2("newInstance", Receiver.class, Class.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver pinnedAllocator, ValueNode instanceClassNode) {
                ResolvedJavaType instanceClass = typeValue(constantReflection, b, targetMethod, instanceClassNode, "instanceClass");
                ValueNode pinnedAllocatorNode = pinnedAllocator.get();
                b.addPush(JavaKind.Object, new NewPinnedInstanceNode(instanceClass, pinnedAllocatorNode));
                return true;
            }
        });

        r.register3("newArray", Receiver.class, Class.class, int.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver pinnedAllocator, ValueNode componentTypeNode, ValueNode length) {
                ResolvedJavaType componentType = typeValue(constantReflection, b, targetMethod, componentTypeNode, "componentType");
                ValueNode pinnedAllocatorNode = pinnedAllocator.get();
                b.addPush(JavaKind.Object, new NewPinnedArrayNode(componentType, length, pinnedAllocatorNode));
                return true;
            }
        });
    }

    private static ResolvedJavaType typeValue(ConstantReflectionProvider constantReflection, GraphBuilderContext b, ResolvedJavaMethod targetMethod, ValueNode typeNode, String name) {
        if (!typeNode.isConstant()) {
            throw b.bailout("parameter " + name + " is not a compile time constant for call to " + targetMethod.format("%H.%n(%p)") + " in " + b.getMethod().asStackTraceElement(b.bci()));
        }
        ResolvedJavaType type = constantReflection.asJavaType(typeNode.asConstant());
        if (type == null) {
            throw b.bailout("parameter " + name + " is null for call to " + targetMethod.format("%H.%n(%p)") + " in " + b.getMethod().asStackTraceElement(b.bci()));
        }
        return type;
    }

    private static void registerClassPlugins(InvocationPlugins plugins) {
        /*
         * We have our own Java-level implementation of isAssignableFrom() in DynamicHub, so we do
         * not need to intrinsifiy that to a Graal node. Therefore, we overwrite and deactivate the
         * invocation plugin registered in StandardGraphBuilderPlugins.
         *
         * TODO we should remove the implementation from DynamicHub to a lowering of
         * ClassIsAssignableFromNode. Then we can remove this code.
         */
        Registration r = new Registration(plugins, Class.class).setAllowOverwrite(true);
        r.register2("isAssignableFrom", Receiver.class, Class.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver type, ValueNode otherType) {
                return false;
            }
        });

        r.register2("cast", Receiver.class, Object.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode object) {
                ValueNode toType = receiver.get();
                LogicNode condition = b.append(InstanceOfDynamicNode.create(b.getAssumptions(), b.getConstantReflection(), toType, object, true));
                if (condition.isTautology()) {
                    b.addPush(JavaKind.Object, object);
                } else {
                    FixedGuardNode fixedGuard = b.add(new FixedGuardNode(condition, DeoptimizationReason.ClassCastException, DeoptimizationAction.InvalidateReprofile, false));
                    b.addPush(JavaKind.Object, DynamicPiNode.create(b.getAssumptions(), b.getConstantReflection(), object, fixedGuard, toType));
                }
                return true;
            }
        });
        r.register2("isInstance", Receiver.class, Object.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode object) {
                ValueNode type = receiver.get();
                LogicNode condition = b.append(InstanceOfDynamicNode.create(null, b.getConstantReflection(), type, object, false));
                b.push(JavaKind.Boolean.getStackKind(), b.append(new ConditionalNode(condition).canonical(null)));
                return true;
            }
        });
    }

    private static void registerEdgesPlugins(MetaAccessProvider metaAccess, InvocationPlugins plugins, boolean analysis) {
        if (analysis) {
            Registration r = new Registration(plugins, Edges.class).setAllowOverwrite(true);
            for (Class<?> c : new Class<?>[]{Node.class, NodeList.class}) {
                r.register2("get" + c.getSimpleName() + "Unsafe", Node.class, long.class, new InvocationPlugin() {
                    @Override
                    public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode node, ValueNode offset) {
                        b.addPush(JavaKind.Object, new AnalysisUnsafePartitionLoadNode(node, offset, JavaKind.Object, //
                                        LocationIdentity.any(), GraalEdgeUnsafePartition.get(), metaAccess.lookupJavaType(c)));
                        return true;
                    }
                });

                r.register3("put" + c.getSimpleName() + "Unsafe", Node.class, long.class, c, new InvocationPlugin() {
                    @Override
                    public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode node, ValueNode offset, ValueNode value) {
                        b.add(new AnalysisUnsafePartitionStoreNode(node, offset, value, JavaKind.Object, LocationIdentity.any(), GraalEdgeUnsafePartition.get(), metaAccess.lookupJavaType(c)));
                        return true;
                    }
                });
            }
        }
    }

    protected static long longValue(GraphBuilderContext b, ResolvedJavaMethod targetMethod, ValueNode node, String name) {
        if (!node.isConstant()) {
            throw b.bailout("parameter " + name + " is not a compile time constant for call to " + targetMethod.format("%H.%n(%p)") + " in " + b.getMethod().asStackTraceElement(b.bci()));
        }
        return node.asJavaConstant().asLong();
    }

    /*
     * When Java Flight Recorder is enabled during image generation, the bytecodes of some methods
     * get instrumented. Undo the instrumentation so that it does not end up in the generated image.
     */

    private static void registerJFRThrowablePlugins(InvocationPlugins plugins, BytecodeProvider bytecodeProvider) {
        Registration r = new Registration(plugins, "oracle.jrockit.jfr.jdkevents.ThrowableTracer", bytecodeProvider).setAllowOverwrite(true);
        r.register2("traceError", Error.class, String.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode throwable, ValueNode message) {
                return true;
            }
        });
        r.register2("traceThrowable", Throwable.class, String.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode throwable, ValueNode message) {
                return true;
            }
        });
    }

    private static void registerJFREventTokenPlugins(InvocationPlugins plugins, BytecodeProvider bytecodeProvider) {
        Registration r = new Registration(plugins, "com.oracle.jrockit.jfr.EventToken", bytecodeProvider);
        r.register1("isEnabled", Receiver.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                receiver.get();
                b.addPush(JavaKind.Boolean, ConstantNode.forBoolean(false));
                return true;
            }
        });
    }

    private static void registerVMConfigurationPlugins(SnippetReflectionProvider snippetReflection, InvocationPlugins plugins) {
        Registration r = new Registration(plugins, ImageSingletons.class);
        r.register1("contains", Class.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver unused, ValueNode classNode) {
                Class<?> key = constantObjectParameter(b, snippetReflection, targetMethod, 0, Class.class, classNode);
                boolean result = ImageSingletons.contains(key);

                b.notifyReplacedCall(targetMethod, b.addPush(JavaKind.Boolean, ConstantNode.forBoolean(result)));
                return true;
            }
        });
        r.register1("lookup", Class.class, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver unused, ValueNode classNode) {
                Class<?> key = constantObjectParameter(b, snippetReflection, targetMethod, 0, Class.class, classNode);
                Object result = ImageSingletons.lookup(key);

                b.notifyReplacedCall(targetMethod, b.addPush(JavaKind.Object, ConstantNode.forConstant(snippetReflection.forObject(result), b.getMetaAccess())));
                return true;
            }
        });
    }

    private static void registerPlatformPlugins(SnippetReflectionProvider snippetReflection, InvocationPlugins plugins) {
        Registration r = new Registration(plugins, Platform.class);
        r.register1("includedIn", Class.class, new InvocationPlugin() {
            @SuppressWarnings("unchecked")
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode classNode) {
                Class<? extends Platform> platform = constantObjectParameter(b, snippetReflection, targetMethod, 0, Class.class, classNode);
                boolean result = Platform.includedIn(platform);

                b.notifyReplacedCall(targetMethod, b.addPush(JavaKind.Boolean, ConstantNode.forBoolean(result)));
                return true;
            }
        });
    }

    private static void registerSizeOfPlugins(SnippetReflectionProvider snippetReflection, InvocationPlugins plugins) {
        Registration r = new Registration(plugins, SizeOf.class);
        r.register1("get", Class.class, new InvocationPlugin() {
            @SuppressWarnings("unchecked")
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver unused, ValueNode classNode) {
                Class<? extends PointerBase> clazz = constantObjectParameter(b, snippetReflection, targetMethod, 0, Class.class, classNode);
                int result = SizeOf.get(clazz);

                b.notifyReplacedCall(targetMethod, b.addPush(JavaKind.Int, ConstantNode.forInt(result)));
                return true;
            }
        });
        r.register1("unsigned", Class.class, new InvocationPlugin() {
            @SuppressWarnings("unchecked")
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver unused, ValueNode classNode) {
                Class<? extends PointerBase> clazz = constantObjectParameter(b, snippetReflection, targetMethod, 0, Class.class, classNode);
                UnsignedWord result = SizeOf.unsigned(clazz);

                b.notifyReplacedCall(targetMethod, b.addPush(JavaKind.Object, ConstantNode.forConstant(snippetReflection.forObject(result), b.getMetaAccess())));
                return true;
            }
        });
    }

    private static <T> T constantObjectParameter(GraphBuilderContext b, SnippetReflectionProvider snippetReflection, ResolvedJavaMethod targetMethod, int parameterIndex, Class<T> declaredType,
                    ValueNode classNode) {
        checkParameterUsage(classNode.isConstant(), b, targetMethod, parameterIndex, "parameter is not a compile time constant");
        T result = snippetReflection.asObject(declaredType, classNode.asJavaConstant());
        checkParameterUsage(result != null, b, targetMethod, parameterIndex, "parameter is null");
        return result;
    }

    private static void checkParameterUsage(boolean condition, GraphBuilderContext b, ResolvedJavaMethod targetMethod, int parameterIndex, String message) {
        if (condition) {
            return;
        }

        String parameterName = null;
        LocalVariableTable variableTable = targetMethod.getLocalVariableTable();
        if (variableTable != null) {
            Local variable = variableTable.getLocal(parameterIndex, 0);
            if (variable != null) {
                parameterName = variable.getName();
            }
        }
        if (parameterName == null) {
            /* Fall back to parameter number if no name is available. */
            parameterName = String.valueOf(parameterIndex);
        }

        throw UserError.abort(message + ": parameter " + parameterName + " of call to " + targetMethod.format("%H.%n(%p)") + " in " + b.getMethod().asStackTraceElement(b.bci()));
    }
}
