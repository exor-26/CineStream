package com.example.cinestream.ffmpeg;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;

@UnstableApi
final class CineFfmpegDecoderException extends DecoderException {
    CineFfmpegDecoderException(String message) {
        super(message);
    }

    CineFfmpegDecoderException(String message, Throwable cause) {
        super(message, cause);
    }
}
