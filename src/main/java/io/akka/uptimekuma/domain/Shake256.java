package io.akka.uptimekuma.domain;

import java.util.Arrays;

/**
 * SHAKE256, the extendable-output function from FIPS 202.
 *
 * <p>Present because the session token's {@code h} claim is a SHAKE256 digest of the stored
 * password hash and the JDK's providers do not offer the function. Everything here is the standard
 * Keccak-f[1600] permutation with a rate of 136 bytes and the {@code 0x1f} domain separator SHAKE
 * uses.
 */
final class Shake256 {

  private Shake256() {}

  private static final int RATE = 136;

  private static final long[] ROUND_CONSTANTS = {
    0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
    0x000000000000808bL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
    0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
    0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
    0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
    0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
  };

  /** The rho rotation amounts, in the order the rho-pi walk visits lanes. */
  private static final int[] ROTATIONS = {
    1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44
  };

  /** The lane the rho-pi walk moves to at each step. */
  private static final int[] PI_LANES = {
    10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1
  };

  static byte[] digest(byte[] input, int outputLength) {
    long[] state = new long[25];
    byte[] block = new byte[RATE];

    int offset = 0;
    while (input.length - offset >= RATE) {
      System.arraycopy(input, offset, block, 0, RATE);
      absorb(state, block);
      offset += RATE;
    }

    Arrays.fill(block, (byte) 0);
    System.arraycopy(input, offset, block, 0, input.length - offset);
    block[input.length - offset] = 0x1f;
    block[RATE - 1] |= (byte) 0x80;
    absorb(state, block);

    byte[] output = new byte[outputLength];
    int produced = 0;
    while (produced < outputLength) {
      int chunk = Math.min(RATE, outputLength - produced);
      for (int i = 0; i < chunk; i++) {
        output[produced + i] = (byte) (state[i / 8] >>> (8 * (i % 8)));
      }
      produced += chunk;
      if (produced < outputLength) {
        permute(state);
      }
    }
    return output;
  }

  private static void absorb(long[] state, byte[] block) {
    for (int i = 0; i < RATE / 8; i++) {
      long lane = 0;
      for (int b = 0; b < 8; b++) {
        lane |= (block[i * 8 + b] & 0xffL) << (8 * b);
      }
      state[i] ^= lane;
    }
    permute(state);
  }

  private static void permute(long[] state) {
    long[] lanes = new long[5];
    for (int round = 0; round < 24; round++) {
      for (int i = 0; i < 5; i++) {
        lanes[i] = state[i] ^ state[i + 5] ^ state[i + 10] ^ state[i + 15] ^ state[i + 20];
      }
      for (int i = 0; i < 5; i++) {
        long parity = lanes[(i + 4) % 5] ^ Long.rotateLeft(lanes[(i + 1) % 5], 1);
        for (int j = 0; j < 25; j += 5) {
          state[j + i] ^= parity;
        }
      }

      long carried = state[1];
      for (int i = 0; i < 24; i++) {
        int lane = PI_LANES[i];
        long held = state[lane];
        state[lane] = Long.rotateLeft(carried, ROTATIONS[i]);
        carried = held;
      }

      for (int j = 0; j < 25; j += 5) {
        System.arraycopy(state, j, lanes, 0, 5);
        for (int i = 0; i < 5; i++) {
          state[j + i] = lanes[i] ^ (~lanes[(i + 1) % 5] & lanes[(i + 2) % 5]);
        }
      }

      state[0] ^= ROUND_CONSTANTS[round];
    }
  }
}
