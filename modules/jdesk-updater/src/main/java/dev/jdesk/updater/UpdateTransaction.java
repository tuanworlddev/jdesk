package dev.jdesk.updater;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Versioned atomic activation with an explicit previous pointer for rollback. */
public final class UpdateTransaction {
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._-]{0,63}");
    private final Path root;

    public UpdateTransaction(Path installRoot) throws UpdateVerificationException {
        root = Objects.requireNonNull(installRoot, "installRoot").toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(root)) throw new UpdateVerificationException("Install root must not be a symlink");
            Files.createDirectories(root.resolve("versions"));
            if (Files.isSymbolicLink(root.resolve("versions")))
                throw new UpdateVerificationException("Versions directory must not be a symlink");
        } catch (IOException e) { throw new UpdateVerificationException("Could not initialize update root", e); }
    }

    public synchronized Path stageAndActivate(VerifiedUpdate update, String version)
            throws UpdateVerificationException {
        requireVersion(version);
        Path versions = root.resolve("versions");
        Path target = versions.resolve(version).normalize();
        if (!target.getParent().equals(versions)) throw new UpdateVerificationException("Invalid version path");
        Path staging = versions.resolve(".staging-" + UUID.randomUUID());
        try {
            Files.createDirectory(staging);
            Files.copy(update.packagePath(), staging.resolve("package.bin"),
                    StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
            Files.writeString(staging.resolve("sha256"), update.sha256() + "\n",
                    StandardCharsets.US_ASCII);
            moveAtomic(staging, target);
            String current = readPointer("current");
            if (current != null) writePointer("previous", current);
            writePointer("current", version);
            return target;
        } catch (IOException e) {
            deleteStaging(staging);
            throw new UpdateVerificationException("Could not activate update", e);
        }
    }

    public synchronized String rollback() throws UpdateVerificationException {
        String previous = readPointer("previous");
        if (previous == null) throw new UpdateVerificationException("No previous version to roll back to");
        if (!Files.isDirectory(root.resolve("versions").resolve(previous), LinkOption.NOFOLLOW_LINKS))
            throw new UpdateVerificationException("Previous version is missing");
        String current = readPointer("current");
        writePointer("current", previous);
        if (current == null) deletePointer("previous"); else writePointer("previous", current);
        return previous;
    }

    public String currentVersion() throws UpdateVerificationException { return readPointer("current"); }

    private void writePointer(String name, String value) throws UpdateVerificationException {
        requireVersion(value); Path temp=root.resolve("."+name+"-"+UUID.randomUUID());
        try { Files.writeString(temp,value+"\n",StandardCharsets.US_ASCII); moveAtomic(temp,root.resolve(name)); }
        catch(IOException e){ throw new UpdateVerificationException("Could not update activation pointer",e); }
    }
    private String readPointer(String name) throws UpdateVerificationException {
        Path p=root.resolve(name); if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS))return null;
        if(Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))
            throw new UpdateVerificationException("Invalid activation pointer");
        try { String v=Files.readString(p,StandardCharsets.US_ASCII).strip(); requireVersion(v); return v; }
        catch(IOException e){throw new UpdateVerificationException("Could not read activation pointer",e);}
    }
    private void deletePointer(String name) throws UpdateVerificationException {
        try{Files.deleteIfExists(root.resolve(name));}catch(IOException e){throw new UpdateVerificationException("Could not clear activation pointer",e);}
    }
    private static void moveAtomic(Path from,Path to)throws IOException{
        try{Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);}
    }
    private static void requireVersion(String v)throws UpdateVerificationException{
        if(v==null||!VERSION.matcher(v).matches())throw new UpdateVerificationException("Invalid update version");
    }
    private static void deleteStaging(Path p){try{if(Files.isDirectory(p))try(var s=Files.list(p)){s.forEach(x->{try{Files.deleteIfExists(x);}catch(IOException ignored){}});}Files.deleteIfExists(p);}catch(IOException ignored){}}
}
