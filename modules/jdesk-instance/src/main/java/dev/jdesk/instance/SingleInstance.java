package dev.jdesk.instance;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;

public final class SingleInstance {
    private SingleInstance(){}
    public static SingleInstanceResult acquire(String appId,Path stateDirectory,List<String> arguments,
            Consumer<List<String>> listener)throws SingleInstanceException{
        if(appId==null||!appId.matches("[A-Za-z0-9._-]{1,128}"))throw new SingleInstanceException("Invalid application id");
        Path dir=stateDirectory.toAbsolutePath().normalize();
        try{
            if(Files.isSymbolicLink(dir))throw new SingleInstanceException("State directory must not be a symlink");
            Files.createDirectories(dir);Path lockPath=dir.resolve(appId+".lock"),state=dir.resolve(appId+".properties");
            FileChannel channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock;
            try{lock=channel.tryLock();}catch(java.nio.channels.OverlappingFileLockException e){lock=null;}
            if(lock==null){channel.close();handoff(state,arguments);return new SingleInstanceResult(false,Optional.empty());}
            byte[] token=new byte[32];new SecureRandom().nextBytes(token);ServerSocket server=new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(),0));writeState(state,server.getLocalPort(),token);
            return new SingleInstanceResult(true,Optional.of(new SingleInstanceSession(channel,lock,server,token,listener,state)));
        }catch(SingleInstanceException e){throw e;}catch(IOException e){throw new SingleInstanceException("Single-instance coordination failed",e);}
    }
    private static void handoff(Path state,List<String> args)throws SingleInstanceException{
        if(args.size()>SingleInstanceSession.MAX_ARGS)throw new SingleInstanceException("Too many handoff arguments");
        try{Properties p=new Properties();try(var in=Files.newInputStream(state,LinkOption.NOFOLLOW_LINKS)){p.load(in);}
            int port=Integer.parseInt(p.getProperty("port"));byte[] token=Base64.getDecoder().decode(p.getProperty("token"));
            try(Socket socket=new Socket(InetAddress.getLoopbackAddress(),port);DataOutputStream out=new DataOutputStream(socket.getOutputStream())){
                out.writeInt(SingleInstanceSession.MAGIC);out.writeShort(token.length);out.write(token);out.writeShort(args.size());
                for(String arg:args){byte[] b=arg.getBytes(StandardCharsets.UTF_8);if(b.length>SingleInstanceSession.MAX_ARG_BYTES)throw new SingleInstanceException("Handoff argument too large");out.writeInt(b.length);out.write(b);}}
        }catch(SingleInstanceException e){throw e;}catch(Exception e){throw new SingleInstanceException("Could not notify primary instance",e);}
    }
    private static void writeState(Path state,int port,byte[] token)throws IOException{
        Path tmp=state.resolveSibling(state.getFileName()+".tmp");Properties p=new Properties();p.setProperty("port",Integer.toString(port));p.setProperty("token",Base64.getEncoder().encodeToString(token));
        try(var out=Files.newOutputStream(tmp)){p.store(out,"JDesk single-instance state");}
        try{Files.setPosixFilePermissions(tmp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
        Files.move(tmp,state,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
}
