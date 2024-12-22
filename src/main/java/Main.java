import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

public class Main {
    // Store our git folder name - we'll use this a lot
    static final String GIT_FOLDER = ".git";
    
    public static void main(String[] args) {
        // Show where logs will go
        System.err.println("Logs from your program will appear here!");
        
        try {
            // Figure out what command to run
            String whatToDo = args[0];
            
            // Do different things based on the command
            switch (whatToDo) {
                case "init" -> makeNewRepo();
                case "cat-file" -> showFileContents(args);
                case "hash-object" -> makeHashFromFile(args);
                case "ls-tree" -> showTreeContents(args);
                case "write-tree" -> writeTreeObject();
                default -> System.out.println("I don't know how to do that: " + whatToDo);
            }
        } catch (Exception oops) {
            // If something goes wrong, let's say what happened
            System.err.println("Uh oh, we hit a problem: " + oops.getMessage());
            oops.printStackTrace();
        }
    }

    // Make a new git repo
    private static void makeNewRepo() throws IOException {
        File gitFolder = new File(GIT_FOLDER);
        new File(gitFolder, "objects").mkdirs();
        new File(gitFolder, "refs").mkdirs();
        
        // Make the HEAD file point to main branch
        File headFile = new File(gitFolder, "HEAD");
        headFile.createNewFile();
        Files.write(headFile.toPath(), "ref: refs/heads/main\n".getBytes());
        
        System.out.println("Created a new git repo!");
    }

    // Show what's in a git object
    private static void showFileContents(String[] args) throws IOException {
        String hash = args[2];
        String folder = hash.substring(0, 2);
        String file = hash.substring(2);
        
        File objectFile = new File(String.format("%s/objects/%s/%s", GIT_FOLDER, folder, file));
        
        // Read and uncompress the file
        byte[] stuff = readCompressedFile(objectFile);
        String content = new String(stuff, "UTF-8");
        String result = content.replaceAll("^blob \\d+\\x00", "");
        
        System.out.print(result);
    }

    // Make a git object from a file
    private static void makeHashFromFile(String[] args) throws IOException, NoSuchAlgorithmException {
        if (!args[1].equals("-w")) {
            System.err.println("Sorry, you need to use -w with this command");
            return;
        }
        
        File inputFile = new File(args[2]);
        byte[] fileContent = Files.readAllBytes(inputFile.toPath());
        
        // Add the git header
        String header = "blob " + fileContent.length + "\0";
        byte[] headerBytes = header.getBytes();
        
        // Put it all together
        byte[] fullContent = new byte[headerBytes.length + fileContent.length];
        System.arraycopy(headerBytes, 0, fullContent, 0, headerBytes.length);
        System.arraycopy(fileContent, 0, fullContent, headerBytes.length, fileContent.length);
        
        // Get the hash and save the object
        String hash = calculateSHA1(fullContent);
        saveCompressedFile(hash, fullContent);
        
        System.out.println(hash);
    }

    // Show what's in a tree object
    private static void showTreeContents(String[] args) throws IOException {
        if (!args[1].equals("--name-only")) {
            System.err.println("Please use --name-only with this command");
            return;
        }

        String hash = args[2];
        File treeFile = new File(String.format("%s/objects/%s/%s", 
            GIT_FOLDER, hash.substring(0, 2), hash.substring(2)));

        byte[] treeContent = readCompressedFile(treeFile);
        
        // Skip past the header to get to the good stuff
        int position = 0;
        while (treeContent[position] != 0) position++;
        position++;
        
        // Get all the file names
        List<String> names = new ArrayList<>();
        while (position < treeContent.length) {
            while (treeContent[position] != ' ') position++;
            position++;
            
            StringBuilder name = new StringBuilder();
            while (treeContent[position] != 0) {
                name.append((char)treeContent[position]);
                position++;
            }
            names.add(name.toString());
            
            position += 21;  // skip past hash
        }
        
        // Show them in order
        Collections.sort(names);
        for (String name : names) {
            System.out.println(name);
        }
    }

    // Write a tree object for the current directory
    private static void writeTreeObject() throws IOException, NoSuchAlgorithmException {
        // Get everything in the current directory
        String treeHash = writeTreeForDirectory(new File("."));
        System.out.println(treeHash);
    }

    // Represents a tree entry for proper sorting
    private static class TreeEntry {
        String mode;
        String name;
        byte[] hash;
        
        TreeEntry(String mode, String name, byte[] hash) {
            this.mode = mode;
            this.name = name;
            this.hash = hash;
        }
        
        // Convert entry to bytes in git format
        byte[] toBytes() {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try {
                bytes.write((mode + " " + name + "\0").getBytes());
                bytes.write(hash);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return bytes.toByteArray();
        }
        
        // Git's special sorting: trees (40000) get a special character prefix
        String getSortKey() {
            return mode.equals("40000") ? name + "/" : name;
        }
    }

    // Helper to write a tree for a directory
    private static String writeTreeForDirectory(File dir) throws IOException, NoSuchAlgorithmException {
        // Keep track of everything in the tree
        List<TreeEntry> entries = new ArrayList<>();
        
        // Look at all files and folders
        File[] stuff = dir.listFiles();
        if (stuff != null) {
            for (File thing : stuff) {
                // Skip the .git folder!
                if (thing.getName().equals(GIT_FOLDER)) {
                    continue;
                }

                // Figure out what kind of thing it is
                String mode;
                String hash;
                
                if (thing.isDirectory()) {
                    // It's a folder - make a tree for it
                    mode = "40000";
                    hash = writeTreeForDirectory(thing);
                } else {
                    // It's a file - make a blob for it
                    mode = "100644";
                    hash = makeBlob(thing);
                }
                
                // Save the entry info
                entries.add(new TreeEntry(mode, thing.getName(), hexToBytes(hash)));
            }
        }

        // Sort entries using Git's sorting rules
        entries.sort((a, b) -> a.getSortKey().compareTo(b.getSortKey()));
        
        // Build the tree content
        ByteArrayOutputStream treeContent = new ByteArrayOutputStream();
        for (TreeEntry entry : entries) {
            treeContent.write(entry.toBytes());
        }
        
        // Add the header
        byte[] contentBytes = treeContent.toByteArray();
        String header = "tree " + contentBytes.length + "\0";
        ByteArrayOutputStream fullContent = new ByteArrayOutputStream();
        fullContent.write(header.getBytes());
        fullContent.write(contentBytes);
        
        // Save it and return the hash
        byte[] finalContent = fullContent.toByteArray();
        String hash = calculateSHA1(finalContent);
        saveCompressedFile(hash, finalContent);
        return hash;
    }

    // Helper to make a blob object from a file
    private static String makeBlob(File file) throws IOException, NoSuchAlgorithmException {
        byte[] content = Files.readAllBytes(file.toPath());
        String header = "blob " + content.length + "\0";
        
        byte[] fullContent = new byte[header.getBytes().length + content.length];
        System.arraycopy(header.getBytes(), 0, fullContent, 0, header.getBytes().length);
        System.arraycopy(content, 0, fullContent, header.getBytes().length, content.length);
        
        String hash = calculateSHA1(fullContent);
        saveCompressedFile(hash, fullContent);
        return hash;
    }

    // Helper to read a compressed file
    private static byte[] readCompressedFile(File file) throws IOException {
        try (
            FileInputStream fileIn = new FileInputStream(file);
            InflaterInputStream inflater = new InflaterInputStream(fileIn);
            ByteArrayOutputStream out = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = inflater.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    // Helper to save a compressed file
    private static void saveCompressedFile(String hash, byte[] content) throws IOException {
        // Compress the content
        Deflater squeezer = new Deflater();
        squeezer.setInput(content);
        squeezer.finish();
        
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!squeezer.finished()) {
            int count = squeezer.deflate(buffer);
            compressed.write(buffer, 0, count);
        }
        
        // Make the folder if needed
        String folder = GIT_FOLDER + "/objects/" + hash.substring(0, 2);
        new File(folder).mkdirs();
        
        // Save the file
        String filename = folder + "/" + hash.substring(2);
        try (FileOutputStream out = new FileOutputStream(filename)) {
            out.write(compressed.toByteArray());
        }
    }

    // Helper to calculate SHA1 hash
    private static String calculateSHA1(byte[] content) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest(content);
        
        StringBuilder result = new StringBuilder();
        for (byte b : hash) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    // Helper to convert hex string to bytes properly
    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return bytes;
    }
}
