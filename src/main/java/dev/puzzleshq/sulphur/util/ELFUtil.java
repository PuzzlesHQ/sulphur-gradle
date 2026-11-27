package dev.puzzleshq.sulphur.util;

import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Utility class for inspecting ELF (Executable and Linkable Format) binaries
 * and determining whether they are compatible with the running JVM's
 * architecture and native byte order.
 *
 * <p>This is primarily intended to validate native libraries and executables
 * before attempting to load or extract them. This checks the ELF class
 * (32-bit or 64-bit), byte order (little-endian or big-endian), and machine
 * architecture against the current runtime environment.</p>
 *
 * <p>Non-ELF files are considered compatible and are not subject to ELF
 * validation.</p>
 *
 * <p>This class supports the following architectures:</p>
 * <ul>
 *     <li>x86 (32-bit)</li>
 *     <li>x86-64 (64-bit)</li>
 *     <li>ARM (32-bit)</li>
 *     <li>AArch64 (64-bit)</li>
 *     <li>RISC-V (64-bit)</li>
 *     <li>PowerPC (64-bit)</li>
 *     <li>IBM System z (64-bit)</li>
 * </ul>
 *
 * @author M4ximumPizza
 *
 * @see MachOUtil
 * @see PEUtil
 */
public final class ELFUtil {

    private static final int ELF_MAGIC_0 = 0x7F;
    private static final int ELF_MAGIC_1 = 'E';
    private static final int ELF_MAGIC_2 = 'L';
    private static final int ELF_MAGIC_3 = 'F';

    /**
     * Prevents instantiation of this utility class.
     */
    private ELFUtil() {
    }

    /**
     * Determines whether an ELF binary is compatible with the current
     * runtime environment.
     *
     * <p>The ELF header is inspected to verify the following properties:</p>
     * <ol>
     *     <li>ELF identification magic number.</li>
     *     <li>ELF class (32-bit or 64-bit).</li>
     *     <li>Byte order (little-endian or big-endian).</li>
     *     <li>Machine architecture ({@code e_machine}).</li>
     * </ol>
     *
     * <p>The binary's ELF class must match {@link OSUtil#ARCH_BITS}, its
     * byte order must match the native byte order of the running system,
     * and its machine architecture must match the architecture reported
     * by {@code os.arch}.</p>
     *
     * <p>Files that do not contain a valid ELF magic number are considered
     * compatible and bypass ELF validation. This allows non-ELF files to
     * pass through without being rejected.</p>
     *
     * <p>A malformed or incomplete ELF header is considered incompatible.
     * At least 20 bytes are required to inspect all relevant header fields.</p>
     *
     * @param header the byte array containing the binary's ELF header
     * @param length the number of valid bytes available in {@code header}
     * @return {@code true} if the file is not an ELF binary or its ELF
     *         architecture matches the current runtime environment;
     *         {@code false} if the header is invalid, incomplete, or
     *         incompatible with the current runtime environment
     */
    public static boolean isCompatibleElf(byte[] header, int length) {

        if (header == null || length < 0 || length > header.length) {
            return false;
        }

        // Non-ELF files are not subject to ELF validation.
        if (length < 4 ||
                (header[0] & 0xFF) != ELF_MAGIC_0 ||
                (header[1] & 0xFF) != ELF_MAGIC_1 ||
                (header[2] & 0xFF) != ELF_MAGIC_2 ||
                (header[3] & 0xFF) != ELF_MAGIC_3) {
            return true;
        }

        // ELF identification and machine fields require at least 20 bytes.
        if (length < 20) {
            return false;
        }

        int elfClass = header[4] & 0xFF;

        // 1 = ELF32, 2 = ELF64
        if (elfClass != 1 && elfClass != 2) {
            return false;
        }

        int elfBits = elfClass == 1 ? 32 : 64;

        if (elfBits != OSUtil.ARCH_BITS) {
            return false;
        }

        // 1 = Little Endian, 2 = Big Endian
        int byteOrder = header[5] & 0xFF;

        if (byteOrder != 1 && byteOrder != 2) {
            return false;
        }

        boolean littleEndian = byteOrder == 1;

        if (littleEndian != (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN)) {
            return false;
        }

        int machine;

        if (littleEndian) {
            machine = (header[18] & 0xFF)
                    | ((header[19] & 0xFF) << 8);
        } else {
            machine = ((header[18] & 0xFF) << 8)
                    | (header[19] & 0xFF);
        }

        int expectedMachine = getExpectedMachine();

        if (expectedMachine == -1) {
            System.err.println(
                    "Unknown ELF architecture: " +
                            System.getProperty("os.arch")
            );

            return false;
        }

        return machine == expectedMachine;
    }

    /**
     * Resolves the ELF machine architecture corresponding to the
     * architecture of the currently running JVM.
     *
     * <p>The architecture is determined using the {@code os.arch}
     * system property. Recognized architecture names are normalized
     * to lowercase before being mapped to their respective ELF
     * {@code e_machine} identifiers.</p>
     *
     * <p>The returned values correspond to the ELF specification:</p>
     * <ul>
     *     <li>{@code 3} - Intel 80386 (x86)</li>
     *     <li>{@code 40} - ARM</li>
     *     <li>{@code 62} - AMD x86-64</li>
     *     <li>{@code 183} - AArch64</li>
     *     <li>{@code 21} - PowerPC 64-bit</li>
     *     <li>{@code 22} - IBM System z (s390x)</li>
     *     <li>{@code 243} - RISC-V</li>
     * </ul>
     *
     * <p>Multiple architecture aliases are supported where applicable,
     * such as {@code amd64} and {@code x86_64}.</p>
     *
     * @return the ELF {@code e_machine} identifier corresponding to the
     *         current JVM architecture, or {@code -1} if the architecture
     *         is not recognized
     */
    public static int getExpectedMachine() {

        String arch = System.getProperty("os.arch", "")
                .toLowerCase(Locale.ROOT);

        switch (arch) {

            // x86
            case "x86":
            case "i386":
            case "i486":
            case "i586":
            case "i686":
                return 3;

            // x86-64
            case "x86_64":
            case "amd64":
                return 62;

            // ARM 32-bit
            case "arm":
            case "arm32":
            case "armv7":
            case "armv7l":
                return 40;

            // ARM 64-bit
            case "aarch64":
            case "arm64":
                return 183;

            // RISC-V
            case "riscv64":
                return 243;

            // PowerPC
            case "ppc64":
            case "ppc64le":
                return 21;

            // IBM System z
            case "s390x":
                return 22;

            default:
                return -1;
        }
    }
}