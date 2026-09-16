package com.simpleworlddownloader.mixin;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the Simple World Downloader Addon.
 */
public class SimpleWorldDownloaderAddon implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("SimpleWorldDownloader");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Simple World Downloader Addon initialized");
        LOGGER.info("Chunk obfuscation detection active; normal chunk packets are not cancelled");
    }
}
