package com.rtmp.drone.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Helpers to read the H.264 data produced by the drone (FLV video tags / AVC format).
 * No Android dependency: everything here is pure byte handling.
 */
public final class H264Utils {

    private H264Utils() {}

    /** True when the FLV video tag carries an AVC decoder configuration record (SPS/PPS). */
    public static boolean isSequenceHeader(byte[] flvVideoTag) {
        return flvVideoTag != null && flvVideoTag.length > 1
                && (flvVideoTag[0] & 0x0F) == 0x07 && flvVideoTag[1] == 0x00;
    }

    /** True when the FLV video tag is a key frame (IDR). */
    public static boolean isKeyFrame(byte[] flvVideoTag) {
        return flvVideoTag != null && flvVideoTag.length > 0
                && ((flvVideoTag[0] & 0xF0) == 0x10);
    }

    /** FLV video tag (5 bytes header + AVCC payload) -> AVCC payload only. */
    public static byte[] videoPayload(byte[] flvVideoTag) {
        if (flvVideoTag == null || flvVideoTag.length <= 5) return null;
        return Arrays.copyOfRange(flvVideoTag, 5, flvVideoTag.length);
    }

    /** Composition time offset carried by an AVC FLV video tag (in milliseconds). */
    public static int compositionTimeOffsetMs(byte[] flvVideoTag) {
        if (flvVideoTag == null || flvVideoTag.length < 5) return 0;
        int cts = ((flvVideoTag[2] & 0xFF) << 16) | ((flvVideoTag[3] & 0xFF) << 8) | (flvVideoTag[4] & 0xFF);
        if ((cts & 0x800000) != 0) cts |= 0xFF000000; // sign extension
        return cts;
    }

    /**
     * Extracts the SPS and the PPS of an AVC configuration record (as found in a FLV
     * video tag) and returns them as one Annex-B buffer (start codes included),
     * ready to be used as "csd-0" for MediaCodec / MediaMuxer.
     */
    public static byte[] extractSpsPpsAnnexB(byte[] flvVideoTag) {
        List<byte[]> spsList = new ArrayList<>();
        List<byte[]> ppsList = new ArrayList<>();
        if (!parseAvcConfig(flvVideoTag, spsList, ppsList)) return null;
        if (spsList.isEmpty() || ppsList.isEmpty()) return null;

        int size = 0;
        for (byte[] nalu : spsList) size += 4 + nalu.length;
        for (byte[] nalu : ppsList) size += 4 + nalu.length;

        byte[] annexB = new byte[size];
        int offset = 0;
        for (byte[] nalu : spsList) offset = writeNalu(annexB, offset, nalu);
        for (byte[] nalu : ppsList) offset = writeNalu(annexB, offset, nalu);
        return annexB;
    }

    /** First SPS NAL unit (without start code) of an AVC configuration record, or null. */
    public static byte[] extractSps(byte[] flvVideoTag) {
        List<byte[]> spsList = new ArrayList<>();
        List<byte[]> ppsList = new ArrayList<>();
        if (!parseAvcConfig(flvVideoTag, spsList, ppsList) || spsList.isEmpty()) return null;
        return spsList.get(0);
    }

    /**
     * Reads the coded picture size from a SPS NAL unit (without start code).
     * @return {width, height} or null when the SPS cannot be parsed.
     */
    public static int[] parseSpsDimensions(byte[] spsNalu) {
        if (spsNalu == null || spsNalu.length < 4) return null;
        try {
            byte[] rbsp = removeEmulationPrevention(spsNalu, 1); // skip NAL header byte
            BitReader reader = new BitReader(rbsp);

            int profileIdc = (int) reader.readBits(8);
            reader.readBits(8); // constraint flags + reserved
            reader.readBits(8); // level_idc
            reader.readUe();    // seq_parameter_set_id

            int chromaFormatIdc = 1;
            if (isHighProfile(profileIdc)) {
                chromaFormatIdc = (int) reader.readUe();
                if (chromaFormatIdc == 3) reader.readBit(); // separate_colour_plane_flag
                reader.readUe(); // bit_depth_luma_minus8
                reader.readUe(); // bit_depth_chroma_minus8
                reader.readBit(); // qpprime_y_zero_transform_bypass_flag
                if (reader.readBit() == 1) { // seq_scaling_matrix_present_flag
                    int count = (chromaFormatIdc != 3) ? 8 : 12;
                    for (int i = 0; i < count; i++) {
                        if (reader.readBit() == 1) {
                            skipScalingList(reader, i < 6 ? 16 : 64);
                        }
                    }
                }
            }

            reader.readUe(); // log2_max_frame_num_minus4
            int picOrderCntType = (int) reader.readUe();
            if (picOrderCntType == 0) {
                reader.readUe(); // log2_max_pic_order_cnt_lsb_minus4
            } else if (picOrderCntType == 1) {
                reader.readBit(); // delta_pic_order_always_zero_flag
                reader.readSe();  // offset_for_non_ref_pic
                reader.readSe();  // offset_for_top_to_bottom_field
                int cycle = (int) reader.readUe();
                if (cycle < 0 || cycle > 256) return null;
                for (int i = 0; i < cycle; i++) reader.readSe();
            }

            reader.readUe();  // max_num_ref_frames
            reader.readBit(); // gaps_in_frame_num_value_allowed_flag

            long picWidthInMbsMinus1 = reader.readUe();
            long picHeightInMapUnitsMinus1 = reader.readUe();
            int frameMbsOnlyFlag = reader.readBit();
            if (frameMbsOnlyFlag == 0) reader.readBit(); // mb_adaptive_frame_field_flag
            reader.readBit(); // direct_8x8_inference_flag

            int cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
            if (reader.readBit() == 1) { // frame_cropping_flag
                cropLeft = (int) reader.readUe();
                cropRight = (int) reader.readUe();
                cropTop = (int) reader.readUe();
                cropBottom = (int) reader.readUe();
            }

            int subWidthC = 1;
            int subHeightC = 1;
            if (chromaFormatIdc == 1) { subWidthC = 2; subHeightC = 2; }
            else if (chromaFormatIdc == 2) { subWidthC = 2; subHeightC = 1; }

            int width = (int) ((picWidthInMbsMinus1 + 1) * 16 - (cropLeft + cropRight) * subWidthC);
            int height = (int) ((2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16
                    - (cropTop + cropBottom) * subHeightC * (2 - frameMbsOnlyFlag));

            if (width <= 0 || height <= 0) return null;
            return new int[]{width, height};
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Parses an AVC decoder configuration record.
     * Layout (inside a FLV video tag): [0]=frame/codec, [1]=0, [2..4]=CTS,
     * [5]=configurationVersion, [6..8]=profile/level, [9]=lengthSizeMinusOne,
     * [10]=numOfSPS, then SPS list, then numOfPPS and the PPS list.
     */
    private static boolean parseAvcConfig(byte[] flvVideoTag, List<byte[]> spsList, List<byte[]> ppsList) {
        if (flvVideoTag == null || flvVideoTag.length < 11) return false;
        try {
            int pos = 5;
            int numSps = flvVideoTag[pos + 5] & 0x1F;
            pos += 6;
            for (int i = 0; i < numSps; i++) {
                if (pos + 2 > flvVideoTag.length) return false;
                int length = ((flvVideoTag[pos] & 0xFF) << 8) | (flvVideoTag[pos + 1] & 0xFF);
                pos += 2;
                if (length <= 0 || pos + length > flvVideoTag.length) return false;
                spsList.add(Arrays.copyOfRange(flvVideoTag, pos, pos + length));
                pos += length;
            }

            if (pos >= flvVideoTag.length) return false;
            int numPps = flvVideoTag[pos] & 0xFF;
            pos += 1;
            for (int i = 0; i < numPps; i++) {
                if (pos + 2 > flvVideoTag.length) return false;
                int length = ((flvVideoTag[pos] & 0xFF) << 8) | (flvVideoTag[pos + 1] & 0xFF);
                pos += 2;
                if (length <= 0 || pos + length > flvVideoTag.length) return false;
                ppsList.add(Arrays.copyOfRange(flvVideoTag, pos, pos + length));
                pos += length;
            }
            return !spsList.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static int writeNalu(byte[] destination, int offset, byte[] nalu) {
        destination[offset] = 0;
        destination[offset + 1] = 0;
        destination[offset + 2] = 0;
        destination[offset + 3] = 1;
        System.arraycopy(nalu, 0, destination, offset + 4, nalu.length);
        return offset + 4 + nalu.length;
    }

    private static boolean isHighProfile(int profileIdc) {
        switch (profileIdc) {
            case 100: case 110: case 122: case 244: case 44:
            case 83: case 86: case 118: case 128: case 138:
            case 139: case 134: case 135:
                return true;
            default:
                return false;
        }
    }

    private static void skipScalingList(BitReader reader, int size) {
        int lastScale = 8;
        int nextScale = 8;
        for (int j = 0; j < size; j++) {
            if (nextScale != 0) {
                int deltaScale = (int) reader.readSe();
                nextScale = (lastScale + deltaScale + 256) % 256;
            }
            lastScale = (nextScale == 0) ? lastScale : nextScale;
        }
    }

    /** Removes the 0x00 0x00 0x03 emulation prevention bytes of a NAL unit. */
    private static byte[] removeEmulationPrevention(byte[] data, int start) {
        byte[] out = new byte[data.length - start];
        int count = 0;
        int zeros = 0;
        for (int i = start; i < data.length; i++) {
            byte b = data[i];
            if (zeros >= 2 && b == 0x03) {
                zeros = 0;
                continue; // drop the emulation prevention byte
            }
            if (b == 0x00) zeros++;
            else zeros = 0;
            out[count++] = b;
        }
        return (count == out.length) ? out : Arrays.copyOf(out, count);
    }

    /** Minimal Exp-Golomb bit reader. */
    private static final class BitReader {
        private final byte[] data;
        private int bitPosition;

        BitReader(byte[] data) {
            this.data = data;
        }

        int readBit() {
            int byteIndex = bitPosition >> 3;
            if (byteIndex >= data.length) return 0;
            int bit = (data[byteIndex] >> (7 - (bitPosition & 7))) & 0x01;
            bitPosition++;
            return bit;
        }

        long readBits(int count) {
            long value = 0;
            for (int i = 0; i < count; i++) {
                value = (value << 1) | readBit();
            }
            return value;
        }

        long readUe() {
            int zeros = 0;
            while (readBit() == 0 && zeros < 32) zeros++;
            if (zeros >= 32) return 0;
            return ((1L << zeros) - 1) + readBits(zeros);
        }

        long readSe() {
            long ue = readUe();
            long value = (ue + 1) / 2;
            return ((ue & 1) == 0) ? -value : value;
        }
    }
}
