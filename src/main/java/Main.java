import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.InflaterInputStream;

public class Main {
  public static void main(String[] args){
    // You can use print statements as follows for debugging, they'll be visible when running tests.
    System.err.println("Logs from your program will appear here!");

    // Uncomment this block to pass the first stage
    //
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
      // cat-file -p1 <blob_sha>3
      
      case "cat-file" -> {
        String key=args[2];
        String objecthash=key.substring(0,2);
        String filename=key.substring(2);
        String path = String.format("./.git/objects/%s/%s",objecthash,filename);
        //e88f7a929cd70b0274c4ea33b209c97fa845fdb
        ///  ./.git/objects/e8/8f7a929cd70b0274c4ea33b209c97fa845fdbc
        File blobFile =new File(path);
        try {
            // Open the blob file as an InputStream
            FileInputStream fileInputStream = new FileInputStream(blobFile);

            // Wrap the input stream with InflaterInputStream for decompression
            InflaterInputStream inflaterInputStream = new InflaterInputStream(fileInputStream);

            // Read the decompressed data into a byte array
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;

            while ((bytesRead = inflaterInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            // Close the streams
            inflaterInputStream.close();
            fileInputStream.close();

            // Convert the decompressed data to a string
            byte[] decompressedData = outputStream.toByteArray();
            String decompressedString = new String(decompressedData, "UTF-8");

            // Display the decompressed data (Git blob contains metadata + content)
            String result = decompressedString.replaceAll("^blob \\d+\\x00", "");
            System.out.print(result);
            // blob <size>\0<content>
            

        } catch (IOException e) {
            System.err.println("Error processing blob file: " + e.getMessage());
            e.printStackTrace();
        }
      }
      

      default -> System.out.println("Unknown command: " + command);
    }
  }
}

