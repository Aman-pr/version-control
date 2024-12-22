import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

public class Main {
    // Let's keep track of our git folder name
    private static final String GIT_DIR = ".git";
    
    public static void main(String[] args) {
        // Just a friendly message to show where logs will appear
        System.err.println("Logs from your program will appear here!");
        
        // Grab the command the user wants to run
        String command = args[0];
        
        try {
            // Handle different git commands
            switch (command) {
                case "init" -> initializeRepo();
                case "cat-file" -> catFile(args);
                case "hash-object" -> hashObject(args);
                case "ls-tree" -> listTree(args);
                default -> System.out.println("Hmm, I don't know that command: " + command);
            }
        } catch (Exception e) {
            System.err.println("Oops! Something went wrong: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Set up a new git repo
    private static void initializeRepo() throws IOException {
        // Create our main folders
        File gitDir = new File(GIT_DIR);
        new File(gitDir, "objects").mkdirs();
        new File(gitDir, "refs").mkdirs();
        
        // Set up HEAD to point to main branch
        File head = new File(gitDir, "HEAD");
        head.createNewFile();
        Files.write(head.toPath(), "ref: refs/heads/main\n".getBytes());
        
        System.out.println("Cool! I've set up a new git repo for you");
    }

    // Read a git object's contents
    private static void catFile(String[] args) throws IOException {
        String hash = args[2];
        
        // Split the hash into directory and filename
        String folder = hash.substring(0, 2);
        String file = hash.substring(2);
        
        // Build the path to our object
        String objectPath = String.format("%s/objects/%s/%s", GIT_DIR, folder, file);
        
        // Read and decompress the file
        byte[] content = readGitObject(objectPath);
        
        // Convert to string and remove the git header
        String text = new String(content, "UTF-8");
        String result = text.replaceAll("^blob \\d+\\x00", "");
        
        System.out.print(result);
    }

    // Create a new git object from a file
    private static void hashObject(String[] args) throws IOException, NoSuchAlgorithmException {
        // We only support -w flag for now
        if (!args[1].equals("-w")) {
            System.err.println("Sorry, I only know how to use the -w flag");
            return;
        }
        
        String filePath = args[2];
        
        // Read the file
        byte[] content = Files.readAllBytes(new File(filePath).toPath());
        
        // Create the git header
        String header = "blob " + content.length + "\0";
        byte[] headerBytes = header.getBytes();
        
        // Combine header and content
        byte[] fullContent = new byte[headerBytes.length + content.length];
        System.arraycopy(headerBytes, 0, fullContent, 0, headerBytes.length);
        System.arraycopy(content, 0, fullContent, headerBytes.length, content.length);
        
        // Calculate SHA1 hash
        String hash = calculateHash(fullContent);
        
        // Save the object
        saveGitObject(hash, fullContent);
        
        System.out.println(hash);
    }

    // Show contents of a tree object
    private static void listTree(String[] args) throws IOException {
        // Check if we got the right arguments
        if (args.length < 3 || !args[1].equals("--name-only")) {
            System.err.println("Hey, use it like this: ls-tree --name-only <tree-sha>");
            return;
        }

        String hash = args[2];
        String objectPath = String.format("%s/objects/%s/%s", 
            GIT_DIR, hash.substring(0, 2), hash.substring(2));

        // Read the tree object
        byte[] treeData = readGitObject(objectPath);
        
        // Get the file names
        List<String> fileNames = new ArrayList<>();
        
        // Skip past the header to the actual data
        int pos = 0;
        while (treeData[pos] != 0) pos++;
        pos++;  // skip the null byte
        
        // Read each entry
        while (pos < treeData.length) {
            // Skip the mode
            while (treeData[pos] != ' ') pos++;
            pos++;  // skip the space
            
            // Get the name
            StringBuilder name = new StringBuilder();
            while (treeData[pos] != 0) {
                name.append((char)treeData[pos]);
                pos++;
            }
            fileNames.add(name.toString());
            
            // Skip past this entry's SHA (20 bytes) and the null byte
            pos += 21;
        }
        
        // Sort and print
        Collections.sort(fileNames);
        for (String name : fileNames) {
            System.out.println(name);
        }
    }

    // Helper to read and decompress a git object
    private static byte[] readGitObject(String path) throws IOException {
        try (
            FileInputStream fileIn = new FileInputStream(path);
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

    // Helper to save a git object
    private static void saveGitObject(String hash, byte[] content) throws IOException {
        // Compress the content
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        Deflater deflater = new Deflater();
        deflater.setInput(content);
        deflater.finish();
        
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            compressed.write(buffer, 0, count);
        }
        
        // Create the object directory if needed
        String folder = GIT_DIR + "/objects/" + hash.substring(0, 2);
        new File(folder).mkdirs();
        
        // Save the file
        String path = folder + "/" + hash.substring(2);
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write(compressed.toByteArray());
        }
    }

    // Helper to calculate SHA1 hash
    private static String calculateHash(byte[] content) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest(content);
        
        // Convert to hex string
        StringBuilder result = new StringBuilder();
        for (byte b : hash) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
