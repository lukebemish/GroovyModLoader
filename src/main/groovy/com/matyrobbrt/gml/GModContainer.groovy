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

package com.matyrobbrt.gml

import com.matyrobbrt.gml.bus.EventBusSubscriber
import com.matyrobbrt.gml.bus.GModEventBus
import com.matyrobbrt.gml.bus.type.ForgeBus
import com.matyrobbrt.gml.bus.type.ModBus
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.eventbus.EventBusErrorMessage
import net.minecraftforge.eventbus.api.BusBuilder
import net.minecraftforge.eventbus.api.Event
import net.minecraftforge.fml.ModContainer
import net.minecraftforge.fml.ModLoadingException
import net.minecraftforge.fml.ModLoadingStage
import net.minecraftforge.fml.config.IConfigEvent
import net.minecraftforge.fml.event.IModBusEvent
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.fml.loading.moddiscovery.ModAnnotation
import net.minecraftforge.forgespi.language.IModInfo
import net.minecraftforge.forgespi.language.ModFileScanData
import org.objectweb.asm.Type

import java.lang.reflect.Constructor
import java.util.function.Consumer

@Slf4j
@CompileStatic
final class GModContainer extends ModContainer {
    private static final Type FORGE_EBS = Type.getType(ForgeBus)
    private static final Type MOD_EBS = Type.getType(ModBus)
    private static final Type EBS = Type.getType(EventBusSubscriber)

    private final Class modClass
    private Object mod
    private final GModEventBus modBus
    private final ModFileScanData scanData

    GModContainer(final IModInfo info, final String className, final ModFileScanData scanData, final ModuleLayer layer) {
        super(info)
        this.scanData = scanData

        activityMap[ModLoadingStage.CONSTRUCT] = this.&constructMod
        this.configHandler = Optional.of(this::postConfigEvent as Consumer<IConfigEvent>)
        this.contextExtension = { null }

        modBus = new GModEventBus(BusBuilder.builder()
                .setExceptionHandler {bus, event, listeners, i, cause -> log.error('Failed to process mod event: {}', new EventBusErrorMessage(event, i, listeners, cause)) }
                .setTrackPhases(false)
                .markerType(IModBusEvent)
                .build())

        final module = layer.findModule(info.owningFile.moduleName()).orElseThrow()
        modClass = Class.forName(module, className)
        log.debug('Loaded GMod class {} on loader {} and module {}', className, modClass.classLoader, module)
    }

    private void constructMod() {
        try {
            log.debug('Loading mod class {} for {}', modClass.name, this.modId)
            def modCtor = resolveCtor()
            this.mod = modCtor.parameterTypes.length > 0 ? modCtor.newInstance(this) : modCtor.newInstance()
            log.debug('Successfully loaded mod {}', this.modId)
            injectEBS()
        } catch (final Throwable t) {
            log.error('Failed to create mod from class {} for modid {}', modClass.name, modId, t)
            throw new ModLoadingException(this.modInfo, ModLoadingStage.CONSTRUCT, 'fml.modloading.failedtoloadmod', t, this.modClass)
        }
    }

    void setupMod(Object mod) {
        injectBus(mod)
    }

    @CompileDynamic
    @SuppressWarnings('GrUnresolvedAccess')
    private void injectBus(Object mod) {
        log.debug('Injecting bus into mod {}, with class {}', modId, modClass)
        mod.modBus = getModBus()
        log.debug('Successfully injected bus into mod {}, with class {}', modId, modClass)
    }

    private void injectEBS() {
        log.debug('Registering EventBusSubscribers for mod {}', modId)

        scanData.annotations.findAll { it.annotationType() == EBS }
            .each {
                final modId = it.annotationData()['modId'] as String
                final boolean isInMod = { ModFileScanData.AnnotationData data ->
                    if (modId !== null && !modId.isEmpty()) {
                        return modId == this.getModId()
                    }
                    return data.clazz().internalName.startsWith(modClass.packageName.replace('.' as char, '/' as char))
                }.call(it)

                if (!isInMod) return
                final bus = it.annotationData()['value'] as Type
                final dists = dists(it)

                if (FMLEnvironment.dist in dists) {
                    log.info('Auto-Subscribing EBS class {} to bus {}', it.clazz().className, bus)
                    (switch (bus) {
                        case FORGE_EBS -> MinecraftForge.EVENT_BUS
                        case MOD_EBS -> getModBus()
                        default -> throw new IllegalArgumentException("Unknown bus: $bus")
                    }).register(Class.forName(it.clazz().className, true, modClass.classLoader))
                }
            }

        log.debug('Registered EventBusSubscribers for mod {}', modId)
    }

    @CompileDynamic
    private static Set<Dist> dists(final ModFileScanData.AnnotationData data) {
        final List<ModAnnotation.EnumHolder> declaredHolders = data.annotationData()['dist'] as List<ModAnnotation.EnumHolder>
        final List<ModAnnotation.EnumHolder> holders = declaredHolders ?: (this.&makeDefaultDistributionHolders)()
        holders.collect { Dist.valueOf(it.value) }.toSet()
    }

    private static List<ModAnnotation.EnumHolder> makeDefaultDistributionHolders() {
        Dist.values().collect { new ModAnnotation.EnumHolder(null, it.name()) }
    }

    private Constructor resolveCtor() {
        try {
            return modClass.getDeclaredConstructor(GModContainer)
        } catch (NoSuchMethodException ignored) {
            return modClass.getDeclaredConstructor()
        }
    }

    GModEventBus getModBus() {
        modBus
    }

    @Override
    boolean matches(Object mod) {
        return mod.is(this.mod)
    }

    @Override
    Object getMod() {
        return mod
    }

    @Override
    protected <T extends Event & IModBusEvent> void acceptEvent(T e) {
        try {
            modBus.post(e)
        } catch (Throwable t) {
            log.error('Caught exception in mod \'{}\' during event dispatch for {}', modId, e, t)
            throw new ModLoadingException(this.modInfo, this.modLoadingStage, 'fml.modloading.errorduringevent', t)
        }
    }

    private void postConfigEvent(final IConfigEvent event) {
        modBus.post(event.self())
    }
}