import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

public class Main {
    static final String GIT_FOLDER = ".git";
    
    public static void main(String[] args) {
        System.err.println("Logs from your program will appear here!");
        
        try {
            String command = args[0];
            
            switch (command) {
                case "init" -> makeNewRepo();
                case "cat-file" -> showFileContents(args);
                case "hash-object" -> makeHashFromFile(args);
                case "ls-tree" -> showTreeContents(args);
                case "write-tree" -> writeTreeObject();
                case "commit-tree" -> createCommit(args);
                case "clone" -> cloneRepo(args);
                default -> System.out.println("I don't know how to do that: " + command);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Clone a repository from GitHub
    private static void cloneRepo(String[] args) throws Exception {
        String url = args[1];
        String targetDir = args[2];
        
        // Create target directory and initialize git repo
        new File(targetDir).mkdirs();
        System.setProperty("user.dir", targetDir);
        makeNewRepo();
        
        // Parse GitHub URL
        URI uri = new URI(url);
        String[] pathParts = uri.getPath().split("/");
        String owner = pathParts[1];
        String repo = pathParts[2];
        
        // Get refs and pack
        Map<String, String> refs = fetchRefs(owner, repo);
        String mainHash = refs.get("refs/heads/main");
        if (mainHash == null) {
            mainHash = refs.get("refs/heads/master");
        }
        
        downloadAndUnpackObjects(owner, repo, mainHash);
        
        // Update HEAD and refs
        File refsHeadsMain = new File(GIT_FOLDER + "/refs/heads/main");
        refsHeadsMain.getParentFile().mkdirs();
        Files.write(refsHeadsMain.toPath(), (mainHash + "\n").getBytes());
    }

    // Fetch repository references from GitHub
    private static Map<String, String> fetchRefs(String owner, String repo) throws Exception {
        URL url = new URL(String.format(
            "https://github.com/%s/%s.git/info/refs?service=git-upload-pack",
            owner, repo
        ));
        
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "git/2.0");
        
        Map<String, String> refs = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            // Skip first line (service announcement)
            reader.readLine();
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() == 0 || line.charAt(0) == '#') continue;
                
                // Parse ref line
                String[] parts = line.substring(4).split(" ");
                if (parts.length >= 2) {
                    refs.put(parts[1], parts[0]);
                }
            }
        }
        
        return refs;
    }

    // Download and process packfile from GitHub
    private static void downloadAndUnpackObjects(String owner, String repo, String wantHash) throws Exception {
        // Request packfile
        URL url = new URL(String.format(
            "https://github.com/%s/%s.git/git-upload-pack",
            owner, repo
        ));
        
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-git-upload-pack-request");
        conn.setRequestProperty("User-Agent", "git/2.0");
        
        // Send want/done message
        String message = String.format("0032want %s\n00000009done\n", wantHash);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(message.getBytes());
        }
        
        // Process packfile
        try (InputStream in = conn.getInputStream()) {
            // Skip response header
            skipPackfileHeader(in);
            
            // Read pack header
            byte[] header = new byte[12];
            in.read(header);
            
            if (!new String(header, 0, 4).equals("PACK")) {
                throw new IOException("Invalid pack header");
            }
            
            // Read number of objects
            int numObjects = readInt32(header, 8);
            
            // Process each object
            for (int i = 0; i < numObjects; i++) {
                unpackObject(in);
            }
        }
    }

    // Skip packfile response header
    private static void skipPackfileHeader(InputStream in) throws IOException {
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
        }
    }

    // Read 32-bit integer from byte array
    private static int readInt32(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) |
               ((bytes[offset + 1] & 0xFF) << 16) |
               ((bytes[offset + 2] & 0xFF) << 8) |
               (bytes[offset + 3] & 0xFF);
    }

    // Unpack a single object from packfile
    private static void unpackObject(InputStream in) throws IOException, NoSuchAlgorithmException {
        // Read object header
        int firstByte = in.read();
        int type = (firstByte >> 4) & 7;
        long size = firstByte & 15;
        
        int shift = 4;
        int b;
        while ((b = in.read()) >= 0 && (b & 0x80) != 0) {
            size |= ((long)(b & 0x7f)) << shift;
            shift += 7;
        }
        
        // Read and decompress object data
        byte[] data = new byte[(int)size];
        try (InflaterInputStream inflater = new InflaterInputStream(in)) {
            int total = 0;
            while (total < size) {
                int count = inflater.read(data, total, (int)size - total);
                if (count < 0) break;
                total += count;
            }
        }
        
        // Save object based on type
        String header;
        switch (type) {
            case 1 -> header = "commit ";
            case 2 -> header = "tree ";
            case 3 -> header = "blob ";
            default -> throw new IOException("Unknown object type: " + type);
        }
        
        byte[] objectData = (header + size + "\0").getBytes();
        byte[] fullContent = new byte[objectData.length + data.length];
        System.arraycopy(objectData, 0, fullContent, 0, objectData.length);
        System.arraycopy(data, 0, fullContent, objectData.length, data.length);
        
        String hash = calculateSHA1(fullContent);
        saveCompressedFile(hash, fullContent);
    }

    // Keep existing methods...
    [Previous methods remain the same]
}
