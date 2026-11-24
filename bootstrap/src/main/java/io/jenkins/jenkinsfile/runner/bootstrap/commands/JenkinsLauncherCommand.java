package io.jenkins.jenkinsfile.runner.bootstrap.commands;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.jenkinsfile.runner.bootstrap.ClassLoaderBuilder;
import io.jenkins.jenkinsfile.runner.bootstrap.IApp;
import io.jenkins.jenkinsfile.runner.bootstrap.InvalidInputException;
import io.jenkins.jenkinsfile.runner.bootstrap.JenkinsStartupException;
import io.jenkins.jenkinsfile.runner.bootstrap.SideClassLoader;
import io.jenkins.jenkinsfile.runner.bootstrap.Util;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.Callable;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Command that requires launching a Jenkins instance,
 * including all required steps like Jenkins WAR and plugins resolution.
 */
public abstract class JenkinsLauncherCommand implements Callable<Integer> {

    public static final long CACHE_EXPIRE = System.currentTimeMillis() - 24 * 3600 * 1000;

    private static final Collection<Integer> REDIRECT_STATUSES = Arrays.asList(
        301, // Moved permanently
        302, // Found (HTTP 1.1) / Moved temporarily (HTTP 1.0)
        303, // See other
        307, // Temporarily Redirect
        308  // Permanent Redirect
    );

    @CommandLine.Mixin
    public JenkinsLauncherOptions launcherOptions;

    public JenkinsLauncherOptions getLauncherOptions() {
        return launcherOptions;
    }

    /**
     * Gets classname of the App which should be launched
     */
    @NonNull
    public abstract String getAppClassName();

    @Override
    public Integer call() {
        try {
            postConstruct();
            return runJenkinsfileRunnerApp();
        } catch (RuntimeException e) {
            // Categorized exceptions (InvalidInputException, JenkinsStartupException) propagate as-is
            throw e;
        } catch (Throwable e) {
            // Checked failures during startup/app execution are reported as unexpected errors
            // by the execution exception handler installed in Bootstrap
            throw new RuntimeException(e);
        }
    }

    /**
     * Directory used as cache for downloaded artifacts.
     */
    private File cache = new File(System.getProperty("user.home") + "/.jenkinsfile-runner/");

    public void postConstruct() throws IOException {
        final JenkinsLauncherOptions settings = getLauncherOptions();

        // Process the Jenkins version
        if (settings.version != null && settings.warDir != null) {
            throw new InvalidInputException("Error: --jenkins-war and --jenkins-version are mutually exclusive options");
        }
        if (settings.version != null && !Util.isJenkinsVersionSupported(settings.version)) {
            throw new InvalidInputException(String.format(
                    "Jenkins version [%s] not supported by this jenkinsfile-runner version (requires %s). %n",
                    settings.version,
                    Util.getMininumJenkinsVersion()));
        }
        if (settings.warDir == null) {
            // TODO: avoid modifying the option
            try {
                settings.warDir = getJenkinsWar(settings.version);
            } catch (IOException e) {
                throw new JenkinsStartupException("Failed to download the Jenkins WAR", e);
            }
        }
        // Explode war if necessary
        String warPath = settings.warDir.getAbsolutePath();
        if(FilenameUtils.getExtension(warPath).equals("war") && new File(warPath).isFile()) {
            System.out.println("Exploding " + warPath +  ", this might take some time.");
            try {
                settings.warDir = Util.explodeWar(warPath);
            } catch (IOException e) {
                throw new JenkinsStartupException("Failed to explode the Jenkins WAR: " + warPath, e);
            }
        }

        if (settings.pluginsDir == null) {
            // TODO: avoid modifying the option
            settings.pluginsDir = new File("plugins.txt");
        } else if (!settings.pluginsDir.exists()) {
            throw new InvalidInputException("invalid plugins file.");
        }

        if (settings.pluginsDir.isFile()) {
            File plugins_txt = settings.pluginsDir;
            // This is a plugin list file
            settings.pluginsDir = Files.createTempDirectory("plugins").toFile();
            for (String line : FileUtils.readLines(plugins_txt, UTF_8)) {
                String shortname = line;
                String version = "latest";

                int i = line.indexOf(':');
                if (i != -1) {
                    shortname = line.substring(0,i);
                    version = line.substring(i+1);
                }

                try {
                    installPlugin(settings.pluginsDir, shortname, version);
                } catch (IOException e) {
                    throw new JenkinsStartupException(
                            String.format("Failed to download plugin %s (%s)", shortname, version), e);
                }
            }
        }
    }

    private void copyURLToFile(URL url, File file) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        int status = connection.getResponseCode();

        // Connections won't automatically redirect across different protocols, i.e. going
        // from http to https. We have to handle that ourselves.
        if (REDIRECT_STATUSES.contains(status)) {
            String newUrl = connection.getHeaderField("Location");
            System.out.println("Following redirect...");
            connection = (HttpURLConnection) new URL(newUrl).openConnection();
        }

        try (InputStream stream = connection.getInputStream()) {
            FileUtils.copyInputStreamToFile(stream, file);
        }
    }

    private File getJenkinsWar(final @CheckForNull String requiredVersion) throws IOException {
        final String versionToUse;
        if (requiredVersion == null) {
            System.out.println("No explicit version has been selected, using latest LTS");

            File latestCore = new File(cache, "war/latest.txt");
            latestCore.getParentFile().mkdirs();
            // Check once a day
            if (!latestCore.exists() || latestCore.lastModified() < CACHE_EXPIRE) {
                copyURLToFile(URI.create("http://updates.jenkins.io/stable/latestCore.txt").toURL(), latestCore);
            }
            versionToUse = FileUtils.readFileToString(latestCore, StandardCharsets.US_ASCII);
        } else {
            versionToUse = requiredVersion;
        }

        System.out.printf("Running pipeline on jenkins %s%n", versionToUse);

        File war = new File(cache, String.format("war/%s/jenkins-war-%s.war", versionToUse, versionToUse));
        if (!war.exists()) {
            war.getParentFile().mkdirs();
            final URL url = new URL(getLauncherOptions().getMirrorURL(String.format("http://updates.jenkins.io/download/war/%s/jenkins.war", versionToUse)));
            System.out.printf("Downloading jenkins %s...%n", versionToUse);
            copyURLToFile(url, war);
        }

        return war;
    }

    private void installPlugin(File pluginsDir, String shortname, String version) throws IOException {
        final File install = new File(pluginsDir, shortname + ".jpi");
        File plugin = new File(cache, String.format("plugins/%s/%s-%s.hpi", shortname, shortname, version));
        if (!plugin.exists() || ("latest".equals(version) && plugin.lastModified() < CACHE_EXPIRE) ) {
            plugin.getParentFile().mkdirs();
            final URL url = new URL(getLauncherOptions().getMirrorURL(String.format("https://updates.jenkins.io/download/plugins/%s/%s/%s.hpi", shortname, version, shortname)));
            System.out.printf("Downloading jenkins plugin %s (%s)...%n", shortname, version);
            FileUtils.copyURLToFile(url, plugin);
        }

        Files.createSymbolicLink(install.toPath(), plugin.toPath());
    }

    public ClassLoader createJenkinsWarClassLoader() throws PrivilegedActionException {
        return AccessController.doPrivileged((PrivilegedExceptionAction<ClassLoader>) () -> new ClassLoaderBuilder(new SideClassLoader(getPlatformClassloader()))
                .collectJars(new File(getLauncherOptions().warDir, "WEB-INF/lib"))
                // In this mode we also take Jetty from the Jenkins core
                .collectJars(new File(getLauncherOptions().warDir, "executable/winstone.jar"))
                // servlet API needs to be visible to jenkins.war
                .collectJars(new File(getAppRepo(), "javax/servlet"))
                .make());
    }

    public ClassLoader createSetupClassLoader(ClassLoader jenkins) throws IOException {
        return new ClassLoaderBuilder(jenkins)
                .collectJars(getAppRepo())
                .collectJars(getSetupJarDir())
                // Payload should be skipped, otherwise it will be loaded with a wrong classloader when no bundled plugin jars
                // NOTE: Not relevant for slim JARs
                .excludeJars(new File(getAppRepo(), "io/jenkins/jenkinsfile-runner/payload"))
                .make();
    }

    public int runJenkinsfileRunnerApp() throws Throwable {
        String appClassName = getAppClassName();
        if (hasClass(appClassName)) {
            Class<?> c = Class.forName(appClassName);
            return  ((IApp) c.getDeclaredConstructor().newInstance()).run(this);
        }

        // Slim packaging (no bundled WAR or plugins)
        ClassLoader jenkins = createJenkinsWarClassLoader();
        ClassLoader setup = createSetupClassLoader(jenkins);

        Thread.currentThread().setContextClassLoader(setup);    // or should this be 'jenkins'?

        try {
            Class<?> c = setup.loadClass(appClassName);
            return ((IApp) c.getDeclaredConstructor().newInstance()).run(this);
        } catch (ClassNotFoundException e) {
            if (setup instanceof URLClassLoader) {
                throw new ClassNotFoundException(e.getMessage() + " not found in " + getAppRepo() + ","
                        + new File(getLauncherOptions().warDir, "WEB-INF/lib") + " " + Arrays.toString(((URLClassLoader) setup).getURLs()),
                        e);
            } else {
                throw e;
            }
        }
    }

    /**
     * In JDK 11, platform classes are not accessible by default but through the platform classloader.
     */
    private ClassLoader getPlatformClassloader() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if (isPostJava8()) {
            return (ClassLoader) ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null);
        }

        return null;
    }

    private static boolean isPostJava8() {
        String javaVersion = System.getProperty("java.version");
        return !javaVersion.startsWith("1.");
    }

    public boolean hasClass(String className) {
        try  {
            Class.forName(className);
            return true;
        }  catch (ClassNotFoundException | NoClassDefFoundError e) {
            return false;
        }
    }

    /**
     * Location of the app repo packaged by AppAssembler.
     * Depending on the configuration, it might include full or slim packaging.
     * @return Path to the app repo
     */
    public File getAppRepo() {
        return new File(System.getProperty("app.repo"));
    }

    /**
     * Location of the app repo packaged by AppAssembler.
     * Depending on the configuration, it might include full or slim packaging.
     * @return Path to the app repo
     */
    public File getLibDirectory() {
        if (launcherOptions.libPath != null) {
            return launcherOptions.libPath;
        }
        return new File(System.getProperty("app.repo"), "../lib");
    }

    public File getSetupJarDir() throws IOException {
        File setupJar = new File(getLibDirectory(), "setup/");
        if (!setupJar.exists()) {
            throw new IOException("Setup JAR is missing: " + setupJar);
        }
        return setupJar;
    }

    public File getPayloadJarDir() throws IOException {
        File payloadJar = new File(getLibDirectory(), "payload/");
        if (!payloadJar.exists()) {
            throw new IOException("Payload JAR is missing: " + payloadJar);
        }
        return payloadJar;
    }
}
