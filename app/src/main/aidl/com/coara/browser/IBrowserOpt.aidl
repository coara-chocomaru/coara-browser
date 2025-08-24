package com.coara.browser;

interface IBrowserOpt {
    void saveFavicon(in String url, in byte[] bitmapData);
    byte[] computeMD5(in String input);
    void saveScreenshot(in byte[] bitmapData, in String fileName);
}
