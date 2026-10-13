package com.exor.cinestream;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.text.SubtitleParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Loads indexed Info/Tracks before the first Cluster when an MKV stores them later.
 * Original byte positions, samples, cue maps, decoders and timestamps remain Media3-owned.
 * Ordinary MKV/WebM files take the unmodified Media3 path with no extra seeks.
 */
@UnstableApi
final class SeekHeadMatroskaExtractor implements Extractor {
    private static final int SEGMENT = 0x18538067;
    private static final int SEEK_HEAD = 0x114D9B74;
    private static final int SEEK = 0x4DBB;
    private static final int SEEK_ID = 0x53AB;
    private static final int SEEK_POSITION = 0x53AC;
    private static final int INFO = 0x1549A966;
    private static final int TRACKS = 0x1654AE6B;
    private static final int CLUSTER = 0x1F43B675;
    private static final int MAX_HEADER_SEEKS = 16;
    private static final long MAX_HEADER_BYTES = MatroskaHeaderReference.MAX_HEADER_BYTES;

    // Private, stackless control signal, always caught at the Extractor boundary.
    private static final class HeaderJump extends RuntimeException {
        HeaderJump() { super(null, null, false, false); }
    }
    private static final HeaderJump HEADER_JUMP = new HeaderJump();
    private final HeaderParser parser;

    SeekHeadMatroskaExtractor(SubtitleParser.Factory subtitles, int flags) {
        parser = new HeaderParser(subtitles, flags);
    }

    @Override public boolean sniff(ExtractorInput input) throws IOException {
        return parser.sniff(input);
    }
    @Override public void init(ExtractorOutput output) { parser.init(output); }
    @Override public void release() { parser.release(); }

    @Override public void seek(long position, long timeUs) {
        parser.headerGoal = 0;
        parser.parserInput = null;
        parser.seek(position, timeUs);
    }

    @Override public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        parser.parserInput = input;
        try {
            return parser.read(input, seekPosition);
        } catch (HeaderJump jump) {
            HeaderTarget target = parser.nextHeader();
            if (target != null) {
                if (++parser.headerSeekCount > MAX_HEADER_SEEKS
                        || !parser.visitedHeaders.add(target.position)) {
                    throw malformed("Cyclic or excessive MKV header references");
                }
                parser.headerGoal = target.id;
                parser.headerStart = target.position;
                parser.headerRootPending = true;
                parser.seek(target.position, 0);
                seekPosition.position = target.position;
                return RESULT_SEEK;
            }
            if (!parser.infoSeen || !parser.tracksSeen) {
                throw malformed("MKV index does not resolve required Info/Tracks");
            }
            parser.headerGoal = 0;
            parser.seek(parser.firstClusterContent, 0);
            // Resume the original Cluster body, not a fabricated or remapped stream.
            parser.resumeFirstCluster();
            seekPosition.position = parser.firstClusterContent;
            android.util.Log.i("MkvHeaderDiscovery", "Loaded indexed MKV track headers before samples");
            return RESULT_SEEK;
        } catch (IllegalHeaderReference invalid) {
            throw malformed(invalid.getMessage());
        } finally {
            parser.parserInput = null;
        }
    }

    private static ParserException malformed(String message) {
        return ParserException.createForMalformedContainer(message, null);
    }

    private static final class HeaderTarget {
        final int id;
        final long position;
        HeaderTarget(int id, long position) { this.id = id; this.position = position; }
    }

    private static final class HeaderParser extends MatroskaExtractor {
        ExtractorInput parserInput;
        long segmentStart = -1;
        long infoPosition = -1;
        long tracksPosition = -1;
        long firstClusterContent = -1;
        long firstClusterSize;
        long seekRelativePosition = -1;
        int seekId;
        int headerGoal;
        long headerStart;
        int headerSeekCount;
        boolean headerRootPending;
        boolean infoSeen;
        boolean tracksSeen;
        final ArrayList<Long> seekHeads = new ArrayList<>();
        final Set<Long> visitedHeaders = new HashSet<>();

        HeaderParser(SubtitleParser.Factory subtitles, int flags) { super(subtitles, flags); }

        @Override protected int getElementType(int id) {
            if (headerRootPending) {
                headerRootPending = false;
                if (id != headerGoal) {
                    throw new IllegalHeaderReference("MKV index points to the wrong element");
                }
            }
            if (headerGoal != 0 && parserInput != null
                    && parserInput.getPosition() - headerStart > MAX_HEADER_BYTES) {
                throw new IllegalHeaderReference("MKV header exceeds bounded discovery budget");
            }
            return super.getElementType(id);
        }

        @Override protected void startMasterElement(int id, long contentPosition, long size)
                throws ParserException {
            if (headerGoal != 0 && (id == headerGoal || id == 0xAE)
                    && !MatroskaHeaderReference.boundedSize(size)) {
                throw malformed("MKV header size exceeds bounded discovery budget");
            }
            if (id == CLUSTER && (!infoSeen || !tracksSeen) && nextHeader() != null) {
                firstClusterContent = contentPosition;
                firstClusterSize = size;
                throw HEADER_JUMP;
            }
            super.startMasterElement(id, contentPosition, size);
            if (id == SEGMENT) segmentStart = contentPosition;
            if (id == SEEK) { seekId = 0; seekRelativePosition = -1; }
        }

        @Override protected void integerElement(int id, long value) throws ParserException {
            super.integerElement(id, value);
            if (id == SEEK_POSITION) seekRelativePosition = value;
        }

        @Override protected void binaryElement(int id, int size, ExtractorInput input)
                throws IOException {
            if (headerGoal != 0 && (size < 0 || size > MAX_HEADER_BYTES)) {
                throw malformed("MKV header binary field exceeds discovery budget");
            }
            if (id == SEEK_ID && size > 0 && size <= 4) {
                byte[] bytes = new byte[size];
                input.peekFully(bytes, 0, size);
                input.resetPeekPosition();
                seekId = 0;
                for (byte value : bytes) seekId = (seekId << 8) | (value & 0xff);
            }
            super.binaryElement(id, size, input);
        }

        @Override protected void endMasterElement(int id) throws ParserException {
            Track endingTrack = id == 0xAE ? getCurrentTrack(id) : null;
            super.endMasterElement(id);
            if (endingTrack != null && endingTrack.format != null
                    && endingTrack.format.frameRate <= 0 && endingTrack.defaultSampleDurationNs > 0
                    && endingTrack.format.sampleMimeType != null
                    && endingTrack.format.sampleMimeType.startsWith("video/")) {
                endingTrack.format = endingTrack.format.buildUpon()
                        .setFrameRate(1_000_000_000f / endingTrack.defaultSampleDurationNs).build();
            }
            if (id == INFO) infoSeen = true;
            if (id == TRACKS) tracksSeen = true;
            if (id == SEEK) {
                long length = parserInput != null ? parserInput.getLength() : C.LENGTH_UNSET;
                long position = MatroskaHeaderReference.resolve(segmentStart, seekRelativePosition, length);
                if (position >= 0) {
                    if (seekId == INFO) infoPosition = position;
                    if (seekId == TRACKS) tracksPosition = position;
                    if (seekId == SEEK_HEAD && seekHeads.size() < MAX_HEADER_SEEKS
                            && !seekHeads.contains(position)) seekHeads.add(position);
                }
            }
            if (headerGoal == id) throw HEADER_JUMP;
        }

        HeaderTarget nextHeader() {
            if (!infoSeen && infoPosition >= 0) return new HeaderTarget(INFO, infoPosition);
            if (!tracksSeen && tracksPosition >= 0) return new HeaderTarget(TRACKS, tracksPosition);
            if (!infoSeen || !tracksSeen) {
                for (long position : seekHeads) {
                    if (!visitedHeaders.contains(position)) return new HeaderTarget(SEEK_HEAD, position);
                }
            }
            return null;
        }

        void resumeFirstCluster() throws ParserException {
            super.startMasterElement(CLUSTER, firstClusterContent, firstClusterSize);
        }
    }

    private static final class IllegalHeaderReference extends RuntimeException {
        IllegalHeaderReference(String message) { super(message); }
    }
}
