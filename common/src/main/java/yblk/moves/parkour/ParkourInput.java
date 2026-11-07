package yblk.moves.parkour;

import net.minecraft.network.FriendlyByteBuf;

/**
 * What the player is asking to do this tick. On the client this is read from
 * the keyboard. On the server it arrives in a packet.
 */
public final class ParkourInput {

    public float forward;
    public float strafe;
    public boolean jump;
    public boolean sneak;
    public boolean sprint;
    public boolean prone;
    /** Grab key (default RMB). Held to grab a ledge, chain or bars. */
    public boolean grab;
    /** True on the tick an enhanced jump should fire (after charge release). */
    public boolean enhanced;
    /** Rising-edge pulse: start a ground slide this tick. */
    public boolean slide;
    /** -1 left, 1 right, 2 back, 3 forward, 0 none. */
    public int dodge;
    /** 0..JUMP_CHARGE_TICKS while charging an enhanced jump. */
    public int jumpCharge;

    public void write(FriendlyByteBuf buf) {
        buf.writeFloat(this.forward);
        buf.writeFloat(this.strafe);
        int flags = 0;
        if (this.jump) {
            flags |= 1;
        }
        if (this.sneak) {
            flags |= 2;
        }
        if (this.sprint) {
            flags |= 4;
        }
        if (this.prone) {
            flags |= 8;
        }
        if (this.enhanced) {
            flags |= 16;
        }
        if (this.grab) {
            flags |= 32;
        }
        if (this.slide) {
            flags |= 64;
        }
        buf.writeByte(flags);
        buf.writeByte(this.dodge);
        buf.writeByte(this.jumpCharge);
    }

    public static ParkourInput read(FriendlyByteBuf buf) {
        ParkourInput input = new ParkourInput();
        input.forward = buf.readFloat();
        input.strafe = buf.readFloat();
        int flags = buf.readByte();
        input.jump = (flags & 1) != 0;
        input.sneak = (flags & 2) != 0;
        input.sprint = (flags & 4) != 0;
        input.prone = (flags & 8) != 0;
        input.enhanced = (flags & 16) != 0;
        input.grab = (flags & 32) != 0;
        input.slide = (flags & 64) != 0;
        input.dodge = buf.readByte();
        input.jumpCharge = buf.readUnsignedByte();
        return input;
    }
}
