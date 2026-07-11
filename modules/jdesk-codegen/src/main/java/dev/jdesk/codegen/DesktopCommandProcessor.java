package dev.jdesk.codegen;

import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.PublicDesktopCommand;
import dev.jdesk.api.RequiresCapability;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/**
 * Compile-time discovery of {@code @DesktopCommand} methods (ADR-005; spec section 11).
 *
 * <p>For each public top-level service class it generates {@code <Service>Commands} with a
 * static {@code create(Service)} returning a {@link dev.jdesk.api.CommandRegistry}. When a
 * package declares more than one service, a constant {@code JDeskCommands.combine(...)}
 * aggregator is generated in that package. At the end of processing it emits the
 * TypeScript bindings ({@code types.ts}, {@code commands.ts}) to
 * {@code CLASS_OUTPUT/jdesk-ts/}, or to the directory given by
 * {@code -Ajdesk.ts.outputDir=...}.
 *
 * <p>Output is deterministic: byte-identical for identical input. No timestamps, no
 * absolute paths; commands are sorted by wire name and record components keep their
 * declaration order.
 */
@SupportedOptions(DesktopCommandProcessor.OPTION_TS_OUTPUT_DIR)
public final class DesktopCommandProcessor extends AbstractProcessor {

    static final String OPTION_TS_OUTPUT_DIR = "jdesk.ts.outputDir";

    private static final Pattern COMMAND_NAME =
            Pattern.compile("[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)*");
    private static final int MAX_COMMAND_NAME_LENGTH = 128;

    /** Wire name to declaring location, for duplicate detection across the compilation. */
    private final Map<String, String> commandOwners = new HashMap<>();
    /** All valid commands, accumulated across rounds for TypeScript emission. */
    private final List<CommandModel> commands = new ArrayList<>();
    /** Validated DTO records by canonical name, insertion-ordered; sorted at emission. */
    private final Map<String, RecordModel> recordsByQualifiedName = new LinkedHashMap<>();
    /** TypeScript interface name to canonical Java name, for collision detection. */
    private final Map<String, String> tsNameToQualifiedName = new HashMap<>();
    /** Canonical names of records that already failed validation (avoids duplicate errors). */
    private final Set<String> invalidRecords = new HashSet<>();
    /** Generated service registry count per package, to trigger the aggregator. */
    private final Map<String, Integer> servicesPerPackage = new HashMap<>();
    private final Set<String> aggregatorPackages = new HashSet<>();
    private boolean hadError;
    private boolean tsEmitted;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("dev.jdesk.api.DesktopCommand");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            emitTypeScript();
            return false;
        }
        TypeElement annotation =
                processingEnv.getElementUtils().getTypeElement("dev.jdesk.api.DesktopCommand");
        if (annotation == null) {
            return false;
        }
        Map<String, TypeElement> servicesByName = new TreeMap<>();
        Map<String, List<ExecutableElement>> methodsByService = new TreeMap<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
            if (element.getKind() != ElementKind.METHOD
                    || !(element.getEnclosingElement() instanceof TypeElement service)) {
                continue;
            }
            String key = service.getQualifiedName().toString();
            servicesByName.put(key, service);
            methodsByService.computeIfAbsent(key, k -> new ArrayList<>())
                    .add((ExecutableElement) element);
        }
        for (Map.Entry<String, TypeElement> entry : servicesByName.entrySet()) {
            processService(entry.getValue(), methodsByService.get(entry.getKey()));
        }
        return true;
    }

    private void processService(TypeElement service, List<ExecutableElement> methods) {
        boolean valid = validateServiceClass(service);

        // Deterministic method order: by wire name, then by method name.
        methods.sort(Comparator
                .comparing((ExecutableElement m) -> m.getAnnotation(DesktopCommand.class).value())
                .thenComparing(m -> m.getSimpleName().toString()));

        // Overloaded @DesktopCommand methods produce ambiguous generated handler references.
        Map<String, Integer> methodNameCounts = new TreeMap<>();
        for (ExecutableElement method : methods) {
            methodNameCounts.merge(method.getSimpleName().toString(), 1, Integer::sum);
        }
        for (ExecutableElement method : methods) {
            if (methodNameCounts.get(method.getSimpleName().toString()) > 1) {
                error(method, "Overloaded @DesktopCommand methods are not supported: '"
                        + method.getSimpleName()
                        + "' is declared more than once in " + service.getQualifiedName()
                        + " and would produce an ambiguous generated name");
                valid = false;
            }
        }

        List<CommandModel> serviceCommands = new ArrayList<>();
        for (ExecutableElement method : methods) {
            CommandModel model = validateMethod(method);
            if (model == null) {
                valid = false;
            } else {
                serviceCommands.add(model);
            }
        }
        if (!valid || serviceCommands.isEmpty()) {
            return;
        }
        serviceCommands.sort(Comparator.comparing(CommandModel::name));
        generateServiceRegistry(service, serviceCommands);
        commands.addAll(serviceCommands);
    }

    private boolean validateServiceClass(TypeElement service) {
        boolean valid = true;
        if (service.getKind() != ElementKind.CLASS) {
            error(service, "@DesktopCommand methods must be declared in a class, not a "
                    + service.getKind().toString().toLowerCase(java.util.Locale.ROOT));
            valid = false;
        }
        if (service.getNestingKind() != NestingKind.TOP_LEVEL) {
            error(service, "Class declaring @DesktopCommand methods must be a top-level class");
            valid = false;
        }
        if (!service.getModifiers().contains(Modifier.PUBLIC)) {
            error(service, "Class declaring @DesktopCommand methods must be public");
            valid = false;
        }
        return valid;
    }

    /** Validates one annotated method; returns its model or {@code null} after errors. */
    private CommandModel validateMethod(ExecutableElement method) {
        boolean valid = true;
        TypeElement service = (TypeElement) method.getEnclosingElement();
        String name = method.getAnnotation(DesktopCommand.class).value();

        if (name.length() > MAX_COMMAND_NAME_LENGTH || !COMMAND_NAME.matcher(name).matches()) {
            error(method, "Invalid command name \"" + name + "\": must be dot-separated "
                    + "lowerCamel segments matching [a-z][a-zA-Z0-9]* with at most "
                    + MAX_COMMAND_NAME_LENGTH + " characters, e.g. \"greeting.greet\"");
            valid = false;
        } else {
            String location = service.getQualifiedName() + "#" + method.getSimpleName();
            String previous = commandOwners.putIfAbsent(name, location);
            if (previous != null) {
                error(method, "Duplicate command name \"" + name + "\": already declared by "
                        + previous);
                valid = false;
            } else {
                for (String existing : new TreeSet<>(commandOwners.keySet())) {
                    if (!existing.equals(name)
                            && (existing.startsWith(name + ".") || name.startsWith(existing + "."))) {
                        error(method, "Command name \"" + name + "\" conflicts with \"" + existing
                                + "\" declared by " + commandOwners.get(existing)
                                + ": a command name cannot also be a namespace of another command");
                        valid = false;
                    }
                }
            }
        }

        RequiresCapability requires = method.getAnnotation(RequiresCapability.class);
        boolean isPublicCommand = method.getAnnotation(PublicDesktopCommand.class) != null;
        Optional<String> capability = Optional.empty();
        if (requires != null && isPublicCommand) {
            error(method, "@RequiresCapability and @PublicDesktopCommand are mutually exclusive; "
                    + "declare exactly one");
            valid = false;
        } else if (requires == null && !isPublicCommand) {
            error(method, "Command must declare @RequiresCapability, or opt out explicitly with "
                    + "@PublicDesktopCommand (commands are deny-by-default)");
            valid = false;
        } else if (requires != null) {
            capability = Optional.of(requires.value());
        }

        if (!method.getModifiers().contains(Modifier.PUBLIC)) {
            error(method, "@DesktopCommand method must be public");
            valid = false;
        }
        if (method.getModifiers().contains(Modifier.STATIC)) {
            error(method, "@DesktopCommand method must be an instance method");
            valid = false;
        }

        Set<String> referencedRecords = new TreeSet<>();

        String tsResponse = null;
        TypeMirror returnType = method.getReturnType();
        if (returnType.getKind() != TypeKind.DECLARED
                || !qualifiedNameOf(returnType).equals("java.util.concurrent.CompletionStage")) {
            error(method, "Command method must return java.util.concurrent.CompletionStage<Res>");
            valid = false;
        } else {
            List<? extends TypeMirror> typeArguments =
                    ((DeclaredType) returnType).getTypeArguments();
            if (typeArguments.size() != 1) {
                error(method, "Command method must return CompletionStage<Res> with an explicit "
                        + "response type argument (raw CompletionStage is not supported)");
                valid = false;
            } else if (typeArguments.get(0).getKind() == TypeKind.WILDCARD) {
                error(method, "Wildcard response types are not supported; declare "
                        + "CompletionStage<Res> with a concrete Res");
                valid = false;
            } else {
                TopLevelType response =
                        validateTopLevelType(typeArguments.get(0), method, true, referencedRecords);
                if (response == null) {
                    valid = false;
                } else {
                    tsResponse = response.ts();
                }
            }
        }

        CommandModel.Shape shape = CommandModel.Shape.NO_ARGS;
        String requestCanonicalName = "java.lang.Void";
        String tsRequest = null;
        List<? extends VariableElement> parameters = method.getParameters();
        if (parameters.size() == 1) {
            if (isInvocationContext(parameters.get(0).asType())) {
                shape = CommandModel.Shape.CONTEXT_ONLY;
            } else {
                error(method, "A single-parameter command method must take "
                        + "dev.jdesk.api.InvocationContext; payload commands are declared as "
                        + "(Request, InvocationContext)");
                valid = false;
            }
        } else if (parameters.size() == 2) {
            shape = CommandModel.Shape.REQUEST_AND_CONTEXT;
            if (!isInvocationContext(parameters.get(1).asType())) {
                error(method, "The second command method parameter must be "
                        + "dev.jdesk.api.InvocationContext");
                valid = false;
            }
            TopLevelType request =
                    validateTopLevelType(parameters.get(0).asType(), method, false, referencedRecords);
            if (request == null) {
                valid = false;
            } else {
                requestCanonicalName = request.canonicalName();
                tsRequest = request.ts();
            }
        } else if (!parameters.isEmpty()) {
            error(method, "Command method must have at most two parameters: "
                    + "(Request, InvocationContext), (InvocationContext), or ()");
            valid = false;
        }

        if (!valid) {
            return null;
        }
        return new CommandModel(name, capability, shape, method.getSimpleName().toString(),
                requestCanonicalName, tsRequest, tsResponse, List.copyOf(referencedRecords));
    }

    /** Canonical Java name plus rendered TypeScript type for a top-level request/response. */
    private record TopLevelType(String canonicalName, String ts) {
    }

    private TopLevelType validateTopLevelType(TypeMirror type, ExecutableElement method,
            boolean isResponse, Set<String> referencedRecords) {
        String role = isResponse ? "response" : "request";
        String allowed = isResponse
                ? "a public record, String, a boxed primitive, or Void"
                : "a public record, String, or a boxed primitive";
        if (type.getKind() != TypeKind.DECLARED) {
            error(method, "Command " + role + " type " + type + " is not supported; it must be "
                    + allowed);
            return null;
        }
        TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
        String qualifiedName = element.getQualifiedName().toString();
        if (qualifiedName.equals("java.lang.Void")) {
            if (isResponse) {
                return new TopLevelType("java.lang.Void", "void");
            }
            error(method, "Void is not a valid request type; omit the request parameter instead");
            return null;
        }
        String forbidden = forbiddenTypeMessage((DeclaredType) type, qualifiedName);
        if (forbidden != null) {
            error(method, "Command " + role + " type: " + forbidden);
            return null;
        }
        String scalar = scalarTsType(qualifiedName);
        if (scalar != null) {
            return new TopLevelType(qualifiedName, scalar);
        }
        if (element.getKind() == ElementKind.RECORD) {
            if (!validateRecord(element, method, new ArrayDeque<>(), referencedRecords)) {
                return null;
            }
            return new TopLevelType(qualifiedName, element.getSimpleName().toString());
        }
        error(method, "Command " + role + " type " + qualifiedName + " is not supported; it must "
                + "be " + allowed + " (wrap collections in a record)");
        return null;
    }

    /**
     * Validates a DTO record and registers it for TypeScript emission. Reports recursion,
     * accessibility, genericity, and component type violations on the command method.
     */
    private boolean validateRecord(TypeElement record, ExecutableElement method,
            Deque<String> stack, Set<String> referencedRecords) {
        String qualifiedName = record.getQualifiedName().toString();
        if (stack.contains(qualifiedName)) {
            error(method, "Recursive record type " + qualifiedName + " is not supported in a "
                    + "command contract (it references itself, directly or transitively)");
            return false;
        }
        if (invalidRecords.contains(qualifiedName)) {
            return false;
        }
        String tsName = record.getSimpleName().toString();
        if (recordsByQualifiedName.containsKey(qualifiedName)) {
            referencedRecords.add(tsName);
            return true;
        }
        boolean valid = true;
        if (!record.getModifiers().contains(Modifier.PUBLIC)) {
            error(method, "DTO record " + qualifiedName + " must be public");
            valid = false;
        }
        for (Element enclosing = record.getEnclosingElement();
                enclosing instanceof TypeElement enclosingType;
                enclosing = enclosingType.getEnclosingElement()) {
            if (!enclosingType.getModifiers().contains(Modifier.PUBLIC)) {
                error(method, "DTO record " + qualifiedName + " is nested in non-public type "
                        + enclosingType.getQualifiedName() + "; every enclosing type must be public");
                valid = false;
            }
        }
        if (!record.getTypeParameters().isEmpty()) {
            error(method, "Generic record " + qualifiedName + " is not supported in a command "
                    + "contract");
            valid = false;
        }
        String existing = tsNameToQualifiedName.putIfAbsent(tsName, qualifiedName);
        if (existing != null && !existing.equals(qualifiedName)) {
            error(method, "TypeScript type name collision: " + qualifiedName + " and " + existing
                    + " share the simple name '" + tsName + "'; rename one of the records");
            valid = false;
        }
        List<RecordModel.Field> fields = new ArrayList<>();
        stack.push(qualifiedName);
        for (RecordComponentElement component : record.getRecordComponents()) {
            String ts = validateComponentType(component.asType(), method, stack, referencedRecords,
                    qualifiedName + "." + component.getSimpleName());
            if (ts == null) {
                valid = false;
            } else {
                fields.add(new RecordModel.Field(component.getSimpleName().toString(), ts));
            }
        }
        stack.pop();
        if (!valid) {
            invalidRecords.add(qualifiedName);
            return false;
        }
        recordsByQualifiedName.put(qualifiedName, new RecordModel(tsName, qualifiedName, fields));
        referencedRecords.add(tsName);
        return true;
    }

    /** Validates one type inside a record; returns its TypeScript rendering or {@code null}. */
    private String validateComponentType(TypeMirror type, ExecutableElement method,
            Deque<String> stack, Set<String> referencedRecords, String where) {
        switch (type.getKind()) {
            case BOOLEAN:
                return "boolean";
            case BYTE, SHORT, INT, LONG, FLOAT, DOUBLE:
                return "number";
            case CHAR:
                return "string";
            case ARRAY:
                error(method, where + ": arrays are not supported in a command contract; use "
                        + "java.util.List");
                return null;
            case WILDCARD:
                error(method, where + ": wildcard types are not supported in a command contract");
                return null;
            case TYPEVAR:
                error(method, where + ": type variables are not supported in a command contract");
                return null;
            case DECLARED:
                break;
            default:
                error(method, where + ": unsupported type " + type + " in a command contract");
                return null;
        }
        DeclaredType declared = (DeclaredType) type;
        TypeElement element = (TypeElement) declared.asElement();
        String qualifiedName = element.getQualifiedName().toString();
        String forbidden = forbiddenTypeMessage(declared, qualifiedName);
        if (forbidden != null) {
            error(method, where + ": " + forbidden);
            return null;
        }
        String scalar = scalarTsType(qualifiedName);
        if (scalar != null) {
            return scalar;
        }
        switch (qualifiedName) {
            case "java.util.List", "java.util.Optional" -> {
                List<? extends TypeMirror> typeArguments = declared.getTypeArguments();
                if (typeArguments.isEmpty()) {
                    error(method, where + ": raw " + qualifiedName + " is not supported; declare "
                            + "an explicit type argument");
                    return null;
                }
                String ts = validateComponentType(typeArguments.get(0), method, stack,
                        referencedRecords, where);
                if (ts == null) {
                    return null;
                }
                if (qualifiedName.equals("java.util.List")) {
                    return ts.contains("|") ? "(" + ts + ")[]" : ts + "[]";
                }
                return ts + " | null";
            }
            case "java.util.Map" -> {
                List<? extends TypeMirror> typeArguments = declared.getTypeArguments();
                if (typeArguments.size() != 2) {
                    error(method, where + ": raw java.util.Map is not supported; declare "
                            + "Map<String, X>");
                    return null;
                }
                TypeMirror key = typeArguments.get(0);
                if (key.getKind() != TypeKind.DECLARED
                        || !qualifiedNameOf(key).equals("java.lang.String")) {
                    error(method, where + ": Map keys must be String in a command contract");
                    return null;
                }
                String ts = validateComponentType(typeArguments.get(1), method, stack,
                        referencedRecords, where);
                return ts == null ? null : "Record<string, " + ts + ">";
            }
            default -> {
                if (element.getKind() == ElementKind.RECORD) {
                    return validateRecord(element, method, stack, referencedRecords)
                            ? element.getSimpleName().toString() : null;
                }
                if (qualifiedName.equals("java.lang.Void")) {
                    error(method, where + ": Void is only allowed as a command response type");
                    return null;
                }
                error(method, where + ": unsupported type " + qualifiedName + " in a command "
                        + "contract; allowed: public records, String, primitives and boxed "
                        + "primitives, List<X>, Map<String, X>, Optional<X>");
                return null;
            }
        }
    }

    /** Message for explicitly forbidden types, or {@code null} when the type is not banned. */
    private String forbiddenTypeMessage(DeclaredType type, String qualifiedName) {
        switch (qualifiedName) {
            case "java.lang.Object":
                return "raw Object is not allowed in a command contract";
            case "java.lang.Class":
                return "Class is not allowed in a command contract";
            case "java.lang.reflect.Method":
                return "java.lang.reflect.Method is not allowed in a command contract";
            case "java.lang.foreign.MemorySegment":
                return "native handle types (java.lang.foreign.MemorySegment) are not allowed in "
                        + "a command contract";
            default:
                TypeElement throwable =
                        processingEnv.getElementUtils().getTypeElement("java.lang.Throwable");
                if (throwable != null && processingEnv.getTypeUtils().isAssignable(
                        processingEnv.getTypeUtils().erasure(type), throwable.asType())) {
                    return "Throwable types (" + qualifiedName + ") are not allowed in a command "
                            + "contract";
                }
                return null;
        }
    }

    private static String scalarTsType(String qualifiedName) {
        return switch (qualifiedName) {
            case "java.lang.String", "java.lang.Character" -> "string";
            case "java.lang.Boolean" -> "boolean";
            case "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
                 "java.lang.Float", "java.lang.Double" -> "number";
            default -> null;
        };
    }

    private boolean isInvocationContext(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED
                && qualifiedNameOf(type).equals("dev.jdesk.api.InvocationContext");
    }

    private static String qualifiedNameOf(TypeMirror declaredType) {
        return ((TypeElement) ((DeclaredType) declaredType).asElement())
                .getQualifiedName().toString();
    }

    private void generateServiceRegistry(TypeElement service, List<CommandModel> serviceCommands) {
        String packageName = processingEnv.getElementUtils().getPackageOf(service)
                .getQualifiedName().toString();
        String generatedSimpleName = service.getSimpleName() + "Commands";
        String generatedQualifiedName =
                packageName.isEmpty() ? generatedSimpleName : packageName + "." + generatedSimpleName;
        writeJavaSource(generatedQualifiedName,
                JavaEmitter.serviceRegistry(packageName, generatedSimpleName,
                        service.getQualifiedName().toString(), serviceCommands),
                service);
        int count = servicesPerPackage.merge(packageName, 1, Integer::sum);
        if (count == 2 && aggregatorPackages.add(packageName)) {
            String aggregatorName =
                    packageName.isEmpty() ? "JDeskCommands" : packageName + ".JDeskCommands";
            writeJavaSource(aggregatorName, JavaEmitter.aggregator(packageName), service);
        }
    }

    private void writeJavaSource(String qualifiedName, String content, Element originating) {
        try {
            JavaFileObject file =
                    processingEnv.getFiler().createSourceFile(qualifiedName, originating);
            try (Writer writer = file.openWriter()) {
                writer.write(content);
            }
        } catch (IOException e) {
            error(originating, "Failed to write generated source " + qualifiedName + ": "
                    + e.getMessage());
        }
    }

    private void emitTypeScript() {
        if (tsEmitted || hadError || commands.isEmpty()) {
            return;
        }
        tsEmitted = true;
        List<CommandModel> sortedCommands = new ArrayList<>(commands);
        sortedCommands.sort(Comparator.comparing(CommandModel::name));
        List<RecordModel> sortedRecords = new ArrayList<>(recordsByQualifiedName.values());
        sortedRecords.sort(Comparator.comparing(RecordModel::tsName));
        String types = TsEmitter.typesTs(sortedRecords);
        String commandsTs = TsEmitter.commandsTs(sortedCommands);
        String outputDir = processingEnv.getOptions().get(OPTION_TS_OUTPUT_DIR);
        try {
            if (outputDir != null && !outputDir.isBlank()) {
                Path directory = Path.of(outputDir);
                Files.createDirectories(directory);
                Files.write(directory.resolve("types.ts"), types.getBytes(StandardCharsets.UTF_8));
                Files.write(directory.resolve("commands.ts"),
                        commandsTs.getBytes(StandardCharsets.UTF_8));
            } else {
                writeResource("jdesk-ts/types.ts", types);
                writeResource("jdesk-ts/commands.ts", commandsTs);
            }
        } catch (IOException e) {
            hadError = true;
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to write generated TypeScript: " + e.getMessage());
        }
    }

    private void writeResource(String relativeName, String content) throws IOException {
        FileObject file = processingEnv.getFiler()
                .createResource(StandardLocation.CLASS_OUTPUT, "", relativeName);
        try (OutputStream out = file.openOutputStream()) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void error(Element element, String message) {
        hadError = true;
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
