package com.unixroot.win10.core;

/** A drawable pixel surface supplied by the host (Android Bitmap/Canvas on the phone, BufferedImage in tests). Colors are 0xRRGGBB. */
public interface Surface {
    int width();
    int height();
    void fillRect(int x, int y, int w, int h, int rgb);
    /** Rectangle with optional fill (fillRgb &lt; 0 = none) and outline (penRgb &lt; 0 = none). */
    void rect(int x, int y, int w, int h, int fillRgb, int penRgb, int penW);
    void roundRect(int x, int y, int w, int h, int rx, int ry, int fillRgb, int penRgb, int penW);
    void ellipse(int x, int y, int w, int h, int fillRgb, int penRgb, int penW);
    void line(int x1, int y1, int x2, int y2, int rgb, int w);
    void polygon(int[] xs, int[] ys, int n, boolean close, int fillRgb, int penRgb, int penW);
    /** Draws text with its top-left corner at (x,y). bgRgb &lt; 0 means transparent background. */
    void text(String s, int x, int y, int fgRgb, int bgRgb, int fontHeight, boolean bold, boolean italic);
    int textWidth(String s, int fontHeight, boolean bold);
    void blit(Surface src, int sx, int sy, int sw, int sh, int dx, int dy, int dw, int dh);
    /** Draws a w*h block of 0xRRGGBB pixels scaled into the destination rectangle. */
    void pixels(int[] rgb, int w, int h, int dx, int dy, int dw, int dh);
    int getPixel(int x, int y);
    void setPixel(int x, int y, int rgb);
    /** Called after drawing so that the host can repaint a visible window. */
    void flush();
}
