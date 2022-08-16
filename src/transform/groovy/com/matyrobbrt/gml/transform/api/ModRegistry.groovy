/*
 * MIT License
 *
 * Copyright (c) 2022 matyrobbrt
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.matyrobbrt.gml.transform.api

import com.matyrobbrt.gml.transform.GModASTTransformer
import groovy.transform.CompileStatic

import javax.annotation.Nullable

class ModRegistry {
    private static final Map<String, ModData> REGISTRY = [:]

    @Nullable
    static ModData getData(String packageName) {
        ModData found = REGISTRY[packageName]
        if (found !== null) return found
        final split = packageName.split('\\.').toList()
        for (int i = split.size() - 1; i >= 0; i--) {
            found = REGISTRY[split.subList(0, i).join('.')]
            if (found !== null) return found
        }
        return found
    }

    static void register(String packageName, ModData modData) {
        if (StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass() == GModASTTransformer) {
            REGISTRY[packageName] = modData
        }
    }

    @CompileStatic
    static record ModData(String className, String modId) {}
}