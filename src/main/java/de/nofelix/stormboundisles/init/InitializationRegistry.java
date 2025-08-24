package de.nofelix.stormboundisles.init;

import de.nofelix.stormboundisles.StormboundIslesMod;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Registry for discovering and calling initialization methods annotated with
 * {@link Initialize}.
 * <p>
 * This class scans for methods marked with the {@link Initialize} annotation
 * and executes them in order of priority (higher priority executes first).
 * <p>
 * Methods must be static, have no parameters, and return void to be eligible
 * for automatic
 * initialization.
 */
public class InitializationRegistry {
    private static boolean initialized = false;

    // Prevent instantiation
    private InitializationRegistry() {
    }

    /**
     * Discovers and executes all initialization methods in the given package and
     * its subpackages.
     * Methods are executed in order of their priority (higher priority first).
     *
     * @param basePackage The base package to scan for initialization methods
     */
    public static void initializeAll(String basePackage) {
        if (initialized) {
            StormboundIslesMod.LOGGER.warn("InitializationRegistry.initializeAll() called more than once");
            return;
        }

        StormboundIslesMod.LOGGER.info("Scanning for initialization methods in package: {}", basePackage);

        try {
            // Find all methods annotated with @Initialize
            Set<Method> initMethods = scanForAnnotatedMethods(Initialize.class, basePackage);

            if (initMethods.isEmpty()) {
                StormboundIslesMod.LOGGER.warn("No initialization methods found in package: {}", basePackage);
                return;
            }

            // Filter out non-static or methods with parameters - using modern pattern
            // matching
            Set<Method> validMethods = initMethods.stream()
                    .filter(method -> Modifier.isStatic(method.getModifiers()))
                    .filter(method -> method.getParameterCount() == 0)
                    .filter(method -> method.getReturnType() == void.class)
                    .collect(Collectors.toSet());

            if (initMethods.size() != validMethods.size()) {
                int invalidCount = initMethods.size() - validMethods.size();
                // Only build the costly string when warn logging is enabled
                String invalidList = StormboundIslesMod.LOGGER.isWarnEnabled()
                        ? initMethods.stream()
                                .filter(m -> !validMethods.contains(m))
                                .map(m -> m.getDeclaringClass().getSimpleName() + "." + m.getName())
                                .collect(Collectors.joining(", "))
                        : "";
                StormboundIslesMod.LOGGER.warn("{} initialization methods were invalid and will be skipped: {}",
                        invalidCount, invalidList);
            }

            // Sort by priority (higher priority first) - using modern method reference
            validMethods.stream()
                    .sorted(Comparator.comparingInt(InitializationRegistry::getPriorityInverse))
                    .forEach(method -> {
                        Initialize annotation = method.getAnnotation(Initialize.class);
                        String description = annotation.description().isEmpty()
                                ? ""
                                : " - " + annotation.description();

                        try {
                            StormboundIslesMod.LOGGER.debug("Calling initialization method: {}.{}{}",
                                    method.getDeclaringClass().getSimpleName(),
                                    method.getName(),
                                    description);

                            method.invoke(null);
                            StormboundIslesMod.LOGGER.trace("Successfully called {}.{}",
                                    method.getDeclaringClass().getSimpleName(), method.getName());
                        } catch (Exception e) {
                            StormboundIslesMod.LOGGER.error("Failed to call initialization method: {}.{}",
                                    method.getDeclaringClass().getSimpleName(), method.getName(), e);
                        }
                    });

            initialized = true;
            StormboundIslesMod.LOGGER.info("Successfully initialized {} methods", validMethods.size());
        } catch (Exception e) {
            StormboundIslesMod.LOGGER.error("Failed to scan for initialization methods", e);
        }
    }

    /**
     * Gets the inverse priority value for sorting (higher priority methods run
     * first).
     * 
     * @param method The method to get priority for
     * @return The negative priority value (for sorting in descending order)
     */
    private static int getPriorityInverse(Method method) {
        Initialize annotation = method.getAnnotation(Initialize.class);
        return -annotation.priority(); // Negative to sort higher values first
    }

    /**
     * Scans the classpath for classes in the given base package and returns methods
     * annotated with the provided annotation.
     *
     * This implementation uses the context ClassLoader to locate resources for the
     * package path, supports directory and JAR scanning, and attempts to load each
     * class to inspect its declared methods.
     */
    private static Set<Method> scanForAnnotatedMethods(Class<? extends Annotation> annotation, String basePackage) {
        String path = basePackage.replace('.', '/');
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = InitializationRegistry.class.getClassLoader();
        }

        Set<String> classNames = new HashSet<>();

        try {
            Enumeration<URL> resources = classLoader.getResources(path);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                String protocol = url.getProtocol();

                if ("file".equals(protocol)) {
                    String filePath = URLDecoder.decode(url.getFile(), "UTF-8");
                    File dir = new File(filePath);
                    if (dir.isDirectory()) {
                        findClassesInDirectory(basePackage, dir, classNames);
                    }
                } else if ("jar".equals(protocol)) {
                    try {
                        JarURLConnection jarConn = (JarURLConnection) url.openConnection();
                        JarFile jar = jarConn.getJarFile();
                        findClassesInJar(path, jar, classNames);
                    } catch (Exception e) {
                        StormboundIslesMod.LOGGER.debug("Failed to scan jar resource {}", url, e);
                    }
                } else {
                    // Fallback: try to handle jar:file: URLs or other schemes by parsing the URL
                    String external = url.toExternalForm();
                    if (external.contains(".jar!/")) {
                        String jarPath = external.substring(0, external.indexOf("!/"));
                        try {
                            if (jarPath.startsWith("jar:"))
                                jarPath = jarPath.substring(4);
                            if (jarPath.startsWith("file:"))
                                jarPath = jarPath.substring(5);
                            jarPath = URLDecoder.decode(jarPath, "UTF-8");
                            try (JarFile jar = new JarFile(jarPath)) {
                                findClassesInJar(path, jar, classNames);
                            }
                        } catch (Exception e) {
                            StormboundIslesMod.LOGGER.debug("Failed to parse nonstandard resource URL {}", url, e);
                        }
                    } else {
                        StormboundIslesMod.LOGGER.debug("Unsupported resource protocol while scanning: {}", protocol);
                    }
                }
            }
        } catch (IOException e) {
            StormboundIslesMod.LOGGER.error("Failed to scan classpath for package: {}", basePackage, e);
        }

        Set<Method> annotatedMethods = new HashSet<>();
        for (String className : classNames) {
            try {
                Class<?> cls = Class.forName(className, false, classLoader);
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.isAnnotationPresent(annotation)) {
                        annotatedMethods.add(m);
                    }
                }
            } catch (Exception t) {
                // Could be ClassNotFoundException, NoClassDefFoundError wrapped as Exception,
                // LinkageError not expected as Exception.
                StormboundIslesMod.LOGGER.debug("Failed to load class {} while scanning for annotations", className, t);
            }
        }

        return annotatedMethods;
    }

    private static void findClassesInDirectory(String packageName, File directory, Set<String> classNames) {
        if (!directory.exists())
            return;
        File[] files = directory.listFiles();
        if (files == null)
            return;
        for (File file : files) {
            if (file.isDirectory()) {
                findClassesInDirectory(packageName + "." + file.getName(), file, classNames);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + '.' + file.getName().substring(0, file.getName().length() - 6);
                classNames.add(className);
            }
        }
    }

    private static void findClassesInJar(String path, JarFile jar, Set<String> classNames) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.startsWith(path) && name.endsWith(".class") && !entry.isDirectory()) {
                String className = name.replace('/', '.').substring(0, name.length() - 6);
                classNames.add(className);
            }
        }
    }

    /**
     * Resets the initialization status, allowing initializeAll to be called again.
     * This is primarily for testing purposes.
     */
    public static void reset() {
        initialized = false;
    }
}