package local.byd.arrival;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** White-box envelope port of MIT pyBYD, pinned in THIRD_PARTY_LICENSE.txt. */
public final class Bangcle {
    private final byte[][] tables = new byte[8][];
    public Bangcle(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt() != 0x42544742 || b.getShort() != 1 || b.getShort() != 8)
            throw new IllegalArgumentException("Invalid cipher tables");
        int[] sizes = {0x28000, 0x3c000, 0x1000, 0x28000, 0x3c000, 0x1000, 8, 8};
        for (int i = 0; i < 8; i++) {
            int offset = b.getInt(), size = b.getInt();
            if (size != sizes[i] || offset < 72 || offset > data.length - size)
                throw new IllegalArgumentException("Invalid cipher table bounds");
            tables[i] = Arrays.copyOfRange(data, offset, offset + size);
        }
    }
    public String encode(String text) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        int pad = 16 - raw.length % 16;
        byte[] padded = Arrays.copyOf(raw, raw.length + pad);
        Arrays.fill(padded, raw.length, padded.length, (byte) pad);
        byte[] out = new byte[padded.length], prev = new byte[16];
        for (int off = 0; off < padded.length; off += 16) {
            byte[] block = Arrays.copyOfRange(padded, off, off + 16);
            for (int i = 0; i < 16; i++) block[i] ^= prev[i];
            prev = block(block, true);
            System.arraycopy(prev, 0, out, off, 16);
        }
        return "F" + Base64.getEncoder().encodeToString(out);
    }
    public String decode(String text) {
        String clean = text.replaceAll("\\s", "").replace('-', '+').replace('_', '/');
        if (!clean.startsWith("F")) throw new IllegalArgumentException("Invalid envelope prefix");
        byte[] raw = Base64.getDecoder().decode(clean.substring(1));
        if (raw.length == 0 || raw.length % 16 != 0) throw new IllegalArgumentException("Invalid envelope size");
        byte[] out = new byte[raw.length], prev = new byte[16];
        for (int off = 0; off < raw.length; off += 16) {
            byte[] encrypted = Arrays.copyOfRange(raw, off, off + 16);
            byte[] plain = block(encrypted, false);
            for (int i = 0; i < 16; i++) out[off + i] = (byte) (plain[i] ^ prev[i]);
            prev = encrypted;
        }
        int pad = out[out.length - 1] & 255;
        if (pad < 1 || pad > 16) throw new IllegalArgumentException("Invalid envelope padding");
        for (int i = out.length - pad; i < out.length; i++)
            if ((out[i] & 255) != pad) throw new IllegalArgumentException("Invalid envelope padding");
        return new String(out, 0, out.length - pad, StandardCharsets.UTF_8);
    }
    private byte[] block(byte[] input, boolean encrypt) {
        int[] state = new int[32], temp = new int[64];
        for (int col = 0; col < 4; col++)
            for (int row = 0; row < 4; row++) state[col * 8 + row] = input[col + row * 4] & 255;
        for (int step = 0; step < 9; step++) {
            int round = encrypt ? step : 9 - step;
            mix(state, temp, round, encrypt);
        }
        int[] old = state.clone();
        byte[] finalTable = tables[encrypt ? 5 : 2];
        for (int col = 0; col < 4; col++) {
            int shift = encrypt ? col : (4 - col) & 3;
            for (int row = 0; row < 4; row++) {
                int sourceRow = (row + shift) & 3;
                int index = old[col * 8 + sourceRow] + sourceRow * 0x400 + col * 0x100;
                state[col * 8 + row] = finalTable[index] & 255;
            }
        }
        byte[] out = new byte[16];
        for (int col = 0; col < 4; col++)
            for (int row = 0; row < 4; row++) out[col + row * 4] = (byte) state[col * 8 + row];
        return out;
    }
    private void mix(int[] state, int[] temp, int round, boolean encrypt) {
        byte[] rounds = tables[encrypt ? 3 : 0], xor = tables[encrypt ? 4 : 1];
        byte[] perm = tables[encrypt ? 7 : 6];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                int row = ((perm[i * 2] & 255) + j) & 3;
                int index = (state[i * 8 + row] + (i + (round * 4 + row) * 4) * 256) * 4;
                for (int k = 0; k < 4; k++) temp[i * 16 + j * 4 + k] = rounds[index + k] & 255;
            }
        }
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                int pos = col + row * 4;
                int low = temp[pos] & 15, high = temp[pos] & 240;
                int base = row * 24 + round * 96;
                for (int n = 0; n < 3; n++) {
                    int value = temp[pos + (n + 1) * 16];
                    int index = base + col * 6 + n * 2;
                    low = xor[index * 256 + (low | ((value << 4) & 255))] & 15;
                    high = (xor[(index + 1) * 256 + ((high >> 4) | (value & 240))] & 15) << 4;
                }
                state[row + col * 8] = high | low;
            }
        }
    }
}
