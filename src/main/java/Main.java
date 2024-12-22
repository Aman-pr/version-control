import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.Deflater;
import java.util.zip.InflaterInputStream;

public class Main {
  public static void main(String[] args) {
    System.err.println("Logs from your program will appear here!");
    
    final String command = args[0];
    
    switch (command) {
      case "init" -> {
        final File root = new File(".git");
        new File(root, "objects").mkdirs();
        new File(root, "refs").mkdirs();
        final File head = new File(root, "HEAD");
    
        try {
          head.createNewFile();
          Files.write(head.toPath(), "ref: refs/heads/main\n".getBytes());
          System.out.println("Initialized git directory");
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      case "cat-file" -> {
        String key = args[2];
        String objectHash = key.substring(0, 2);
        String filename = key.substring(2);
        String path = String.format("./.git/objects/%s/%s", objectHash, filename);
        
        File blobFile = new File(path);
        try {
          FileInputStream fileInput = new FileInputStream(blobFile);
          InflaterInputStream inflater = new InflaterInputStream(fileInput);
          ByteArrayOutputStream output = new ByteArrayOutputStream();
          
          byte[] buffer = new byte[1024];
          int bytesRead;
          while ((bytesRead = inflater.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
          }
          inflater.close();
          fileInput.close();
          byte[] decompressedData = output.toByteArray();
          String content = new String(decompressedData, "UTF-8");
          String result = content.replaceAll("^blob \\d+\\x00", "");
          System.out.print(result);
          
        } catch (IOException e) {
          System.err.println("Error reading file: " + e.getMessage());
          e.printStackTrace();
        }
      }
      
      // Command to create a Git object
      case "hash-object" -> {
        // Check if -w flag is present
        if (!args[1].equals("-w")) {
          System.err.println("Only -w flag is supported");
          return;
        }
        
        try {
          // 1. Read the file
          String filePath = args[2];
          byte[] fileContent = Files.readAllBytes(new File(filePath).toPath());
          
          // 2. Add Git header
          String header = "blob " + fileContent.length + "\0";
          byte[] headerBytes = header.getBytes();
          
          // 3. Combine header and content
          byte[] fullContent = new byte[headerBytes.length + fileContent.length];
          System.arraycopy(headerBytes, 0, fullContent, 0, headerBytes.length);
          System.arraycopy(fileContent, 0, fullContent, headerBytes.length, fileContent.length);
          
          // 4. Calculate file ID (hash)
          MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
          byte[] hash = sha1.digest(fullContent);
          String fileId = bytesToHex(hash);
          
          // 5. Compress the content
          Deflater compressor = new Deflater();
          compressor.setInput(fullContent);
          compressor.finish();
          
          ByteArrayOutputStream compressedData = new ByteArrayOutputStream();
          byte[] buffer = new byte[1024];
          while (!compressor.finished()) {
            int count = compressor.deflate(buffer);
            compressedData.write(buffer, 0, count);
          }
          
          
          String folder = ".git/objects/" + fileId.substring(0, 2);
          new File(folder).mkdirs();
          
          String savePath = folder + "/" + fileId.substring(2);
          FileOutputStream fileOutput = new FileOutputStream(savePath);
          fileOutput.write(compressedData.toByteArray());
          fileOutput.close();
          
          System.out.println(fileId);
          
        } catch (IOException | NoSuchAlgorithmException e) {
          throw new RuntimeException(e);
        }
      }
      
      default -> System.out.println("Unknown command: " + command);
    }
  }
  
  // hexadecimal
  private static String bytesToHex(byte[] bytes) {
    StringBuilder result = new StringBuilder();
    for (byte b : bytes) {
      result.append(String.format("%02x", b));
    }
    return result.toString();
  }
}

