package dev.puzzleshq.sulphur.util;

import java.util.Locale;

/**
 * Utility class for inspecting Mach-O (Mach Object) executable and dynamic
 * library binaries and determining whether they are compatible with the
 * architecture of the currently running JVM.
 *
 * <p>Mach-O is the native executable and object file format used by macOS.
 * It is used for executables, dynamic libraries, object files, and other
 * compiled binary artifacts.</p>
 *
 * <p>This utility performs lightweight binary header inspection rather than
 * complete Mach-O parsing. It identifies the binary format, determines its
 * target processor architecture, and compares that architecture against the
 * architecture reported by the currently running JVM through the
 * {@code os.arch} system property.</p>
 *
 * <p>The following Mach-O binary formats are supported:</p>
 * <ul>
 *     <li>32-bit thin Mach-O binaries.</li>
 *     <li>64-bit thin Mach-O binaries.</li>
 *     <li>32-bit universal (fat) Mach-O binaries.</li>
 *     <li>64-bit universal (fat) Mach-O binaries.</li>
 * </ul>
 *
 * <p>Both little-endian and big-endian Mach-O headers are supported.</p>
 *
 * <p>Supported processor architectures:</p>
 * <ul>
 *     <li>x86 (32-bit Intel).</li>
 *     <li>x86-64 / AMD64 (64-bit Intel/AMD).</li>
 *     <li>ARM (32-bit).</li>
 *     <li>ARM64 / AArch64 (64-bit).</li>
 * </ul>
 *
 * <p>Universal binaries are considered compatible when at least one of their
 * architecture entries matches the architecture reported by the running JVM.
 * This allows a universal binary containing both Intel and Apple Silicon
 * implementations to be accepted by either architecture.</p>
 *
 * <p>Architecture compatibility is determined using the JVM's reported
 * architecture rather than the physical processor architecture. This is
 * particularly relevant on Apple Silicon systems running an x86-64 JVM
 * through Rosetta, where the JVM reports {@code x86_64} despite the physical
 * processor being ARM64.</p>
 *
 * <p>This utility only examines binary headers. It does not validate the
 * complete Mach-O structure, load commands, segments, sections, executable
 * contents, dynamic library dependencies, or whether the operating system
 * can successfully load the binary.</p>
 *
 * <p>Malformed, incomplete, unsupported, or architecture-incompatible
 * binaries are considered incompatible.</p>
 *
 * @author M4ximumPizza
 *
 * @see PEUtil
 * @see ELFUtil
 */
public final class MachOUtil {

    private static final int MH_MAGIC = 0xFEEDFACE;
    private static final int MH_MAGIC_64 = 0xFEEDFACF;
    private static final int FAT_MAGIC = 0xCAFEBABE;
    private static final int FAT_MAGIC_64 = 0xCAFEBABF;
    private static final int FAT_CIGAM = 0xBEBAFECA;
    private static final int FAT_CIGAM_64 = 0xBFBAFECA;
    private static final int CPU_TYPE_X86 = 7;

    private static final int CPU_TYPE_X86_64 = 0x01000007;
    private static final int CPU_TYPE_ARM = 12;
    private static final int CPU_TYPE_ARM64 = 0x0100000C;

    /**
     * Prevents instantiation of this utility class.
     */
    private MachOUtil() {
    }

    /**
     * Determines whether the supplied binary header represents a Mach-O
     * binary compatible with the architecture of the currently running JVM.
     *
     * <p>This method identifies the Mach-O format by inspecting its initial
     * four-byte magic number. Based on the identified format, it delegates
     * architecture validation to the appropriate thin or universal binary
     * inspection method.</p>
     *
     * <p>The following formats are recognized:</p>
     * <table border="1">
     *     <caption>Supported Mach-O magic numbers</caption>
     *     <tr>
     *         <th>Magic Number</th>
     *         <th>Format</th>
     *         <th>Byte Ordering</th>
     *     </tr>
     *     <tr>
     *         <td>{@code 0xFEEDFACE}</td>
     *         <td>32-bit Mach-O</td>
     *         <td>Big-endian</td>
     *     </tr>
     *     <tr>
     *         <td>{@code 0xFEEDFACF}</td>
     *         <td>64-bit Mach-O</td>
     *         <td>Big-endian</td>
     *     </tr>
     *     <tr>
     *         <td>{@code 0xCEFAEDFE}</td>
     *         <td>32-bit Mach-O</td>
     *         <td>Little-endian</td>
     *     </tr>
     *     <tr>
     *         <td>{@code 0xCFFAEDFE}</td>
     *         <td>64-bit Mach-O</td>
     *         <td>Little-endian</td>
     *     </tr>
     *     <tr>
     *         <td>{@code 0xCAFEBABE}</td>
     *         <td>32-bit universal binary</td>
     *         <td>Big-endian</td>
     *     </tr>
     *     <tr>
     *         <td>{@code 0xCAFEBABF}</td>
     *         <td>64-bit universal binary</td>
     *         <td>Big-endian</td>
     *     </tr>
     *     <tr>
     *         <td>{@code 0xBEBAFECA}</td>
     *         <td>32-bit universal binary</td>
     *         <td>Little-endian</td>
     *     </tr>
     *     <tr>
     *         <td>{@code 0xBFBAFECA}</td>
     *         <td>64-bit universal binary</td>
     *         <td>Little-endian</td>
     *     </tr>
     * </table>
     *
     * <p>For thin binaries, the CPU type is read directly from the Mach-O
     * header. For universal binaries, each architecture entry is inspected
     * until a compatible CPU type is found.</p>
     *
     * <p>A universal binary is considered compatible if at least one
     * architecture entry matches the running JVM. This method does not
     * validate the contents or offsets of the individual architecture
     * slices.</p>
     *
     * <p>Null arrays, invalid lengths, unrecognized magic numbers,
     * malformed headers, and unsupported architectures are rejected.</p>
     *
     * @param header Binary header bytes to inspect.
     * @param length Number of valid bytes contained in {@code header}.
     *
     * @return {@code true} if the supplied header represents a recognized
     * Mach-O binary containing an architecture compatible with the running
     * JVM; {@code false} otherwise.
     *
     * @see #isCompatibleThin(byte[], int, boolean, boolean)
     * @see #isCompatibleFat(byte[], int, boolean)
     * @see #isCompatibleArchitecture(int)
     */
    public static boolean isCompatibleMachO(byte[] header, int length) {
        if (header == null || length < 4 || length > header.length) {
            return false;
        }

        int magic = readIntBE(header, 0);

        switch (magic) {
            case MH_MAGIC:
                return isCompatibleThin(header, length, false, true);

            case MH_MAGIC_64:
                return isCompatibleThin(header, length, false, false);

            case 0xCEFAEDFE:
                return isCompatibleThin(header, length, true, true);

            case 0xCFFAEDFE:
                return isCompatibleThin(header, length, true, false);

            case FAT_MAGIC:
                return isCompatibleFat(header, length, false);

            case FAT_MAGIC_64:
                return isCompatibleFat(header, length, false, true);

            case FAT_CIGAM:
                return isCompatibleFat(header, length, true);

            case FAT_CIGAM_64:
                return isCompatibleFat(header, length, true, true);

            default:
                return false;
        }
    }

    /**
     * Determines whether a thin Mach-O binary contains an architecture
     * compatible with the currently running JVM.
     *
     * <p>A thin Mach-O binary contains exactly one processor architecture.
     * Its CPU type is stored in the Mach-O header immediately following
     * the four-byte magic number.</p>
     *
     * <p>The CPU type is read using the byte ordering specified by the
     * binary's magic number.</p>
     *
     * <p>The method verifies that the supplied header contains enough
     * bytes for the corresponding Mach-O header structure before
     * attempting to read the CPU type.</p>
     *
     * <p>The minimum header sizes are:</p>
     * <ul>
     *     <li>32-bit Mach-O: 28 bytes.</li>
     *     <li>64-bit Mach-O: 32 bytes.</li>
     * </ul>
     *
     * @param header Binary header bytes to inspect.
     * @param length Number of valid bytes contained in {@code header}.
     * @param littleEndian {@code true} if the binary uses little-endian
     *                     byte ordering; {@code false} for big-endian.
     * @param is32Bit {@code true} if the binary uses a 32-bit Mach-O header;
     *                {@code false} if it uses a 64-bit Mach-O header.
     *
     * @return {@code true} if the binary's CPU type matches the architecture
     * of the running JVM; {@code false} otherwise.
     *
     * @see #isCompatibleArchitecture(int)
     * @see #readInt(byte[], int, boolean)
     */
    private static boolean isCompatibleThin(
            byte[] header,
            int length,
            boolean littleEndian,
            boolean is32Bit
    ) {
        int requiredLength = is32Bit ? 28 : 32;

        if (length < requiredLength) {
            return false;
        }

        int cpuType = readInt(header, 4, littleEndian);

        return isCompatibleArchitecture(cpuType);
    }

    /**
     * Determines whether a 32-bit universal (fat) Mach-O binary contains
     * an architecture compatible with the currently running JVM.
     *
     * <p>This overload delegates to the generalized universal binary
     * inspection method with the 32-bit architecture entry format.</p>
     *
     * <p>Each architecture entry in a 32-bit fat header occupies 20 bytes
     * and contains the CPU type, CPU subtype, slice offset, slice size,
     * and alignment information.</p>
     *
     * @param header Binary header bytes to inspect.
     * @param length Number of valid bytes contained in {@code header}.
     * @param littleEndian {@code true} if the universal header uses
     *                     little-endian byte ordering; {@code false}
     *                     for big-endian.
     *
     * @return {@code true} if at least one architecture entry matches the
     * architecture of the running JVM; {@code false} otherwise.
     *
     * @see #isCompatibleFat(byte[], int, boolean, boolean)
     */
    private static boolean isCompatibleFat(
            byte[] header,
            int length,
            boolean littleEndian
    ) {
        return isCompatibleFat(header, length, littleEndian, false);
    }

    /**
     * Determines whether a universal (fat) Mach-O binary contains an
     * architecture compatible with the currently running JVM.
     *
     * <p>A universal binary contains multiple architecture entries, each
     * describing a separate binary slice. Each entry identifies the CPU
     * architecture of its corresponding slice.</p>
     *
     * <p>This method inspects the architecture entries without extracting
     * or validating the actual binary slices.</p>
     *
     * <p>The architecture entry sizes are:</p>
     * <ul>
     *     <li>32-bit fat header: 20 bytes per entry.</li>
     *     <li>64-bit fat header: 32 bytes per entry.</li>
     * </ul>
     *
     * <p>The architecture count is validated against the available header
     * length before iteration. This prevents malformed headers from
     * requesting more architecture entries than the supplied byte array
     * can contain.</p>
     *
     * <p>The binary is considered compatible if at least one architecture
     * entry matches the architecture reported by the running JVM.</p>
     *
     * @param header Binary header bytes to inspect.
     * @param length Number of valid bytes contained in {@code header}.
     * @param littleEndian {@code true} if the universal header uses
     *                     little-endian byte ordering; {@code false}
     *                     for big-endian.
     * @param is64Bit {@code true} if the binary uses 64-bit universal
     *                architecture entries; {@code false} if it uses
     *                32-bit universal architecture entries.
     *
     * @return {@code true} if at least one architecture entry matches the
     * architecture of the running JVM; {@code false} otherwise.
     *
     * @see #isCompatibleArchitecture(int)
     * @see #readInt(byte[], int, boolean)
     */
    private static boolean isCompatibleFat(
            byte[] header,
            int length,
            boolean littleEndian,
            boolean is64Bit
    ) {
        if (length < 8) {
            return false;
        }

        int architectureCount = readInt(header, 4, littleEndian);

        if (architectureCount <= 0) {
            return false;
        }

        int entrySize = is64Bit ? 32 : 20;

        // Prevent malformed headers from causing excessive iteration.
        if (architectureCount > (length - 8) / entrySize) {
            return false;
        }

        for (int i = 0; i < architectureCount; i++) {
            int offset = 8 + (i * entrySize);

            int cpuType = readInt(header, offset, littleEndian);

            if (isCompatibleArchitecture(cpuType)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Determines whether a Mach-O CPU type identifier matches the
     * architecture reported by the currently running JVM.
     *
     * <p>The current JVM architecture is obtained from the
     * {@code os.arch} system property and normalized to lowercase
     * using {@link Locale#ROOT}.</p>
     *
     * <p>The following architecture identifiers and aliases are recognized:</p>
     * <table border="1">
     *     <caption>Mach-O CPU architecture compatibility</caption>
     *     <tr>
     *         <th>Mach-O CPU Type</th>
     *         <th>Architecture</th>
     *         <th>Recognized JVM Architecture Names</th>
     *     </tr>
     *     <tr>
     *         <td>{@code 7}</td>
     *         <td>x86</td>
     *         <td>{@code x86}, {@code i386}, {@code i486},
     *         {@code i586}, {@code i686}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code 0x01000007}</td>
     *         <td>x86-64</td>
     *         <td>{@code amd64}, {@code x86_64}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code 12}</td>
     *         <td>ARM32</td>
     *         <td>{@code arm}, {@code arm32}, {@code armv7},
     *         {@code armv7l}</td>
     *     </tr>
     *     <tr>
     *         <td>{@code 0x0100000C}</td>
     *         <td>ARM64</td>
     *         <td>{@code aarch64}, {@code arm64}</td>
     *     </tr>
     * </table>
     *
     * <p>This comparison checks architecture compatibility only. It does
     * not account for instruction-set extensions, CPU subtypes, operating
     * system compatibility, or execution through translation layers.</p>
     *
     * @param cpuType Mach-O CPU type identifier obtained from a Mach-O
     *                or universal binary header.
     *
     * @return {@code true} if the supplied CPU type matches the architecture
     * reported by the running JVM; {@code false} if the architecture does
     * not match or is unsupported.
     *
     * @see System#getProperty(String)
     * @see #CPU_TYPE_X86
     * @see #CPU_TYPE_X86_64
     * @see #CPU_TYPE_ARM
     * @see #CPU_TYPE_ARM64
     */
    private static boolean isCompatibleArchitecture(int cpuType) {
        String architecture = System.getProperty("os.arch", "")
                .toLowerCase(Locale.ROOT);

        switch (cpuType) {
            case CPU_TYPE_X86:
                return architecture.equals("x86")
                        || architecture.equals("i386")
                        || architecture.equals("i486")
                        || architecture.equals("i586")
                        || architecture.equals("i686");

            case CPU_TYPE_X86_64:
                return architecture.equals("amd64")
                        || architecture.equals("x86_64");

            case CPU_TYPE_ARM:
                return architecture.equals("arm")
                        || architecture.equals("arm32")
                        || architecture.equals("armv7")
                        || architecture.equals("armv7l");

            case CPU_TYPE_ARM64:
                return architecture.equals("aarch64")
                        || architecture.equals("arm64");

            default:
                return false;
        }
    }

    /**
     * Reads a 32-bit integer from a byte array using big-endian byte ordering.
     *
     * <p>The first byte is interpreted as the most significant byte and
     * the fourth byte as the least significant byte.</p>
     *
     * <p>All bytes are treated as unsigned values during decoding to
     * preserve their original bit patterns.</p>
     *
     * <p>This method is primarily used to inspect Mach-O magic numbers,
     * which are initially interpreted using big-endian ordering to
     * distinguish between the supported binary formats.</p>
     *
     * @param data Source byte array.
     * @param offset Starting byte offset of the integer.
     *
     * @return The decoded 32-bit integer.
     *
     * @see #readInt(byte[], int, boolean)
     */
    private static int readIntBE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    /**
     * Reads a 32-bit integer from a byte array using the specified
     * byte ordering.
     *
     * <p>When {@code littleEndian} is {@code true}, the first byte is
     * interpreted as the least significant byte. Otherwise, the integer
     * is decoded using big-endian byte ordering.</p>
     *
     * <p>All bytes are treated as unsigned values during decoding to
     * preserve their original bit patterns.</p>
     *
     * <p>The caller is responsible for ensuring that the supplied byte
     * array contains at least four bytes starting at the specified offset.</p>
     *
     * @param data Source byte array.
     * @param offset Starting byte offset of the integer.
     * @param littleEndian {@code true} to decode using little-endian
     *                     byte ordering; {@code false} to decode using
     *                     big-endian byte ordering.
     *
     * @return The decoded 32-bit integer.
     *
     * @see #readIntBE(byte[], int)
     */
    private static int readInt(
            byte[] data,
            int offset,
            boolean littleEndian
    ) {
        if (littleEndian) {
            return (data[offset] & 0xFF)
                    | ((data[offset + 1] & 0xFF) << 8)
                    | ((data[offset + 2] & 0xFF) << 16)
                    | ((data[offset + 3] & 0xFF) << 24);
        }

        return readIntBE(data, offset);
    }
}