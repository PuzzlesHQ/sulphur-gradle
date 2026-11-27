package dev.puzzleshq.sulphur.util;

import java.util.Locale;

/**
 * Utility class for inspecting and validating Windows Portable Executable (PE)
 * binary headers.
 *
 * <p>The Portable Executable format is used by Windows executables, dynamic
 * libraries, and other binary files. PE files contain a DOS header followed
 * by a PE signature, COFF file header, and optional header.</p>
 *
 * <p>This utility provides functionality to:</p>
 * <ul>
 *     <li>Identify PE binaries using their DOS and PE signatures.</li>
 *     <li>Determine the target architecture of a PE binary.</li>
 *     <li>Check whether a PE binary matches the architecture of the
 *     currently running JVM.</li>
 * </ul>
 *
 * <p>Supported architectures:</p>
 * <ul>
 *     <li>x86 (32-bit)</li>
 *     <li>x86-64 / AMD64 (64-bit)</li>
 *     <li>ARM (32-bit)</li>
 *     <li>ARM64 / AArch64 (64-bit)</li>
 * </ul>
 *
 * <p>This class performs lightweight header inspection rather than complete
 * PE parsing. It does not validate the entire PE structure, optional header,
 * sections, or executable contents.</p>
 *
 * <p>All integer fields are interpreted using little-endian byte ordering,
 * as required by the PE format.</p>
 *
 * @author M4ximumPizza
 *
 * @see MachOUtil
 * @see ELFUtil
 */
public final class PEUtil {

    private static final int DOS_HEADER_SIZE = 64;
    private static final int PE_SIGNATURE_SIZE = 4;
    private static final int IMAGE_FILE_MACHINE_I386 = 0x014C;
    private static final int IMAGE_FILE_MACHINE_AMD64 = 0x8664;
    private static final int IMAGE_FILE_MACHINE_ARM = 0x01C0;
    private static final int IMAGE_FILE_MACHINE_ARMNT = 0x01C4;
    private static final int IMAGE_FILE_MACHINE_ARM64 = 0xAA64;

    /**
     * Prevents instantiation of this utility class.
     */
    private PEUtil() {
    }

    /**
     * Determines whether the supplied binary header represents a recognized
     * Windows Portable Executable (PE) binary.
     *
     * <p>This method validates the following:</p>
     * <ol>
     *     <li>The supplied header contains at least the minimum DOS header.</li>
     *     <li>The DOS signature is {@code MZ}.</li>
     *     <li>The PE header offset is within the supplied header.</li>
     *     <li>The PE signature is {@code PE\0\0}.</li>
     *     <li>The COFF machine identifier corresponds to a supported
     *     architecture.</li>
     * </ol>
     *
     * <p>This method does not check whether the binary is compatible with
     * the current operating system or JVM architecture.</p>
     *
     * @param header Binary header bytes to inspect.
     * @param length Number of valid bytes contained in {@code header}.
     *
     * @return {@code true} if the supplied header contains a recognized PE
     * binary; {@code false} otherwise.
     *
     * @see #getArchitecture(byte[], int)
     */
    public static boolean isPE(byte[] header, int length) {
        return getArchitecture(header, length) != null;
    }

    /**
     * Determines whether the supplied PE binary is compatible with the
     * architecture reported by the currently running JVM.
     *
     * <p>The architecture is obtained from the PE COFF machine identifier
     * and compared against the JVM's {@code os.arch} system property.</p>
     *
     * <p>Recognized JVM architecture identifiers include:</p>
     * <ul>
     *     <li>{@code x86}, {@code i386}, {@code i486}, {@code i586},
     *     {@code i686}</li>
     *     <li>{@code amd64}, {@code x86_64}</li>
     *     <li>{@code arm}, {@code arm32}</li>
     *     <li>{@code aarch64}, {@code arm64}</li>
     * </ul>
     *
     * <p>This is an architecture equality check, not a complete test of
     * whether Windows can execute the binary. It does not account for
     * operating-system emulation or compatibility layers.</p>
     *
     * @param header Binary header bytes to inspect.
     * @param length Number of valid bytes contained in {@code header}.
     *
     * @return {@code true} if the PE architecture matches the architecture
     * reported by the current JVM; {@code false} otherwise.
     *
     * @see #getArchitecture(byte[], int)
     */
    public static boolean isCompatiblePE(byte[] header, int length) {
        Architecture architecture = getArchitecture(header, length);

        if (architecture == null) {
            return false;
        }

        String systemArchitecture = System.getProperty("os.arch")
                .toLowerCase(Locale.ROOT);

        switch (architecture) {
            case X86:
                return systemArchitecture.equals("x86")
                        || systemArchitecture.equals("i386")
                        || systemArchitecture.equals("i486")
                        || systemArchitecture.equals("i586")
                        || systemArchitecture.equals("i686");

            case X86_64:
                return systemArchitecture.equals("amd64")
                        || systemArchitecture.equals("x86_64");

            case ARM32:
                return systemArchitecture.equals("arm")
                        || systemArchitecture.equals("arm32");

            case ARM64:
                return systemArchitecture.equals("aarch64")
                        || systemArchitecture.equals("arm64");

            default:
                return false;
        }
    }

    /**
     * Identifies the target architecture of a Windows Portable Executable
     * binary by inspecting its DOS header, PE signature, and COFF machine
     * identifier.
     *
     * <p>The PE header location is obtained from the DOS header's
     * {@code e_lfanew} field at offset {@code 0x3C}.</p>
     *
     * <p>The COFF machine identifier is located immediately after the
     * four-byte PE signature.</p>
     *
     * <p>Supported machine identifiers:</p>
     * <table border="1">
     *     <caption>Supported PE machine architectures</caption>
     *     <tr>
     *         <th>Machine Identifier</th>
     *         <th>Architecture</th>
     *     </tr>
     *     <tr>
     *         <td>{@code 0x014C}</td>
     *         <td>x86</td>
     *     </tr>
     *     <tr>
     *         <td>{@code 0x8664}</td>
     *         <td>x86-64</td>
     *     </tr>
     *     <tr>
     *         <td>{@code 0x01C0}</td>
     *         <td>ARM</td>
     *     </tr>
     *     <tr>
     *         <td>{@code 0x01C4}</td>
     *         <td>ARM Thumb-2</td>
     *     </tr>
     *     <tr>
     *         <td>{@code 0xAA64}</td>
     *         <td>ARM64</td>
     *     </tr>
     * </table>
     *
     * <p>Unknown machine identifiers and malformed headers are rejected.</p>
     *
     * @param header Binary header bytes to inspect.
     * @param length Number of valid bytes contained in {@code header}.
     *
     * @return The identified {@link Architecture}, or {@code null} if the
     * header is invalid, incomplete, or contains an unsupported architecture.
     *
     * @see Architecture
     */
    public static Architecture getArchitecture(byte[] header, int length) {
        if (header == null || length < DOS_HEADER_SIZE || length > header.length) {
            return null;
        }

        // DOS signature: MZ
        if (header[0] != 'M' || header[1] != 'Z') {
            return null;
        }

        // e_lfanew: Offset of the PE header.
        int peOffset = readIntLE(header, 0x3C);

        if (peOffset < DOS_HEADER_SIZE
                || peOffset > length - PE_SIGNATURE_SIZE - 2) {
            return null;
        }

        // PE signature: PE\0\0
        if (header[peOffset] != 'P'
                || header[peOffset + 1] != 'E'
                || header[peOffset + 2] != 0
                || header[peOffset + 3] != 0) {
            return null;
        }

        int machine = readShortLE(header, peOffset + 4);

        switch (machine) {
            case IMAGE_FILE_MACHINE_I386:
                return Architecture.X86;

            case IMAGE_FILE_MACHINE_AMD64:
                return Architecture.X86_64;

            case IMAGE_FILE_MACHINE_ARM:
            case IMAGE_FILE_MACHINE_ARMNT:
                return Architecture.ARM32;

            case IMAGE_FILE_MACHINE_ARM64:
                return Architecture.ARM64;

            default:
                return null;
        }
    }

    /**
     * Reads an unsigned 16-bit integer from a byte array using little-endian
     * byte ordering.
     *
     * <p>The returned value is represented as a Java {@code int} to preserve
     * the full unsigned 16-bit range.</p>
     *
     * @param data Source byte array.
     * @param offset Starting byte offset.
     *
     * @return The decoded unsigned 16-bit integer.
     */
    private static int readShortLE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8);
    }

    /**
     * Reads a 32-bit integer from a byte array using little-endian
     * byte ordering.
     *
     * <p>The returned value uses Java's signed {@code int} representation.
     * PE header offsets are expected to be positive and within the supplied
     * header buffer.</p>
     *
     * @param data Source byte array.
     * @param offset Starting byte offset.
     *
     * @return The decoded 32-bit integer.
     */
    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    /**
     * Represents a processor architecture identified from a Windows
     * Portable Executable (PE) binary.
     *
     * <p>Each constant corresponds to one or more COFF machine identifiers
     * defined by the PE/COFF specification.</p>
     */
    public enum Architecture {

        /**
         * 32-bit Intel x86 architecture.
         */
        X86,

        /**
         * 64-bit Intel/AMD x86-64 architecture.
         */
        X86_64,

        /**
         * 32-bit ARM architecture, including ARM Thumb-2.
         */
        ARM32,

        /**
         * 64-bit ARM architecture (ARM64 / AArch64).
         */
        ARM64
    }
}