import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class Build {
    public static void main(String[] args) throws Exception {
        Path dist = Path.of("dist");
        Files.createDirectories(dist);
        String html = Files.readString(Path.of("index.html"))
                .replace("/src/main.js", "./main.js");
        Files.writeString(dist.resolve("index.html"), html);
        Files.copy(Path.of("src/main.js"), dist.resolve("main.js"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("src/style.css"), dist.resolve("style.css"),
                StandardCopyOption.REPLACE_EXISTING);
    }
}
