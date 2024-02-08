/*
 * Copyright (c) Matyrobbrt
 * SPDX-License-Identifier: MIT
 */

package org.groovymc.gml.mappings

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.mojang.datafixers.util.Either
import com.mojang.logging.LogUtils
import cpw.mods.modlauncher.Launcher
import cpw.mods.modlauncher.api.IEnvironment
import groovy.transform.CompileStatic
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.loading.FMLLoader
import net.minecraftforge.srgutils.IMappingFile
import org.slf4j.Logger

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

@CompileStatic
class MappingsProvider {

    public static final MappingsProvider INSTANCE = new MappingsProvider()

    private static final Logger LOGGER = LogUtils.getLogger()
    private static final String PISTON_META = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
    private static final String MAPPINGS_THREAD = 'GML Mappings Thread'
    private static final String VERSION_JSON = 'version.json'
    private static final String OFFICIAL = 'official.txt'
    private static final String MCPCONFIG_ZIP = 'srg.zip'
    private static final String JOINED_PATH = 'config/joined.tsrg'

    @Lazy
    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()

    private static final AtomicLong startTime = new AtomicLong(0)

    private final String version = FMLLoader.versionInfo().mcVersion()
    private final Path gameDir = Launcher.INSTANCE.environment().getProperty(IEnvironment.Keys.GAMEDIR.get()).get()
    private final Path cacheDir = gameDir.resolve('mod_data/gml').resolve(version)
    private final Path zipPath = cacheDir.resolve(MCPCONFIG_ZIP)
    private final Path versionJsonPath = cacheDir.resolve(VERSION_JSON)
    private final Path officialPath = cacheDir.resolve(OFFICIAL)
    private final String mcpVersion = FMLLoader.versionInfo().mcpVersion()
    private final GString mcpConfigURL = "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_config/${version}-${mcpVersion}/mcp_config-${version}-${mcpVersion}.zip"

    final CompletableFuture<Either<LoadedMappings, Throwable>> mappingsProvider = new CompletableFuture<Either<LoadedMappings, Throwable>>()

    private boolean setup = false

    @Lazy
    private static volatile HttpClient client = HttpClient.newBuilder().build()

    private MappingsProvider() {
        try (final is = getClass().getResourceAsStream('/mod_data_readme.txt')) {
            Files.createDirectories(cacheDir)
            Files.write(cacheDir.resolve('README'), is.readAllBytes())
        }
    }

    private Thread setupMappingsThread(Runnable runnable) {
        Thread mappingsThread = new Thread(runnable, MAPPINGS_THREAD)
        mappingsThread.setUncaughtExceptionHandler { t, e ->
            LOGGER.error('Caught exception while setting up mappings...', e)
            mappingsProvider.complete(Either.right(e))
        }
        return mappingsThread
    }

    synchronized void startMappingsSetup() {
        startTime.set(System.currentTimeMillis())
        if (!setup) {
            LOGGER.info('Starting runtime mappings setup...')
            setup = true

            if (Files.exists(versionJsonPath) && Files.exists(zipPath) && Files.exists(officialPath)) {
                Thread mappingsThread = setupMappingsThread {
                    loadLayeredMappings()
                    LOGGER.info('Finished runtime mappings setup.')
                }
                mappingsThread.start()
            } else {
                Thread mappingsThread = setupMappingsThread {
                    try {
                        InputStream manifestInput = downloadFile(PISTON_META)
                        BufferedReader manifestReader = new BufferedReader(new InputStreamReader(manifestInput))
                        ManifestMetaFile manifestMeta = GSON.fromJson(manifestReader, ManifestMetaFile)
                        ManifestMetaFile.VersionMeta versionMeta = manifestMeta.versions.find { it.id == this.version }
                        if (!Files.exists(cacheDir)) Files.createDirectories(cacheDir)
                        LOGGER.info('Found version metadata from piston-meta.')

                        checkAndUpdateVersionFile(versionMeta)
                        LOGGER.info('version.json is up to date.')

                        checkAndUpdateOfficialFile()
                        LOGGER.info('Official mappings are up to date.')

                        checkAndUpdateMCPConfigFile()
                        LOGGER.info('MCPConfig is up to date.')

                        loadLayeredMappings()
                        LOGGER.info('Finished runtime mappings setup.')
                    } catch (IOException e) {
                        // Error state, I couldn't make mappings.
                        throw e
                    } catch (NoSuchElementException e) {
                        // Error state, not a known version? Huh?
                        throw e
                    }
                }
                mappingsThread.start()
            }
        }
    }

    private void loadLayeredMappings() throws IOException {
        try (final ZipFile zip = new ZipFile(zipPath.toFile())
             final InputStream joined = zip.getInputStream(new ZipEntry(JOINED_PATH))) {
            final srgFile = IMappingFile.load(joined)
            final officialFile = IMappingFile.load(officialPath.newInputStream()).reverse()

            // official class name, official -> srg
            final methodsMap = new LinkedHashMap<String, Map<String, List<String>>>(8250)
            final fieldsMap = new LinkedHashMap<String, Map<String, String>>(6500)

            officialFile.classes.each { IMappingFile.IClass clazz ->
                final String official = clazz.getMapped()
                final String obf = clazz.getOriginal()

                final methods = new LinkedHashMap<String, List<String>>()
                final fields = new LinkedHashMap<String, String>()

                final srgMethods = srgFile.getClass(obf)?.methods
                final srgFields = srgFile.getClass(obf)?.fields

                if (srgFields === null || srgMethods === null) return
                final String dotSeparatedOfficial = official.replace('/', '.')

                final officialMethods = officialFile.getClass(obf).methods
                if (!officialMethods.empty) {
                    officialMethods.each { IMappingFile.IMethod method ->
                        String mMoj = method.mapped
                        String mObf = method.original
                        String mObfDesc = method.descriptor
                        String mSrg = srgFile.getClass(obf).getMethod(mObf, mObfDesc)?.mapped
                        if (mSrg === null || mSrg == mObf) return
                        final outSrg = methods.computeIfAbsent(mMoj, {[]})
                        outSrg.add(mSrg)
                    }
                    if (!methods.isEmpty())
                        methodsMap.put(dotSeparatedOfficial, methods)
                }

                final officialFields = officialFile.getClass(obf).fields
                if (!officialFields.empty) {
                    officialFields.each { IMappingFile.IField field ->
                        final String fMoj = field.mapped
                        final String srg = srgFile.getClass(obf).getField(field.original)?.mapped
                        if (srg === null || fMoj == srg) return
                        fields.put(fMoj, srg)
                    }
                    if (!fields.isEmpty())
                        fieldsMap.put(dotSeparatedOfficial, fields)
                }
            }

            mappingsProvider.complete(Either.left(new LoadedMappings(methodsMap, fieldsMap)))
        }
        LOGGER.info "Loaded runtime mappings in ${System.currentTimeMillis() - startTime}ms"
    }

    private void checkAndUpdateVersionFile(ManifestMetaFile.VersionMeta versionMeta) throws IOException {
        String sha1 = versionMeta.sha1

        if (Files.exists(versionJsonPath)) {
            byte[] existingSha1 = calcSha1(versionJsonPath)
            byte[] knownSha1 = decodeHex(sha1)
            if (Arrays.equals(knownSha1, existingSha1)) return
            LOGGER.warn 'Checksum mismatch for version.json'
            LOGGER.warn "Expected: $knownSha1"
            LOGGER.warn "Found:    $existingSha1"
        }

        try (InputStream versionStream = downloadFile(versionMeta.url)) {
            Files.copy(versionStream, versionJsonPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private static byte[] decodeHex(String hex) {
        int l = hex.length()
        int len = l.intdiv(2)
        byte[] data = new byte[len]
        for (int i = 0; i < len; i += 1) {
            data[i] = (byte) ((hexDigit(hex.charAt(i*2)) << 4)
                    + hexDigit(hex.charAt(i*2 + 1)))
        }
        return data
    }

    private static byte hexDigit(char ch) {
        int digit = Character.digit(ch, 16)
        if (digit == -1) throw new IllegalArgumentException("Invalid hex digit: $ch")
        return (byte) digit
    }

    private void checkAndUpdateMCPConfigFile() throws IOException {
        Path srg = this.cacheDir.resolve(MCPCONFIG_ZIP)
        if (Files.exists(srg))
            return

        try (InputStream officialStream = downloadFile(mcpConfigURL)) {
            Files.copy(officialStream, srg, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private void checkAndUpdateOfficialFile() throws IOException {
        VersionMetaFile versionMeta = GSON.fromJson(Files.newBufferedReader(versionJsonPath), VersionMetaFile)
        String sha1 = switch (FMLLoader.dist) {
            case Dist.CLIENT -> versionMeta.downloads.client_mappings.sha1
            case Dist.DEDICATED_SERVER -> versionMeta.downloads.server_mappings.sha1
        }
        String url = switch (FMLLoader.dist) {
            case Dist.CLIENT -> versionMeta.downloads.client_mappings.url
            case Dist.DEDICATED_SERVER -> versionMeta.downloads.server_mappings.url
        }

        if (Files.exists(officialPath)) {
            byte[] existingSha1 = calcSha1(officialPath)
            byte[] knownSha1 = decodeHex(sha1)
            if (Arrays.equals(knownSha1, existingSha1)) return
        }

        try (InputStream officialStream = downloadFile(url)) {
            Files.copy(officialStream, officialPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private static byte[] calcSha1(Path file) throws FileNotFoundException,
            IOException, NoSuchAlgorithmException {

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1")
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192]
            int len = input.read(buffer)

            while (len != -1) {
                sha1.update(buffer, 0, len)
                len = input.read(buffer)
            }

            return sha1.digest()
        }
    }

    /**
     * Downloads a file from the given URI, with GZip if the server supports it.
     * @param url
     * @return InputStream
     * @throws IOException
     * @throws InterruptedException
     */
    private static InputStream downloadFile(final String url) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(new URI(url))
                .setHeader('Accept-Encoding', 'gzip')
                .GET()
                .build()
        final HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !== 200)
            throw new IOException("Failed to download file from \"$url\" (${response.statusCode()})")

        final boolean isGzipEncoded = response.headers().firstValue('Content-Encoding').orElse('') == 'gzip'
        return isGzipEncoded ? new GZIPInputStream(response.body()) : response.body()
    }
}
