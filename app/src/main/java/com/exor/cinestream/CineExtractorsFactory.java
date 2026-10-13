package com.exor.cinestream;

import android.net.Uri;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import java.util.List;
import java.util.Map;

/** Preserve Media3's sniffing/order/settings, replacing only its MKV header discovery. */
@UnstableApi
final class CineExtractorsFactory implements ExtractorsFactory {
    private final DefaultExtractorsFactory defaults = new DefaultExtractorsFactory();
    private SubtitleParser.Factory subtitles = new DefaultSubtitleParserFactory();
    private boolean transcodeText = true;

    @Override public synchronized Extractor[] createExtractors() {
        return replaceMatroska(defaults.createExtractors());
    }
    @Override public synchronized Extractor[] createExtractors(Uri uri, Map<String, List<String>> headers) {
        return replaceMatroska(defaults.createExtractors(uri, headers));
    }
    @Override public synchronized ExtractorsFactory setSubtitleParserFactory(SubtitleParser.Factory factory) {
        subtitles = factory;
        defaults.setSubtitleParserFactory(factory);
        return this;
    }
    @SuppressWarnings("deprecation")
    @Override public synchronized ExtractorsFactory experimentalSetTextTrackTranscodingEnabled(boolean enabled) {
        transcodeText = enabled;
        defaults.experimentalSetTextTrackTranscodingEnabled(enabled);
        return this;
    }
    @Override public synchronized ExtractorsFactory experimentalSetCodecsToParseWithinGopSampleDependencies(int codecs) {
        defaults.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs);
        return this;
    }
    private Extractor[] replaceMatroska(Extractor[] extractors) {
        for (int i = 0; i < extractors.length; i++) {
            if (extractors[i] instanceof MatroskaExtractor) {
                extractors[i] = new SeekHeadMatroskaExtractor(subtitles,
                        transcodeText ? 0 : MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA);
            }
        }
        return extractors;
    }
}
