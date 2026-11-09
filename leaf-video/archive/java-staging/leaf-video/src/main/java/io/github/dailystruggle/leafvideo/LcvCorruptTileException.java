package io.github.dailystruggle.leafvideo;

/**
 * Checked exception raised by {@code CBIT} bounds and syntax guards when a compressed
 * spatiotemporal tile payload is truncated or corrupted, confining the blast radius to a
 * single {@code 16 x 16 x K} cube.
 *
 * @governedBy leaf-video-ADR-001
 * @trace REQ-LCV-F-004
 */
public final class LcvCorruptTileException extends Exception {
    private static final long serialVersionUID = 1L;

    public LcvCorruptTileException(String message) {
        super(message);
    }

    public LcvCorruptTileException(String message, Throwable cause) {
        super(message, cause);
    }
}
