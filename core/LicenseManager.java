/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.core;

import audiomanager.util.PreferenceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Manages license verification and feature availability.
 * Free version: single file, 100MB limit
 * Pro version: unlimited batch, 750MB limit
 */
public class LicenseManager {
    public static void main(String[] args){
        System.out.println("This file is not available to the public!");
    }
}