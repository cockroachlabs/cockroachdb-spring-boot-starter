package com.cockroachlabs.data;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the null-safety conventions that NullAway can't (see "Null safety, the Spring way" in
 * {@code docs/architecture.md}):
 * <ul>
 *   <li>every package of the starter is {@code @NullMarked};</li>
 *   <li>every override of a Spring (or AOP Alliance) method keeps Spring's nullness, return values
 *       included: NullAway rejects a parameter that drops Spring's {@code @Nullable}, but accepts a
 *       return value narrowed to non-null;</li>
 *   <li>every {@code @Around} advice returns {@code @Nullable Object}, since it returns whatever
 *       {@code ProceedingJoinPoint.proceed()} does, and AspectJ isn't null-annotated.</li>
 * </ul>
 * It loads the starter's compiled classes, with the optional dependencies they reference, in a class
 * loader of their own (from the {@code nullSafety.classpath} system property that the build sets),
 * and reads JSpecify's annotations, which are retained at runtime. Annotations are therefore matched
 * by name.
 */
class NullSafetyConventionsTest {

    private static final String NULL_MARKED = "org.jspecify.annotations.NullMarked";
    private static final String NULLABLE = "org.jspecify.annotations.Nullable";
    private static final String AROUND = "org.aspectj.lang.annotation.Around";

    private static final List<Class<?>> classes = new ArrayList<>();
    private static ClassLoader loader = NullSafetyConventionsTest.class.getClassLoader();

    @BeforeAll
    static void loadStarterClasses() throws IOException {
        String classpath = System.getProperty("nullSafety.classpath");
        assertThat(classpath).as("nullSafety.classpath system property (set by the Gradle build)").isNotBlank();
        List<Path> entries = Stream.of(classpath.split(File.pathSeparator)).map(Path::of).toList();
        URL[] urls = new URL[entries.size()];
        for (int i = 0; i < urls.length; i++) {
            urls[i] = entries.get(i).toUri().toURL();
        }
        loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());

        List<String> unloadable = new ArrayList<>();
        for (Path entry : entries) {
            if (Files.isDirectory(entry.resolve("com/cockroachlabs"))) {
                load(entry, unloadable);
            }
        }
        assertThat(unloadable).as("starter classes that can't be loaded").isEmpty();
        assertThat(classes).as("starter classes found").isNotEmpty();
    }

    private static void load(Path root, List<String> unloadable) throws IOException {
        List<String> names;
        try (Stream<Path> files = Files.walk(root)) {
            names = files.map(root::relativize)
                    .map(Path::toString)
                    .filter(name -> name.endsWith(".class") && name.startsWith("com/cockroachlabs/"))
                    .filter(name -> !name.endsWith("package-info.class"))
                    .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                    .sorted()
                    .toList();
        }
        for (String name : names) {
            try {
                Class<?> type = Class.forName(name, false, loader);
                type.getDeclaredMethods(); // resolves the types in its signatures
                classes.add(type);
            } catch (LinkageError | ClassNotFoundException ex) {
                unloadable.add(name + ": " + ex);
            }
        }
    }

    @Test
    void everyPackageIsNullMarked() {
        Set<String> packages = new TreeSet<>();
        for (Class<?> type : classes) {
            packages.add(type.getPackageName());
        }
        List<String> unmarked = new ArrayList<>();
        for (String name : packages) {
            if (!isNullMarked(name)) {
                unmarked.add(name);
            }
        }
        assertThat(unmarked).as("packages without a @NullMarked package-info.java").isEmpty();
    }

    @Test
    void overridesOfSpringApisKeepSpringsNullness() {
        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        for (Class<?> type : classes) {
            for (Method method : type.getDeclaredMethods()) {
                if (!overridable(method)) {
                    continue;
                }
                for (Method overridden : overriddenMethods(type, method)) {
                    String owner = overridden.getDeclaringClass().getName();
                    if (!owner.startsWith("org.springframework.") && !owner.startsWith("org.aopalliance.")) {
                        continue;
                    }
                    checked++;
                    compare(method, overridden, mismatches);
                }
            }
        }
        assertThat(checked).as("overrides of Spring APIs found").isPositive();
        assertThat(mismatches).as("overrides whose nullness differs from Spring's").isEmpty();
    }

    @Test
    void aroundAdviceReturnsNullable() {
        List<String> nonNull = new ArrayList<>();
        for (Class<?> type : classes) {
            for (Method method : type.getDeclaredMethods()) {
                if (hasAnnotation(method.getAnnotations(), AROUND) && !isNullable(method.getAnnotatedReturnType())) {
                    nonNull.add(type.getName() + "." + method.getName());
                }
            }
        }
        assertThat(nonNull).as("@Around advice not returning @Nullable Object").isEmpty();
    }

    private static boolean isNullMarked(String packageName) {
        try {
            return hasAnnotation(Class.forName(packageName + ".package-info", false, loader).getAnnotations(), NULL_MARKED);
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private static boolean overridable(Method method) {
        int modifiers = method.getModifiers();
        return !method.isSynthetic() && !method.isBridge()
                && !Modifier.isStatic(modifiers) && !Modifier.isPrivate(modifiers);
    }

    /** The methods {@code method} overrides, in every superclass and interface of {@code type}. */
    private static List<Method> overriddenMethods(Class<?> type, Method method) {
        List<Method> overridden = new ArrayList<>();
        Deque<Class<?>> pending = new ArrayDeque<>(supertypes(type));
        Set<Class<?>> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            Class<?> supertype = pending.poll();
            if (!seen.add(supertype)) {
                continue;
            }
            for (Method candidate : supertype.getDeclaredMethods()) {
                if (overridable(candidate) && overrides(method, candidate)) {
                    overridden.add(candidate);
                }
            }
            pending.addAll(supertypes(supertype));
        }
        return overridden;
    }

    private static List<Class<?>> supertypes(Class<?> type) {
        List<Class<?>> supertypes = new ArrayList<>(List.of(type.getInterfaces()));
        if (type.getSuperclass() != null) {
            supertypes.add(type.getSuperclass());
        }
        return supertypes;
    }

    private static boolean overrides(Method method, Method candidate) {
        if (!candidate.getName().equals(method.getName())
                || candidate.getParameterCount() != method.getParameterCount()
                || !candidate.getReturnType().isAssignableFrom(method.getReturnType())) {
            return false;
        }
        for (int i = 0; i < method.getParameterCount(); i++) {
            if (!candidate.getParameterTypes()[i].isAssignableFrom(method.getParameterTypes()[i])) {
                return false;
            }
        }
        return true;
    }

    private static void compare(Method method, Method overridden, List<String> mismatches) {
        String where = method.getDeclaringClass().getName() + "." + method.getName()
                + " (overrides " + overridden.getDeclaringClass().getName() + ")";
        compare(where + " return", method.getAnnotatedReturnType(), overridden.getAnnotatedReturnType(), mismatches);
        for (int i = 0; i < method.getParameterCount(); i++) {
            compare(where + " parameter " + i, method.getAnnotatedParameterTypes()[i],
                    overridden.getAnnotatedParameterTypes()[i], mismatches);
        }
        TypeVariable<Method>[] ours = method.getTypeParameters();
        TypeVariable<Method>[] spring = overridden.getTypeParameters();
        if (ours.length == spring.length) {
            for (int i = 0; i < ours.length; i++) {
                AnnotatedType[] ourBounds = ours[i].getAnnotatedBounds();
                AnnotatedType[] springBounds = spring[i].getAnnotatedBounds();
                for (int j = 0; j < Math.min(ourBounds.length, springBounds.length); j++) {
                    compare(where + " type variable " + ours[i].getName(), ourBounds[j], springBounds[j], mismatches);
                }
            }
        }
    }

    /** Compares the nullness of two types, and of their type arguments and array components. */
    private static void compare(String where, AnnotatedType ours, AnnotatedType spring, List<String> mismatches) {
        if (isNullable(ours) != isNullable(spring)) {
            mismatches.add(where + ": " + (isNullable(spring) ? "Spring's is @Nullable, ours isn't" : "ours is @Nullable, Spring's isn't"));
        }
        if (ours instanceof AnnotatedParameterizedType ourType && spring instanceof AnnotatedParameterizedType springType) {
            AnnotatedType[] ourArguments = ourType.getAnnotatedActualTypeArguments();
            AnnotatedType[] springArguments = springType.getAnnotatedActualTypeArguments();
            if (ourArguments.length == springArguments.length) {
                for (int i = 0; i < ourArguments.length; i++) {
                    compare(where + " type argument " + i, ourArguments[i], springArguments[i], mismatches);
                }
            }
        }
        if (ours instanceof AnnotatedArrayType ourArray && spring instanceof AnnotatedArrayType springArray) {
            compare(where + " array component", ourArray.getAnnotatedGenericComponentType(),
                    springArray.getAnnotatedGenericComponentType(), mismatches);
        }
    }

    private static boolean isNullable(AnnotatedType type) {
        return hasAnnotation(type.getAnnotations(), NULLABLE);
    }

    private static boolean hasAnnotation(Annotation[] annotations, String name) {
        for (Annotation annotation : annotations) {
            if (annotation.annotationType().getName().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
