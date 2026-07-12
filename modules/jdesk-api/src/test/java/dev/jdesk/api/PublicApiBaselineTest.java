package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Freezes the exported Java API. Intentional breaking changes must update the baseline in
 * the same reviewed commit and be called out in the migration guide.
 */
class PublicApiBaselineTest {
    private static final Path BASELINE = Path.of(
            System.getProperty("jdesk.apiBaseline.path", "dev.jdesk.api.txt"));

    @Test
    void exportedApiMatchesReviewedBaseline() throws Exception {
        String actual = snapshot();
        if (Boolean.getBoolean("jdesk.apiBaseline.update")) {
            Files.createDirectories(BASELINE.getParent());
            Files.writeString(BASELINE, actual, StandardCharsets.UTF_8);
        }
        assertThat(BASELINE).as("public API baseline").isRegularFile();
        assertThat(actual).isEqualTo(Files.readString(BASELINE, StandardCharsets.UTF_8));
    }

    private static String snapshot() throws IOException, URISyntaxException,
            ClassNotFoundException {
        Path classes = Path.of(JDeskApplication.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path packageRoot = classes.resolve("dev/jdesk/api");
        List<Class<?>> types = new ArrayList<>();
        try (Stream<Path> files = Files.walk(packageRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                String relative = classes.relativize(file).toString();
                String className = relative.substring(0, relative.length() - ".class".length())
                        .replace('/', '.').replace('\\', '.');
                Class<?> type = Class.forName(className, false,
                        JDeskApplication.class.getClassLoader());
                if (isApi(type)) {
                    types.add(type);
                }
            }
        }
        types.sort(Comparator.comparing(Class::getName));
        StringBuilder out = new StringBuilder("# JDesk public API baseline v1\n");
        for (Class<?> type : types) {
            appendType(out, type);
        }
        return out.toString();
    }

    private static boolean isApi(Class<?> type) {
        int modifiers = type.getModifiers();
        return (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers))
                && !type.isSynthetic();
    }

    private static void appendType(StringBuilder out, Class<?> type) {
        out.append("\ntype ").append(modifiers(type.getModifiers()));
        if (type.isSealed()) {
            out.append(" sealed");
        }
        out.append(' ')
                .append(kind(type)).append(' ').append(type.getName());
        if (type.getTypeParameters().length > 0) {
            out.append('<').append(String.join(",",
                    Arrays.stream(type.getTypeParameters()).map(Object::toString).toList()))
                    .append('>');
        }
        if (type.getGenericSuperclass() != null && type.getSuperclass() != Object.class
                && !type.isEnum() && !type.isRecord()) {
            out.append(" extends ").append(type.getGenericSuperclass().getTypeName());
        }
        if (type.getGenericInterfaces().length > 0) {
            out.append(" implements ").append(String.join(",",
                    Arrays.stream(type.getGenericInterfaces())
                            .map(java.lang.reflect.Type::getTypeName).sorted().toList()));
        }
        appendAnnotations(out, type);
        out.append('\n');

        Arrays.stream(type.getDeclaredFields())
                .filter(field -> visible(field.getModifiers()) && !field.isSynthetic())
                .sorted(Comparator.comparing(Field::toGenericString))
                .forEach(field -> {
                    out.append("  field ").append(field.toGenericString());
                    appendAnnotations(out, field);
                    out.append('\n');
                });
        Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> visible(constructor.getModifiers())
                        && !constructor.isSynthetic())
                .sorted(Comparator.comparing(Constructor::toGenericString))
                .forEach(constructor -> {
                    out.append("  constructor ").append(constructor.toGenericString());
                    appendAnnotations(out, constructor);
                    out.append('\n');
                });
        Arrays.stream(type.getDeclaredMethods())
                .filter(method -> visible(method.getModifiers())
                        && !method.isSynthetic() && !method.isBridge())
                .sorted(Comparator.comparing(Method::toGenericString))
                .forEach(method -> {
                    out.append("  method ").append(method.toGenericString());
                    appendAnnotations(out, method);
                    out.append('\n');
                });
    }

    private static boolean visible(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static String modifiers(int value) {
        return Modifier.toString(value & (Modifier.PUBLIC | Modifier.PROTECTED
                | Modifier.ABSTRACT | Modifier.FINAL | Modifier.STATIC));
    }

    private static String kind(Class<?> type) {
        if (type.isAnnotation()) {
            return "annotation";
        }
        if (type.isEnum()) {
            return "enum";
        }
        if (type.isRecord()) {
            return "record";
        }
        if (type.isInterface()) {
            return "interface";
        }
        return "class";
    }

    private static void appendAnnotations(StringBuilder out, AnnotatedElement element) {
        List<String> annotations = Arrays.stream(element.getDeclaredAnnotations())
                .map(annotation -> annotation.toString().replace("dev.jdesk.api.", ""))
                .sorted().toList();
        if (!annotations.isEmpty()) {
            out.append(" annotations=").append(annotations);
        }
    }
}
