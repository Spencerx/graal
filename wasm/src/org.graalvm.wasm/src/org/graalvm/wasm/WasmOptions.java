/*
 * Copyright (c) 2019, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.graalvm.wasm;

import java.util.HashMap;
import java.util.Map;

import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionStability;
import org.graalvm.options.OptionType;
import org.graalvm.wasm.exception.Failure;
import org.graalvm.wasm.exception.WasmException;
import org.graalvm.wasm.predefined.BuiltinModule;

import com.oracle.truffle.api.Option;

@Option.Group(WasmLanguage.ID)
public class WasmOptions {
    @Option(help = "A comma-separated list of builtin modules to use.", category = OptionCategory.USER, stability = OptionStability.STABLE, usageSyntax = "[<linkingName>:]<builtinModuleName>,[<linkingName>:]<builtinModuleName>,...")//
    public static final OptionKey<Map<String, BuiltinModule>> Builtins = new OptionKey<>(Map.of(), new OptionType<>("Builtins", optionValue -> {
        if (optionValue.isEmpty()) {
            return Map.of();
        }
        final String[] moduleSpecs = optionValue.split(",");
        final Map<String, BuiltinModule> builtinModules = new HashMap<>(moduleSpecs.length);
        for (String moduleSpec : moduleSpecs) {
            final String[] parts = moduleSpec.split(":");
            if (parts.length > 2) {
                throw WasmException.create(Failure.UNSPECIFIED_INVALID, "Module specification '" + moduleSpec + "' is not valid.");
            }
            final String linkingName = parts[0];
            final String predefinedModuleName = parts.length == 2 ? parts[1] : parts[0];
            builtinModules.put(linkingName, BuiltinModule.requireBuiltinModule(predefinedModuleName));
        }
        return Map.copyOf(builtinModules);
    }));

    @Option(help = "The minimal binary size for which to use async parsing. If threads are not supported, async parsing will not be used.", category = OptionCategory.USER, stability = OptionStability.STABLE, usageSyntax = "[0, inf)", //
                    deprecated = true, deprecationMessage = "Option no longer has any effect and can be safely omitted.")//
    public static final OptionKey<Integer> AsyncParsingBinarySize = new OptionKey<>(100_000);

    @Option(help = "The stack size in kilobytes to use during async parsing, or zero to use defaults.", category = OptionCategory.USER, stability = OptionStability.STABLE, usageSyntax = "[0, inf)", //
                    deprecated = true, deprecationMessage = "Option no longer has any effect and can be safely omitted.")//
    public static final OptionKey<Integer> AsyncParsingStackSize = new OptionKey<>(0);

    @Option(help = "A comma-separated list of pre-opened Wasi directories.", category = OptionCategory.USER, stability = OptionStability.STABLE, usageSyntax = "[<virtualDir>::]<hostDir>,[<virtualDir>::]<hostDir>,...")//
    public static final OptionKey<String> WasiMapDirs = new OptionKey<>("");

    public enum ConstantsStorePolicy {
        ALL,
        LARGE_ONLY,
        NONE
    }

    public static final OptionType<ConstantsStorePolicy> StoreConstantsPolicyOptionType = new OptionType<>("StoreConstantsPolicy", ConstantsStorePolicy::valueOf);

    @Option(help = "Whenever to store the constants in a pool or not. Deprecated: no longer has any effect.", category = OptionCategory.EXPERT, stability = OptionStability.EXPERIMENTAL, deprecated = true, usageSyntax = "NONE|ALL|LARGE_ONLY")//
    public static final OptionKey<ConstantsStorePolicy> StoreConstantsPolicy = new OptionKey<>(ConstantsStorePolicy.NONE, StoreConstantsPolicyOptionType);

    @Option(help = "Use sun.misc.Unsafe-based memory.", category = OptionCategory.EXPERT, stability = OptionStability.EXPERIMENTAL, usageSyntax = "false|true")//
    public static final OptionKey<Boolean> UseUnsafeMemory = new OptionKey<>(false);

    // WASM Context Options
    @Option(help = "Use saturating-float-to-int conversion", category = OptionCategory.EXPERT, stability = OptionStability.EXPERIMENTAL, usageSyntax = "true|false") //
    public static final OptionKey<Boolean> SaturatingFloatToInt = new OptionKey<>(true);

    @Option(help = "Use sign-extension operators", category = OptionCategory.EXPERT, stability = OptionStability.EXPERIMENTAL, usageSyntax = "true|false") //
    public static final OptionKey<Boolean> SignExtensionOps = new OptionKey<>(true);

    @Option(help = "Enable multi-value support", category = OptionCategory.EXPERT, stability = OptionStability.EXPERIMENTAL, usageSyntax = "true|false") //
    public static final OptionKey<Boolean> MultiValue = new OptionKey<>(true);

    @Option(help = "Enable bulk-memory operations and support for reference types", category = OptionCategory.EXPERT, stability = OptionStability.EXPERIMENTAL, usageSyntax = "true|false") //
    public static final OptionKey<Boolean> BulkMemoryAndRefTypes = new OptionKey<>(true);

    @Option(help = "Enable support for 64-bit memory addresses (More details at https://github.com/WebAssembly/memory64/blob/main/proposals/memory64/Overview.md)", //
                    category = OptionCategory.EXPERT, stability = OptionStability.EXPERIMENTAL, usageSyntax = "false|true") //
    public static final OptionKey<Boolean> Memory64 = new OptionKey<>(false);

    @Option(help = "Enable support for multiple memories within a single module", //
                    category = OptionCategory.EXPERT, stability = OptionStability.EXPERIMENTAL, usageSyntax = "false|true") //
    public static final OptionKey<Boolean> MultiMemory = new OptionKey<>(false);

    @Option(help = "Enable support for threads and atomics", //
                    category = OptionCategory.EXPERT, stability = OptionStability.EXPERIMENTAL, usageSyntax = "false|true") //
    public static final OptionKey<Boolean> Threads = new OptionKey<>(false);

    @Option(help = "Enable support for extended const expressions", //
                    category = OptionCategory.EXPERT, stability = OptionStability.EXPERIMENTAL, usageSyntax = "false|true") //
    public static final OptionKey<Boolean> ExtendedConstExpressions = new OptionKey<>(false);

    @Option(help = "Enable support for the v128 type and vector instructions", //
                    category = OptionCategory.EXPERT, stability = OptionStability.EXPERIMENTAL, usageSyntax = "true|false") //
    public static final OptionKey<Boolean> SIMD = new OptionKey<>(true);

    @Option(help = "Enable support for the relaxed vector instructions", //
                    category = OptionCategory.EXPERT, stability = OptionStability.EXPERIMENTAL, usageSyntax = "true|false") //
    public static final OptionKey<Boolean> RelaxedSIMD = new OptionKey<>(false);

    @Option(help = "In this mode memories and tables are not initialized.", category = OptionCategory.INTERNAL, stability = OptionStability.EXPERIMENTAL, usageSyntax = "false|true") //
    public static final OptionKey<Boolean> MemoryOverheadMode = new OptionKey<>(false);

    @Option(help = "Make WASI random_get always return the same random numbers. For testing purpose only.", category = OptionCategory.INTERNAL, stability = OptionStability.EXPERIMENTAL, usageSyntax = "false|true") //
    public static final OptionKey<Boolean> WasiConstantRandomGet = new OptionKey<>(false);

    @Option(help = "Allows the embedder to access memories as direct byte buffers.", category = OptionCategory.INTERNAL, stability = OptionStability.EXPERIMENTAL, usageSyntax = "false|true") //
    public static final OptionKey<Boolean> DirectByteBufferMemoryAccess = new OptionKey<>(false);

    @Option(help = "Support instrumentation for functions that do not have their source available. For testing purpose only.", category = OptionCategory.INTERNAL, stability = OptionStability.EXPERIMENTAL, usageSyntax = "false|true") //
    public static final OptionKey<Boolean> DebugTestMode = new OptionKey<>(false);

    @Option(help = "Makes Context#eval return a wasm instance (runtime representation) instead of a wasm module (symbolic representation).", category = OptionCategory.EXPERT, stability = OptionStability.EXPERIMENTAL, usageSyntax = "false|true") //
    public static final OptionKey<Boolean> EvalReturnsInstance = new OptionKey<>(false);
}
