package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.game.GameInstance;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * Neuters two methods of {@code zombie.gameStates.MainScreenState} in the player's own copy.
 *
 * <ul>
 *   <li><b>{@code printSpecs()}</b> asks oshi for the host's hardware inventory. That walk dies on
 *       Android, on the main menu, before the player can act. Nothing reads its result - it only
 *       writes machine specs to the log.</li>
 *   <li><b>{@code renderVideo()}</b> plays the Build 42 title-screen video through Bink. There is
 *       no ARM64 Bink: {@code VideoTexture}'s static initialiser runs
 *       {@code System.loadLibrary("bink64")}, our stub carries the Build 41 name
 *       ({@code libBink2x64.so}), and the search reaches the game directory and finds the game's
 *       own <i>x86_64</i> {@code libbink64.so} - "is for EM_X86_64 (62) instead of EM_AARCH64
 *       (183)". Uncaught, it kills MainThread on the first frame of the main menu.</li>
 * </ul>
 *
 * <p>The video fix is the game's own fallback rather than an invention. {@code renderBackground()}
 * reads: {@code renderVideo()}, {@code ifne done}, else {@code renderOriginalBackground(F)}. So a
 * method that just returns {@code false} makes the game draw its static background, which is what
 * players had been seeing all along - see the history note below.
 *
 * <p>An empty stub named {@code libbink64.so} would <b>not</b> be enough:
 * {@code VideoTexture.LoadVideoFile()} calls the native {@code openVideo()} with no try/catch, so
 * an ELF without JNI symbols only moves the UnsatisfiedLinkError one frame later. A working stub
 * would have to export all ten of the class's native methods and answer -1.
 *
 * <h3>Why this crash appeared only in 1.4.9</h3>
 *
 * <p>Until 5298f95 these classes were fixed by swapping in a pre-built replacement chosen <b>by
 * file size</b>, and the window for the 42.17 asset was 33100-33500 bytes. Build 42.20's
 * MainScreenState is 33329 bytes, so every 42.20 instance silently ran our <b>42.17</b> class - in
 * which {@code renderVideo} is declared and never called, so Bink was never touched. Proven from
 * her own logs: {@code LuaEventManager.triggerEvent} inside {@code enter()} is at bytecode offset
 * 230 in both classes, but line 434 in the 42.17 asset and line 446 in the real 42.20 class; the
 * working 1.4.8 log says 434, the crash log says 446. Patching the real class was correct and
 * removed an accidental workaround along with the substitution.
 *
 * <p>Hence no size gate here. A size window was only ever an <i>asset selector</i>, and inheriting
 * it as a version gate is what made this patch fire on 42.20 in the first place. The gate is now
 * simply whether the method is present with the expected descriptor: absent means nothing to do.
 *
 * <p>The edit rewrites one method's {@code Code} attribute to a minimal body. Dropping
 * {@code StackMapTable} is correct rather than tolerated - a body without a single branch needs no
 * stack map frames. Constant-pool entries the old bodies used stay behind unused, which is legal
 * and cheaper than renumbering the pool. The original class is kept beside the patched one as
 * {@code .class.disabled}.
 */
public final class MainScreenStatePatchApplier {
    private static final String LOG_TAG = MainScreenStatePatchApplier.class.getSimpleName();

    private static final String CLASS_REL_PATH = "zombie/gameStates/MainScreenState.class";

    /** What a neutered method should hand back. */
    private enum Result {
        VOID,          // return
        FALSE          // iconst_0; ireturn
    }

    private MainScreenStatePatchApplier() {}

    public static void applyIfNeeded(GameInstance gameInstance) {
        if (!"42".equals(gameInstance.getBuildVersion())) return;

        File target = new File(gameInstance.getGamePath(), CLASS_REL_PATH);
        if (!target.isFile()) return;

        try {
            byte[] original = Files.readAllBytes(target.toPath());
            byte[] patched = neuter(original, "printSpecs", "()V", Result.VOID);
            patched = neuter(patched, "renderVideo", "()Z", Result.FALSE);
            if (Arrays.equals(patched, original)) return; // already done, or neither method here

            File backup = new File(target.getAbsolutePath() + ".disabled");
            if (!backup.exists()) {
                Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            File tmp = new File(target.getAbsolutePath() + ".tmp");
            Files.write(tmp.toPath(), patched);
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);

            Log.i(LOG_TAG, "Neutered MainScreenState.printSpecs()/renderVideo() ("
                    + original.length + " -> " + patched.length + " bytes)");
        } catch (Exception e) {
            // A half-written class is worse than the original crash staying diagnosable.
            Log.e(LOG_TAG, "Failed to patch MainScreenState, leaving the class untouched", e);
        }
    }

    // ---- class-file surgery ----------------------------------------------------------------

    /**
     * Returns the class file with the named method's body replaced by a minimal one, or the input
     * unchanged when the class does not declare that method or its body is already minimal.
     *
     * @throws IOException if the file is not a class, or the method exists without a {@code Code}
     *                     attribute - never guess, leave the class alone instead.
     */
    static byte[] neuter(byte[] classFile, String methodName, String descriptor, Result result)
            throws IOException {
        if (classFile.length < 10 || readU4(classFile, 0) != 0xCAFEBABEL) {
            throw new IOException("not a class file");
        }

        int cpCount = readU2(classFile, 8);
        int off = 10;
        int nameIndex = -1, descriptorIndex = -1, codeIndex = -1;

        for (int i = 1; i < cpCount; ) {
            int tag = classFile[off] & 0xFF;
            switch (tag) {
                case 1: { // CONSTANT_Utf8
                    int len = readU2(classFile, off + 1);
                    String text = new String(classFile, off + 3, len, StandardCharsets.UTF_8);
                    if (text.equals(methodName)) nameIndex = i;
                    else if (text.equals(descriptor)) descriptorIndex = i;
                    else if (text.equals("Code")) codeIndex = i;
                    off += 3 + len;
                    break;
                }
                case 7: case 8: case 16: case 19: case 20: off += 3; break;
                case 15: off += 4; break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: off += 5; break;
                case 5: case 6: off += 9; i++; break; // long/double take two slots
                default: throw new IOException("unknown constant pool tag " + tag + " at " + off);
            }
            i++;
        }
        // A name the class never mentions cannot be one of its methods.
        if (nameIndex < 0 || descriptorIndex < 0 || codeIndex < 0) return classFile;

        // access_flags, this_class, super_class, interfaces_count, interfaces[]
        int p = off + 6;
        p += 2 + readU2(classFile, p) * 2;
        p = skipMembers(classFile, p);  // fields
        int methodsCount = readU2(classFile, p);
        p += 2;

        for (int m = 0; m < methodsCount; m++) {
            int accessFlags = readU2(classFile, p);
            boolean isTarget = readU2(classFile, p + 2) == nameIndex
                    && readU2(classFile, p + 4) == descriptorIndex;
            int attributesCount = readU2(classFile, p + 6);
            int a = p + 8;

            for (int i = 0; i < attributesCount; i++) {
                int attributeNameIndex = readU2(classFile, a);
                int attributeLength = (int) readU4(classFile, a + 2);
                if (isTarget && attributeNameIndex == codeIndex) {
                    byte[] body = minimalBody(result, localsFor(descriptor, accessFlags));
                    return splice(classFile, a + 6, attributeLength, body);
                }
                a += 6 + attributeLength;
            }
            if (isTarget) throw new IOException("no Code attribute for " + methodName + descriptor);
            p = a;
        }
        return classFile; // declared nowhere in this class
    }

    /** The whole content of a {@code Code} attribute holding nothing but the return. */
    private static byte[] minimalBody(Result result, int maxLocals) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeU2(body, result == Result.FALSE ? 1 : 0);   // max_stack
        writeU2(body, maxLocals);
        if (result == Result.FALSE) {
            writeU4(body, 2);
            body.write(0x03);   // iconst_0
            body.write(0xAC);   // ireturn
        } else {
            writeU4(body, 1);
            body.write(0xB1);   // return
        }
        writeU2(body, 0);   // exception_table_length
        writeU2(body, 0);   // attributes_count: no StackMapTable needed without branches
        return body.toByteArray();
    }

    /**
     * Local slots the frame must still declare: the receiver plus every argument. The body uses
     * none of them, but the verifier sizes the frame from the descriptor, not from the code.
     */
    private static int localsFor(String descriptor, int accessFlags) {
        int slots = (accessFlags & 0x0008) != 0 ? 0 : 1;  // ACC_STATIC
        int i = descriptor.indexOf('(') + 1;
        while (i < descriptor.length() && descriptor.charAt(i) != ')') {
            char c = descriptor.charAt(i);
            if (c == '[') { i++; continue; }
            if (c == 'L') { i = descriptor.indexOf(';', i) + 1; slots++; continue; }
            slots += (c == 'J' || c == 'D') ? 2 : 1;
            i++;
        }
        return slots;
    }

    /** Replaces the Code attribute's content at {@code contentOff} and fixes its length field. */
    private static byte[] splice(byte[] classFile, int contentOff, int contentLength, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(classFile.length);
        out.write(classFile, 0, contentOff - 4);
        writeU4(out, body.length);
        out.write(body, 0, body.length);
        int tailOff = contentOff + contentLength;
        out.write(classFile, tailOff, classFile.length - tailOff);
        return out.toByteArray();
    }

    /** Skips a {@code fields_count}/{@code methods_count} table, returning the offset after it. */
    private static int skipMembers(byte[] classFile, int off) {
        int count = readU2(classFile, off);
        int p = off + 2;
        for (int i = 0; i < count; i++) {
            int attributesCount = readU2(classFile, p + 6);
            p += 8;
            for (int a = 0; a < attributesCount; a++) {
                p += 6 + (int) readU4(classFile, p + 2);
            }
        }
        return p;
    }

    private static int readU2(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long readU4(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static void writeU2(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeU4(ByteArrayOutputStream out, int v) {
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }
}
