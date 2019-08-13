package com.openwebstart.jvm;

import com.openwebstart.jvm.func.Result;
import com.openwebstart.jvm.io.DownloadInputStream;
import com.openwebstart.jvm.json.CacheStore;
import com.openwebstart.jvm.json.JsonHandler;
import com.openwebstart.jvm.listener.Registration;
import com.openwebstart.jvm.listener.RuntimeAddedListener;
import com.openwebstart.jvm.listener.RuntimeRemovedListener;
import com.openwebstart.jvm.listener.RuntimeUpdateListener;
import com.openwebstart.jvm.localfinder.RuntimeFinder;
import com.openwebstart.jvm.localfinder.RuntimeFinderUtils;
import com.openwebstart.jvm.os.OperationSystem;
import com.openwebstart.jvm.runtimes.LocalJavaRuntime;
import com.openwebstart.jvm.runtimes.RemoteJavaRuntime;
import com.openwebstart.jvm.ui.dialogs.RuntimeDownloadDialog;
import com.openwebstart.jvm.util.FileUtil;
import com.openwebstart.jvm.util.FolderFactory;
import com.openwebstart.jvm.util.RuntimeVersionComparator;
import com.openwebstart.jvm.util.ZipUtil;
import dev.rico.client.Client;
import dev.rico.core.http.HttpClient;
import dev.rico.core.http.HttpResponse;
import net.adoptopenjdk.icedteaweb.Assert;
import net.adoptopenjdk.icedteaweb.io.IOUtils;
import net.adoptopenjdk.icedteaweb.jnlp.version.VersionString;
import net.adoptopenjdk.icedteaweb.logging.Logger;
import net.adoptopenjdk.icedteaweb.logging.LoggerFactory;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public final class LocalRuntimeManager {

    private static final Logger LOG = LoggerFactory.getLogger(LocalRuntimeManager.class);

    private static final LocalRuntimeManager INSTANCE = new LocalRuntimeManager();

    private final List<LocalJavaRuntime> runtimes = new CopyOnWriteArrayList<>();

    private final List<RuntimeRemovedListener> removedListeners = new CopyOnWriteArrayList<>();

    private final List<RuntimeAddedListener> addedListeners = new CopyOnWriteArrayList<>();

    private final List<RuntimeUpdateListener> updatedListeners = new CopyOnWriteArrayList<>();

    private final Lock jsonStoreLock = new ReentrantLock();

    private LocalRuntimeManager() {
    }

    public List<LocalJavaRuntime> getAll() {
        return Collections.unmodifiableList(runtimes);
    }

    public Registration addRuntimeAddedListener(final RuntimeAddedListener listener) {
        addedListeners.add(listener);
        return () -> addedListeners.remove(listener);
    }

    public Registration addRuntimeRemovedListener(final RuntimeRemovedListener listener) {
        removedListeners.add(listener);
        return () -> removedListeners.remove(listener);
    }

    public Registration addRuntimeUpdatedListener(final RuntimeUpdateListener listener) {
        updatedListeners.add(listener);
        return () -> updatedListeners.remove(listener);
    }

    private void saveRuntimes() throws Exception {
        jsonStoreLock.lock();
        try {
            LOG.debug("Saving runtime cache to filesystem");
            final File cachePath = RuntimeManagerConfig.getInstance().getCachePath().toFile();
            if (!cachePath.exists()) {
                final boolean dirCreated = cachePath.mkdirs();
                if (!dirCreated) {
                    throw new IOException("Can not create cache dir '" + cachePath + "'");
                }
            }
            final File jsonFile = new File(cachePath, RuntimeManagerConstants.JSON_STORE_FILENAME);
            if (jsonFile.exists()) {
                jsonFile.delete();
            }
            try (final FileOutputStream fileOutputStream = new FileOutputStream(jsonFile)) {
                final CacheStore cacheStore = new CacheStore(runtimes);
                final String jsonString = JsonHandler.getInstance().toJson(cacheStore);
                IOUtils.writeContent(fileOutputStream, jsonString.getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            jsonStoreLock.unlock();
        }
    }

    public void loadRuntimes() throws Exception {
        jsonStoreLock.lock();
        try {
            LOG.debug("Loading runtime cache from filesystem");
            final File cachePath = RuntimeManagerConfig.getInstance().getCachePath().toFile();
            final File jsonFile = new File(cachePath, RuntimeManagerConstants.JSON_STORE_FILENAME);
            if (jsonFile.exists()) {
                try (final FileInputStream fileInputStream = new FileInputStream(jsonFile)) {
                    final String content = IOUtils.readContentAsString(fileInputStream, Charset.forName(RuntimeManagerConstants.UTF_8));
                    final CacheStore cacheStore = JsonHandler.getInstance().fromJson(content, CacheStore.class);

                    clear();
                    cacheStore.getRuntimes().forEach(r -> add(r));
                }
            } else {
                clear();
            }
        } finally {
            jsonStoreLock.unlock();
        }
    }

    private void clear() {
        LOG.debug("Clearing runtime cache");
        runtimes.forEach(r -> {
            if (runtimes.contains(r)) {
                final boolean removed = runtimes.remove(r);

                if (removed) {
                    removedListeners.forEach(l -> l.onRuntimeRemoved(r));
                }
            }
        });
        try {
            saveRuntimes();
        } catch (final Exception e) {
            throw new RuntimeException("Error while saving JVM cache.", e);
        }
    }

    public void replace(final LocalJavaRuntime oldRuntime, final LocalJavaRuntime newRuntime) {
        LOG.debug("Replacing runtime definition with new one");

        Assert.requireNonNull(oldRuntime, "oldRuntime");
        Assert.requireNonNull(newRuntime, "newRuntime");

        if (!Objects.equals(oldRuntime.getJavaHome(), newRuntime.getJavaHome())) {
            throw new IllegalArgumentException("Can only replace a runtime with same JAVA_HOME");
        }

        if (!Objects.equals(oldRuntime.isManaged(), newRuntime.isManaged())) {
            throw new IllegalArgumentException("Can not change managed state of runtime");
        }

        final int index = runtimes.indexOf(oldRuntime);
        if (index < 0) {
            throw new IllegalArgumentException("Item is not in collection!");
        }
        runtimes.remove(index);
        runtimes.add(index, newRuntime);
        updatedListeners.forEach(l -> l.onRuntimeUpdated(oldRuntime, newRuntime));
        try {
            saveRuntimes();
        } catch (final Exception e) {
            throw new RuntimeException("Error while saving JVM cache.", e);
        }
    }

    public void add(final LocalJavaRuntime localJavaRuntime) {
        LOG.debug("Adding runtime definition");

        Assert.requireNonNull(localJavaRuntime, "localJavaRuntime");

        //final VersionString supportedRange = RuntimeManagerConfig.getInstance().getSupportedVersionRange();
        //if(!Optional.ofNullable(supportedRange).map(v -> v.contains(localJavaRuntime.getVersion())).orElse(true)) {
        //    throw new IllegalStateException("Runtime version '" + localJavaRuntime.getVersion() + "' do not match to supported version range '" + supportedRange + "'");
        //}

        final Path runtimePath = localJavaRuntime.getJavaHome();
        if (!runtimePath.toFile().exists()) {
            throw new IllegalArgumentException("Can not add runtime with nonexisting JAVAHOME=" + runtimePath);
        }

        if (!runtimes.contains(localJavaRuntime)) {
            runtimes.add(localJavaRuntime);
            addedListeners.forEach(l -> l.onRuntimeAdded(localJavaRuntime));
            try {
                saveRuntimes();
            } catch (final Exception e) {
                throw new RuntimeException("Error while saving JVM cache.", e);
            }
        }
    }

    public void delete(final LocalJavaRuntime localJavaRuntime) {
        Assert.requireNonNull(localJavaRuntime, "localJavaRuntime");

        LOG.debug("Deleting runtime");

        if (!localJavaRuntime.isManaged()) {
            throw new IllegalArgumentException("Can not delete runtime that is not managed");
        }

        if (runtimes.contains(localJavaRuntime)) {
            final boolean removed = runtimes.remove(localJavaRuntime);

            if (removed && localJavaRuntime.isManaged()) {
                final Path runtimeDir = localJavaRuntime.getJavaHome();
                final boolean deleted = FileUtil.deleteDirectory(runtimeDir.toFile());
                if (!deleted) {
                    throw new RuntimeException("Can not delete " + runtimeDir);
                }
            }
            if (removed) {
                removedListeners.forEach(l -> l.onRuntimeRemoved(localJavaRuntime));
            }
            try {
                saveRuntimes();
            } catch (final Exception e) {
                throw new RuntimeException("Error while saving JVM cache.", e);
            }
        }
    }

    public void remove(final LocalJavaRuntime localJavaRuntime) {
        Assert.requireNonNull(localJavaRuntime, "localJavaRuntime");

        LOG.debug("Removing runtime definition");

        if (localJavaRuntime.isManaged()) {
            throw new IllegalArgumentException("Can not remove runtime that is managed");
        }

        if (runtimes.contains(localJavaRuntime)) {
            final boolean removed = runtimes.remove(localJavaRuntime);

            if (removed) {
                removedListeners.forEach(l -> l.onRuntimeRemoved(localJavaRuntime));
            }
            try {
                saveRuntimes();
            } catch (final Exception e) {
                throw new RuntimeException("Error while saving JVM cache.", e);
            }
        }
    }

    public static LocalRuntimeManager getInstance() {
        return INSTANCE;
    }

    public List<Result<LocalJavaRuntime>> findAndAddLocalRuntimes() {
        final List<Result<LocalJavaRuntime>> result = RuntimeFinderUtils.findRuntimesOnSystem();
        result.stream()
                .forEach(r -> {
                    if (r.isSuccessful()) {
                        final LocalJavaRuntime runtime = r.getResult();
                        if (Optional.ofNullable(RuntimeManagerConfig.getInstance().getSupportedVersionRange()).map(v -> v.contains(runtime.getVersion())).orElse(true)) {
                            add(runtime);
                        }
                    }
                });
        return result;
    }

    public LocalJavaRuntime install(final RemoteJavaRuntime remoteRuntime, final Consumer<DownloadInputStream> downloadConsumer) throws Exception {
        Assert.requireNonNull(remoteRuntime, "remoteRuntime");

        LOG.debug("Installing remote runtime on local cache");


        if (!Objects.equals(remoteRuntime.getOperationSystem(), OperationSystem.getLocalSystem())) {
            throw new IllegalArgumentException("Can not install JVM for another os than " + OperationSystem.getLocalSystem().getName());
        }

        final FolderFactory folderFactory = new FolderFactory(RuntimeManagerConfig.getInstance().getCachePath());
        final Path runtimePath = folderFactory.createSubFolder(remoteRuntime.getVendor() + "-" + remoteRuntime.getVersion());

        LOG.debug("Runtime will be installed in " + runtimePath);


        final URI downloadRequest = remoteRuntime.getEndpoint();

        //TODO: HTTP request runtime
        final HttpClient client = Client.getService(HttpClient.class);

        final HttpResponse<InputStream> response = client.get(downloadRequest)
                .withoutContent()
                .streamBytes()
                .execute()
                .get(RuntimeManagerConstants.HTTP_TIMEOUT_IN_MS, TimeUnit.MILLISECONDS);

        final DownloadInputStream inputStream = DownloadInputStream.map(response.getContent(), response.getContentSize());

        if(downloadConsumer != null) {
            downloadConsumer.accept(inputStream);
        }

        SwingUtilities.invokeAndWait(() -> {
            final RuntimeDownloadDialog downloadDialog = new RuntimeDownloadDialog(remoteRuntime, inputStream);
            downloadDialog.setVisible(true);
        });

        try {
            LOG.debug("Trying to download and extract runtime");
            ZipUtil.unzip(inputStream, runtimePath);
        } catch (final Exception e) {
            final File runtimeDir = runtimePath.toFile();
            if (runtimeDir.exists()) {
                final boolean deleted = FileUtil.deleteDirectory(runtimeDir);
                if (deleted) {
                    throw e;
                } else {
                    throw new IOException("Error in Download + Can not delegte directory", e);
                }
            } else {
                throw new IOException("Error in runtime download", e);
            }
        }

        final LocalJavaRuntime newRuntime = new LocalJavaRuntime(remoteRuntime.getVersion(), remoteRuntime.getOperationSystem(), remoteRuntime.getVendor(), runtimePath);
        add(newRuntime);
        return newRuntime;
    }

    public LocalJavaRuntime getBestRuntime(final VersionString versionString) {
        return getBestRuntime(versionString, RuntimeManagerConstants.VENDOR_ANY);
    }

    public LocalJavaRuntime getBestRuntime(final VersionString versionString, final String vendor) {
        return getBestRuntime(versionString, vendor, OperationSystem.getLocalSystem());
    }

    public LocalJavaRuntime getBestRuntime(final VersionString versionString, final String vendor, final OperationSystem operationSystem) {
        Assert.requireNonNull(versionString, "versionString");
        Assert.requireNonBlank(vendor, "vendor");
        Assert.requireNonNull(operationSystem, "operationSystem");

        final String vendorForRequest = RuntimeManagerConfig.getInstance().isSpecificVendorEnabled() ? vendor : RuntimeManagerConfig.getInstance().getDefaultVendor();


        LOG.debug("Trying to find local Java runtime. Requested version: '" + versionString + "' Requested vendor: '" + vendorForRequest + "' requested os: '" + operationSystem + "'");

        return runtimes.stream()
                .filter(r -> r.isActive())
                .filter(r -> Objects.equals(operationSystem, r.getOperationSystem()))
                .filter(r -> Objects.equals(vendorForRequest, RuntimeManagerConstants.VENDOR_ANY) || Objects.equals(vendorForRequest, r.getVendor()))
                .filter(r -> versionString.contains(r.getVersion()))
                .filter(r -> Optional.ofNullable(RuntimeManagerConfig.getInstance().getSupportedVersionRange()).map(v -> v.contains(r.getVersion())).orElse(true))
                .sorted(new RuntimeVersionComparator(versionString).reversed())
                .findFirst()
                .orElse(null);
    }
}