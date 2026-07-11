package dev.jdesk.instance;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class SingleInstanceSession implements AutoCloseable {
    static final int MAGIC=0x4a444553,MAX_ARGS=64,MAX_ARG_BYTES=16*1024;
    private final FileChannel lockChannel; private final FileLock lock; private final ServerSocket server;
    private final byte[] token; private final Consumer<List<String>> listener; private final ExecutorService executor;
    private final Path state; private volatile boolean closed;
    SingleInstanceSession(FileChannel c,FileLock l,ServerSocket s,byte[] t,Consumer<List<String>> x,Path state){
        lockChannel=c;lock=l;server=s;token=t;listener=x;this.state=state;
        executor=Executors.newVirtualThreadPerTaskExecutor(); executor.submit(this::acceptLoop);
    }
    private void acceptLoop(){while(!closed)try{Socket socket=server.accept();executor.submit(()->handle(socket));}
        catch(IOException e){if(!closed)closed=true;}}
    private void handle(Socket socket){try(socket;DataInputStream in=new DataInputStream(socket.getInputStream())){
        if(!socket.getInetAddress().isLoopbackAddress()||in.readInt()!=MAGIC)return;
        int tokenLength=in.readUnsignedShort();if(tokenLength!=token.length)return;byte[] supplied=in.readNBytes(tokenLength);
        if(!java.security.MessageDigest.isEqual(token,supplied))return;int count=in.readUnsignedShort();if(count>MAX_ARGS)return;
        List<String> args=new ArrayList<>(count);for(int i=0;i<count;i++){int length=in.readInt();if(length<0||length>MAX_ARG_BYTES)return;
            byte[] bytes=in.readNBytes(length);if(bytes.length!=length)return;args.add(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));}
        listener.accept(List.copyOf(args));}catch(IOException|RuntimeException ignored){}}
    public int port(){return server.getLocalPort();}
    public String tokenBase64(){return Base64.getEncoder().encodeToString(token);}
    @Override public void close(){if(closed)return;closed=true;try{server.close();}catch(IOException ignored){}executor.shutdownNow();
        try{Files.deleteIfExists(state);}catch(IOException ignored){}try{lock.release();}catch(IOException ignored){}try{lockChannel.close();}catch(IOException ignored){}}
}
