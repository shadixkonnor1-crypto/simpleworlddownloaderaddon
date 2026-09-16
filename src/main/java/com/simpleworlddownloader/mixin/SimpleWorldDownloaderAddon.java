package com.simpleworlddownloader.mixin;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the Simple World Downloader Addon.
 * Initializes chunk packet interception and obfuscation detection.
 */
public class SimpleWorldDownloaderAddon implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("SimpleWorldDownloader");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Simple World Downloader Addon initialized");
        LOGGER.info("Client-side chunk packet interception active");
    }
}