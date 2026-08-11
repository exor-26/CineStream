from pathlib import Path

path = Path('app/src/main/java/com/example/cinestream/VideoPlayerActivity.java')
text = path.read_text()
old = '''            if (!sameLogicalItem) {\n                compatibilityTranscodeAttempted = false;\n            }\n'''
new = '''            if (!sameLogicalItem) {\n                compatibilityTranscodeAttempted = false;\n                if (decoderMode.preferSoftwareVideo) {\n                    decoderMode = decoderMode.withoutSoftwareVideo();\n                    String targetPlaybackKey = playbackKey;\n                    uiHandler.post(() -> {\n                        if (exoPlayer == null\n                                || targetPlaybackKey == null\n                                || !targetPlaybackKey.equals(playbackKey)) {\n                            return;\n                        }\n                        ArrayList<MediaItem> items = snapshotMediaItems();\n                        int itemIndex = exoPlayer.getCurrentMediaItemIndex();\n                        long position = Math.max(0L, exoPlayer.getCurrentPosition());\n                        boolean playWhenReady = exoPlayer.getPlayWhenReady();\n                        Log.i(\"VideoCompatibility\",\n                                \"Resetting to hardware-first video for new media item\");\n                        rebuildPlayerPreservingCompatibility(\n                                items, itemIndex, position, playWhenReady);\n                    });\n                }\n            }\n'''
if text.count(old) != 1:
    raise SystemExit(f'Expected one transition reset block, found {text.count(old)}')
path.write_text(text.replace(old, new, 1))
