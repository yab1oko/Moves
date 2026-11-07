package yblk.moves.parkour;

public enum ParkourMode {
    NONE,
    LEDGE,
    PULLUP,
    HANG,
    CLIMB,
    DODGE,
    /** Sprint along a wall after a jump. Not part of the original list. */
    WALL_RUN,
    /** Sprint into prone. Not part of the original list. */
    SLIDE,
    /** Riding a placed rope or chain line. */
    ZIPLINE;

    public boolean scripted() {
        return this != NONE;
    }

    public boolean locksBody() {
        // HANG (ceiling) keeps free look — locking body shoved the camera into bars.
        return this == LEDGE || this == PULLUP || this == CLIMB;
    }
}
