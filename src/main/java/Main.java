import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

public class Main {
    // Git folder name constant
    static final String GIT_FOLDER = ".git";
    
    public static void main(String[] args) {
        System.err.println("Logs from your program will appear here!");
        
        try {
            // Get the command from arguments
            String command = args[0];
            
            // Handle different git commands
            switch (command) {
                case "init" -> makeNewRepo();
                case "cat-file" -> showFileContents(args);
                case "hash-object" -> makeHashFromFile(args);
                case "ls-tree" -> showTreeContents(args);
                case "write-tree" -> writeTreeObject();
                case "commit-tree" -> createCommit(args);
                default -> System.out.println("I don't know how to do that: " + command);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Initialize a new git repository
    private static void makeNewRepo() throws IOException {
        // Create .git directory structure
        File gitFolder = new File(GIT_FOLDER);
        new File(gitFolder, "objects").mkdirs();
        new File(gitFolder, "refs").mkdirs();
        
        // Create HEAD file pointing to main branch
        File headFile = new File(gitFolder, "HEAD");
        headFile.createNewFile();
        Files.write(headFile.toPath(), "ref: refs/heads/main\n".getBytes());
        
        System.out.println("Created a new git repo!");
    }

    // Show contents of a git object
    private static void showFileContents(String[] args) throws IOException {
        String hash = args[2];
        String folder = hash.substring(0, 2);
        String file = hash.substring(2);
        
        // Read the object file
        File objectFile = new File(String.format("%s/objects/%s/%s", GIT_FOLDER, folder, file));
        byte[] content = readCompressedFile(objectFile);
        
        // Remove the header and print content
        String result = new String(content, "UTF-8").replaceAll("^blob \\d+\\x00", "");
        System.out.print(result);
    }

    // Create a hash object from a file
    private static void makeHashFromFile(String[] args) throws IOException, NoSuchAlgorithmException {
        if (!args[1].equals("-w")) {
            System.err.println("Please use -w with this command");
            return;
        }
        
        // Read file content
        File inputFile = new File(args[2]);
        byte[] fileContent = Files.readAllBytes(inputFile.toPath());
        
        // Create git blob object
        String header = "blob " + fileContent.length + "\0";
        byte[] headerBytes = header.getBytes();
        
        // Combine header and content
        byte[] fullContent = new byte[headerBytes.length + fileContent.length];
        System.arraycopy(headerBytes, 0, fullContent, 0, headerBytes.length);
        System.arraycopy(fileContent, 0, fullContent, headerBytes.length, fileContent.length);
        
        // Save and print hash
        String hash = calculateSHA1(fullContent);
        saveCompressedFile(hash, fullContent);
        System.out.println(hash);
    }

    // Show contents of a tree object
    private static void showTreeContents(String[] args) throws IOException {
        if (!args[1].equals("--name-only")) {
            System.err.println("Please use --name-only with this command");
            return;
        }

        String hash = args[2];
        File treeFile = new File(String.format("%s/objects/%s/%s", 
            GIT_FOLDER, hash.substring(0, 2), hash.substring(2)));

        // Read tree content
        byte[] treeContent = readCompressedFile(treeFile);
        
        // Skip header
        int pos = 0;
        while (treeContent[pos] != 0) pos++;
        pos++;
        
        // Extract file names
        List<String> names = new ArrayList<>();
        while (pos < treeContent.length) {
            // Skip mode
            while (treeContent[pos] != ' ') pos++;
            pos++;
            
            // Get file name
            StringBuilder name = new StringBuilder();
            while (treeContent[pos] != 0) {
                name.append((char)treeContent[pos]);
                pos++;
            }
            names.add(name.toString());
            
            // Skip hash (20 bytes)
            pos += 21;
        }
        
        // Print sorted names
        Collections.sort(names);
        for (String name : names) {
            System.out.println(name);
        }
    }

    // Create a tree object for current directory
    private static void writeTreeObject() throws IOException, NoSuchAlgorithmException {
        String treeHash = writeTreeForDirectory(new File("."));
        System.out.println(treeHash);
    }

    // Create a commit object
    private static void createCommit(String[] args) throws Exception {
        // Parse arguments
        String treeHash = args[1];
        String message = "";
        String parentHash = "";
        
        // Get parent hash and message from flags
        for (int i = 2; i < args.length; i += 2) {
            if (args[i].equals("-p")) {
                parentHash = args[i + 1];
            } else if (args[i].equals("-m")) {
                message = args[i + 1];
            }
        }

        // Build commit content
        StringBuilder commit = new StringBuilder();
        commit.append("tree ").append(treeHash).append("\n");
        
        if (!parentHash.isEmpty()) {
            commit.append("parent ").append(parentHash).append("\n");
        }

        // Add author and committer info
        String timestamp = getGitTimestamp();
        String userInfo = "Your Name <you@example.com> " + timestamp;
        
        commit.append("author ").append(userInfo).append("\n");
        commit.append("committer ").append(userInfo).append("\n");
        commit.append("\n").append(message).append("\n");

        // Create and save commit object
        byte[] content = commit.toString().getBytes();
        String header = "commit " + content.length + "\0";
        byte[] fullContent = new byte[header.getBytes().length + content.length];
        System.arraycopy(header.getBytes(), 0, fullContent, 0, header.getBytes().length);
        System.arraycopy(content, 0, fullContent, header.getBytes().length, content.length);

        String hash = calculateSHA1(fullContent);
        saveCompressedFile(hash, fullContent);
        
        System.out.println(hash);
    }

    // Helper class for tree entries
    private static class TreeEntry {
        String mode;
        String name;
        byte[] hash;
        
        TreeEntry(String mode, String name, byte[] hash) {
            this.mode = mode;
            this.name = name;
            this.hash = hash;
        }
        
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
        
        String getSortKey() {
            return mode.equals("40000") ? name + "/" : name;
        }
    }

    // Create tree object for a directory
    private static String writeTreeForDirectory(File dir) throws IOException, NoSuchAlgorithmException {
        List<TreeEntry> entries = new ArrayList<>();
        
        // Process all files and directories
        File[] items = dir.listFiles();
        if (items != null) {
            for (File item : items) {
                if (item.getName().equals(GIT_FOLDER)) {
                    continue;
                }

                String mode;
                String hash;
                
                if (item.isDirectory()) {
                    mode = "40000";
                    hash = writeTreeForDirectory(item);
                } else {
                    mode = "100644";
                    hash = makeBlob(item);
                }
                
                entries.add(new TreeEntry(mode, item.getName(), hexToBytes(hash)));
            }
        }

        // Sort entries
        entries.sort((a, b) -> a.getSortKey().compareTo(b.getSortKey()));
        
        // Create tree content
        ByteArrayOutputStream treeContent = new ByteArrayOutputStream();
        for (TreeEntry entry : entries) {
            treeContent.write(entry.toBytes());
        }
        
        // Add header and save
        byte[] contentBytes = treeContent.toByteArray();
        String header = "tree " + contentBytes.length + "\0";
        ByteArrayOutputStream fullContent = new ByteArrayOutputStream();
        fullContent.write(header.getBytes());
        fullContent.write(contentBytes);
        
        byte[] finalContent = fullContent.toByteArray();
        String hash = calculateSHA1(finalContent);
        saveCompressedFile(hash, finalContent);
        return hash;
    }

    // Create blob object from file
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

    // Get timestamp in git format
    private static String getGitTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat("s z");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        long timestamp = System.currentTimeMillis() / 1000;
        return timestamp + " " + format.format(new Date()).replace("UTC", "+0000");
    }

    // Read compressed git object
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

    // Save compressed git object
    private static void saveCompressedFile(String hash, byte[] content) throws IOException {
        Deflater deflater = new Deflater();
        deflater.setInput(content);
        deflater.finish();
        
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            compressed.write(buffer, 0, count);
        }
        
        String folder = GIT_FOLDER + "/objects/" + hash.substring(0, 2);
        new File(folder).mkdirs();
        
        String filename = folder + "/" + hash.substring(2);
        try (FileOutputStream out = new FileOutputStream(filename)) {
            out.write(compressed.toByteArray());
        }
    }

    // Calculate SHA1 hash
    private static String calculateSHA1(byte[] content) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest(content);
        
        StringBuilder result = new StringBuilder();
        for (byte b : hash) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    // Convert hex string to bytes
    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return bytes;
    }
}
