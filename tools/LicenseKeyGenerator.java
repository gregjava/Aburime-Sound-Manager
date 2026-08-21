/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.tools;

import java.security.MessageDigest;
import java.util.UUID;

/**
 * Internal tool for generating Pro license keys.
 * This would not be included in the public distribution.
 */
public class LicenseKeyGenerator {
    
    public static String generateLicenseKey(String userEmail) {
        // In production: Use proper RSA signing
        // For demonstration: Simple format with hash
        String prefix = "PRO";
        String id = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String hash = generateHash(userEmail + id).substring(0, 8).toUpperCase();
        return String.format("%s-%s-%s-%s-%s", 
            prefix,
            id.substring(0, 4),
            id.substring(4, 8),
            hash.substring(0, 4),
            hash.substring(4, 8));
    }
    
    private static String generateHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString().toUpperCase();
        } catch (Exception e) {
            return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
    }
    
    public static void main(String[] args) {
        // Run this tool to generate licenses
        System.out.println("Aburime Sound Manager - License Key Generator");
        System.out.println("=============================================");
        
        String[] emails = {"user1@example.com", "user2@example.com"};
        for (String email : emails) {
            String key = generateLicenseKey(email);
            System.out.println(email + " -> " + key);
        }
    }
}