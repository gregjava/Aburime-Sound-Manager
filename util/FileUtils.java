/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * File utility methods
 */
public class FileUtils {
    
    /**
     * Get file name without extension
     * @param fileName  A String value of the file's full name with extension.
     * @return  The name of the file without extension or the input String.
     */
    public static String getFileNameWithoutExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }
    
    /**
     * Get file extension
     * @param fileName  A String value of the file's full name with extension.
     * @return  The file extension or an empty String.
     */
    public static String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1).toLowerCase() : "";
    }
    
    /**
     * Create directory if it doesn't exist
     * @param directoryPath  The directory to be created.
     * @throws java.io.IOException  An Input/Output exception raised if an error occurs during the process.
     */
    public static void createDirectoryIfNotExists(String directoryPath) throws IOException {
        Path path = Paths.get(directoryPath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }
    
    /**
     * Format file size in human-readable format
     * @param bytes  The size of the file in bytes.
     * @return  The String representation of the file size in a human-readable format.
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp-1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), pre);
    }
    
    /**
     * Check if file is an audio file based on extension
     * @param file  The file to be checked.
     * @return  Returns true when the file is an audio file of type mp3, wav, flac, 
     *          ogg, m4a, aac, wma, opus, alac, aiff, amr &amp; ac3. Returns false otherwise.
     */
    public static boolean isAudioFile(File file) {
        String[] audioExtensions = {".mp3", ".wav", ".flac", ".ogg", ".m4a", ".aac", ".wma", ".opus", ".alac", ".aiff", ".amr", ".ac3"};
        String name = file.getName().toLowerCase();
        for (String ext : audioExtensions) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Generate a unique file path to avoid overwriting
     * @param directory  A String value of the file path to be generated.
     * @param baseName  The name of the base file
     * @param extension  The extension of the file
     * @return  The generated path
     */
    public static String generateUniqueFilePath(String directory, String baseName, String extension) {
        Path path = Paths.get(directory, baseName + extension);
        int counter = 1;
        while (Files.exists(path)) {
            path = Paths.get(directory, baseName + "_" + counter++ + extension);
        }
        return path.toString();
    }
}