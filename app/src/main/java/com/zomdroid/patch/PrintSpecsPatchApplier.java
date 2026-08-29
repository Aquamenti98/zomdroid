package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.game.GameInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Empties {@code zombie.gameStates.MainScreenState.printSpecs()} in the player's own copy.
 *
 * The method asks oshi for the host's hardware inventory. On Android that walk dies, and it dies
 * on the main screen, before the player can do anything about it. The fix is to make the method a
 * no-op: it only writes machine specs to the log, nothing reads its result.
 *
 * <p>Previously this shipped as two pre-built replacement classes, one per affected game build,
 * chosen by file size. Doing it as constant-pool surgery on the installed class removes the size
 * table with it: the patch now applies to any class carrying this method, so a re-released build
 * of the same version needs no new asset. It also reaches instances created by an older launcher,
 * because it runs at every launch instead of once at instance creation.
 *
 * <p>The edit is one method's {@code Code} attribute, rewritten to a bare {@code return}:
 * {@code max_stack=0, max_locals=0, code=[0xB1]}, empty exception table, no nested attributes.
 * Dropping {@code StackMapTable} is correct rather than merely tolerated — a body without a single
 * branch needs no stack map frames. Constant-pool entries the old body used stay behind unused,
 * which is legal and cheaper than renumbering the pool.
 *
 * <p>Gated to the two builds where the crash was actually observed (42.15 and 42.17, by class
 * size). Widen the gate when a log shows the same oshi crash on a later build — until then the
 * spec dump is worth keeping, since it is the only place the game reports the host's hardware.
 *
 * <p>The original class is kept beside the patched one as {@code .class.disabled}, the same name
 * and the same meaning as everywhere else in the launcher: present means the work is done and the
 * untouched original is recoverable.
 */
public final class PrintSpecsPatchApplier {
    private static final String LOG_TAG = PrintSpecsPatchApplier.class.getSimpleName();

    private static final String CLASS_REL_PATH = "zombie/gameStates/MainScreenState.class";
    private static final String METHOD_NAME = "printSpecs";
    private static final String METHOD_DESCRIPTOR = "()V";

    /** Class-file size of the builds whose printSpecs() is known to crash (42.15 and 42.17). */
    private static final long MIN_AFFECTED_SIZE = 32700;
    private static final long MAX_AFFECTED_SIZE = 33500;

    private PrintSpecsPatchApplier() {}

    public static void applyIfNeeded(GameInstance gameInstance) {
        if (!"42".equals(gameInstance.getBuildVersion())) return;

        java.io.File oshiDir = new java.io.File(gameInstance.getGamePath(), "oshi");
        if (!oshiDir.isDirectory()) return; // no oshi, no crash to patch away

        java.io.File target = new java.io.File(gameInstance.getGamePath(), CLASS_REL_PATH);
        if (!target.isFile()) return;

        java.io.File backup = new java.io.File(target.getAbsolutePath() + ".disabled");
        if (backup.exists()) return; // already patched, here or by an older launcher version

        long size = target.length();
        if (size < MIN_AFFECTED_SIZE || size > MAX_AFFECTED_SIZE) return;

        try {
            byte[] original = Files.readAllBytes(target.toPath());
            byte[] patched = emptyMethodBody(original, METHOD_NAME, METHOD_DESCRIPTOR);

            Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            java.io.File tmp = new java.io.File(target.getAbsolutePath() + ".tmp");
            Files.write(tmp.toPath(), patched);
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);

            Log.i(LOG_TAG, "Emptied MainScreenState." + METHOD_NAME + "() ("
                    + original.length + " -> " + patched.length + " bytes)");
        } catch (Exception e) {
            // A half-written class is worse than the original crash staying diagnosable.
            Log.e(LOG_TAG, "Failed to patch MainScreenState, leaving the class untouched", e);
        }
    }

    // ---- class-file surgery ----------------------------------------------------------------

    /**
     * Returns a copy of the class file whose named method carries an empty body.
     *
     * @throws IOException if the file is not a class, or does not declare that method with a
     *                     {@code Code} attribute — never guess, leave the class alone instead.
     */
    static byte[] emptyMethodBody(byte[] classFile, String methodName, String descriptor)
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
        if (nameIndex < 0 || descriptorIndex < 0 || codeIndex < 0) {
            throw new IOException("class does not name " + methodName + descriptor + " and Code");
        }

        // access_flags, this_class, super_class, interfaces_count, interfaces[]
        int p = off + 6;
        p += 2 + readU2(classFile, p) * 2;
        p = skipMembers(classFile, p);  // fields
        int methodsCount = readU2(classFile, p);
        p += 2;

        for (int m = 0; m < methodsCount; m++) {
            int methodNameIndex = readU2(classFile, p + 2);
            int methodDescriptorIndex = readU2(classFile, p + 4);
            int attributesCount = readU2(classFile, p + 6);
            int a = p + 8;
            boolean isTarget = methodNameIndex == nameIndex
                    && methodDescriptorIndex == descriptorIndex;

            for (int i = 0; i < attributesCount; i++) {
                int attributeNameIndex = readU2(classFile, a);
                int attributeLength = (int) readU4(classFile, a + 2);
                if (isTarget && attributeNameIndex == codeIndex) {
                    return replaceAttribute(classFile, a + 6, attributeLength);
                }
                a += 6 + attributeLength;
            }
            p = a;
        }
        throw new IOException("no Code attribute for " + methodName + descriptor);
    }

    /** Splices an empty method body over the Code attribute's content at {@code contentOff}. */
    private static byte[] replaceAttribute(byte[] classFile, int contentOff, int contentLength)
            throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeU2(body, 0);        // max_stack
        writeU2(body, 0);        // max_locals: a static ()V frame holds nothing
        writeU4(body, 1);        // code_length
        body.write(0xB1);        // return
        writeU2(body, 0);        // exception_table_length
        writeU2(body, 0);        // attributes_count: no StackMapTable needed without branches

        byte[] replacement = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(classFile.length);
        out.write(classFile, 0, contentOff - 4);
        writeU4(out, replacement.length);          // the attribute's own length
        out.write(replacement, 0, replacement.length);
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
