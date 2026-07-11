package dev.jdesk.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable registry of all commands, normally produced by generated code
 * ({@code GeneratedCommands.create(...)}). Duplicate names are a construction error.
 */
public final class CommandRegistry {
    private final Map<String, CommandDefinition> commands;

    private CommandRegistry(Map<String, CommandDefinition> commands) {
        this.commands = Map.copyOf(commands);
    }

    public static CommandRegistry of(CommandDefinition... definitions) {
        Map<String, CommandDefinition> map = new LinkedHashMap<>();
        for (CommandDefinition definition : definitions) {
            CommandDefinition previous = map.putIfAbsent(definition.name(), definition);
            if (previous != null) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "Duplicate command name: " + definition.name());
            }
        }
        return new CommandRegistry(map);
    }

    public Optional<CommandDefinition> find(String name) {
        return Optional.ofNullable(commands.get(name));
    }

    public Set<String> commandNames() {
        return commands.keySet();
    }

    public int size() {
        return commands.size();
    }
}
