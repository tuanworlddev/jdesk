package dev.jdesk.instance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Files;import java.nio.file.Path;import java.util.List;import java.util.concurrent.LinkedBlockingQueue;import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
class SingleInstanceTest{
 @TempDir Path temp;
 @Test void secondaryHandsDeepLinkToPrimary()throws Exception{
  var received=new LinkedBlockingQueue<List<String>>();var first=SingleInstance.acquire("dev.test",temp,List.of(),received::add);
  assertThat(first.primary()).isTrue();try(var session=first.session().orElseThrow()){
   var second=SingleInstance.acquire("dev.test",temp,List.of("jdesk-test://open/item","--safe"),x->{});
   assertThat(second.primary()).isFalse();assertThat(received.poll(5,TimeUnit.SECONDS)).containsExactly("jdesk-test://open/item","--safe");
  }
 }
 @Test void rejectsOversizeAndSymlinkDirectory()throws Exception{
  var received=new LinkedBlockingQueue<List<String>>();var first=SingleInstance.acquire("dev.test",temp,List.of(),received::add);
  try(var session=first.session().orElseThrow()){
   assertThatThrownBy(()->SingleInstance.acquire("dev.test",temp,List.of("x".repeat(20000)),x->{})).hasMessage("Handoff argument too large");
  }
  Path real=temp.resolve("real");Files.createDirectories(real);Path link=temp.resolve("link");Files.createSymbolicLink(link,real);
  assertThatThrownBy(()->SingleInstance.acquire("dev.test2",link,List.of(),x->{})).hasMessage("State directory must not be a symlink");
 }
}
