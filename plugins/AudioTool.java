/*
 * Copyright (c) AudioManager Project Contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package audiomanager.plugins;

/**
 *
 * @author USER
 */

import javafx.scene.Node;
import java.io.File;
import java.util.function.Consumer;

/**
 * Plugin interface for audio tools
 */
public interface AudioTool {
    /**
     * Get tool name
     * @return 
     */
    String getName();

    /**
     * Get tool description
     * @return 
     */
    String getDescription();

    /**
     * Get UI component for this tool
     * @return 
     */
    Node createUI();

    /**
     * Check if tool can process the given file
     * @param file  The file to be processed
     * @return 
     */
    boolean canProcess(File file);

    /**
     * Process a file
     * @param input Input file
     * @param config Tool-specific configuration
     * @param logger Logger for messages
     * @throws java.lang.Exception
     */
    void process(File input, ToolConfig config, Consumer<String> logger) throws Exception;

    /**
     * Tool-specific configuration
     */
    interface ToolConfig {
        // Marker interface - each tool implements its own config
    }
}