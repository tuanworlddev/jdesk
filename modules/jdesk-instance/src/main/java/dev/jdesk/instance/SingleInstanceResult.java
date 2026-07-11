package dev.jdesk.instance;
import java.util.Optional;
public record SingleInstanceResult(boolean primary, Optional<SingleInstanceSession> session) { }
