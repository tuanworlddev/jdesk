package @PACKAGE@.infrastructure;

import @PACKAGE@.application.GreetingUseCase;
import @PACKAGE@.domain.Greeting;

public final class SystemGreetingUseCase implements GreetingUseCase {
    @Override
    public Greeting greet(String name) {
        String value = name == null || name.isBlank() ? "world" : name.strip();
        return new Greeting("Hello, " + value + "!");
    }
}
