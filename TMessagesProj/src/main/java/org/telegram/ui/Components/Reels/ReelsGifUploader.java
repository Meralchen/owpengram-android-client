package org.telegram.ui.Components.Reels;

import org.telegram.messenger.ConnectionsManager;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Uploads a comment GIF as a real Telegram document: upload.saveFilePart for the
 * bytes, then messages.uploadMedia to mint the document. The resulting
 * InputDocument is what reels.sendComment carries.
 */
final class ReelsGifUploader {

    private static final int PART_SIZE = 256 * 1024;

    private ReelsGifUploader() {
    }

    static void upload(int account, byte[] bytes, ReelsRepository.GifCallback callback) {
        if (bytes == null || bytes.length == 0) {
            callback.onGif(null, new ReelsError(400, "The GIF could not be read."));
            return;
        }
        final long fileId = Utilities.random.nextLong() & 0x7fffffffffffffffL;
        final int parts = Math.max(1, (bytes.length + PART_SIZE - 1) / PART_SIZE);
        uploadPart(account, bytes, fileId, parts, 0, callback);
    }

    private static void uploadPart(int account, byte[] bytes, long fileId, int parts, int index,
                                   ReelsRepository.GifCallback callback) {
        if (index >= parts) {
            finishUpload(account, fileId, parts, callback);
            return;
        }
        final int start = index * PART_SIZE;
        final int end = Math.min(bytes.length, start + PART_SIZE);
        final NativeByteBuffer buffer = buffer(Arrays.copyOfRange(bytes, start, end));
        if (buffer == null) {
            callback.onGif(null, new ReelsError(500, "The GIF could not be uploaded."));
            return;
        }
        final TLRPC.TL_upload_saveFilePart request = new TLRPC.TL_upload_saveFilePart();
        request.file_id = fileId;
        request.file_part = index;
        request.bytes = buffer;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            if (error != null) {
                callback.onGif(null, ReelsError.fromTl(error));
                return;
            }
            uploadPart(account, bytes, fileId, parts, index + 1, callback);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private static void finishUpload(int account, long fileId, int parts, ReelsRepository.GifCallback callback) {
        final TLRPC.TL_inputFile inputFile = new TLRPC.TL_inputFile();
        inputFile.id = fileId;
        inputFile.parts = parts;
        inputFile.name = "reel-comment.gif";
        inputFile.md5_checksum = "";

        final TLRPC.TL_inputMediaUploadedDocument media = new TLRPC.TL_inputMediaUploadedDocument();
        media.file = inputFile;
        media.mime_type = "image/gif";
        media.attributes = new ArrayList<>();
        media.attributes.add(new TLRPC.TL_documentAttributeAnimated());
        final TLRPC.TL_documentAttributeImageSize size = new TLRPC.TL_documentAttributeImageSize();
        size.w = 0;
        size.h = 0;
        media.attributes.add(size);

        final TLRPC.TL_messages_uploadMedia request = new TLRPC.TL_messages_uploadMedia();
        request.peer = new TLRPC.TL_inputPeerSelf();
        request.media = media;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            final ReelsError failure = ReelsError.fromTl(error);
            if (failure != null) {
                callback.onGif(null, failure);
                return;
            }
            TLRPC.Document document = null;
            if (response instanceof TLRPC.TL_messageMediaDocument) {
                document = ((TLRPC.TL_messageMediaDocument) response).document;
            } else if (response instanceof TLRPC.TL_messageMediaEmpty) {
                document = null;
            }
            if (document == null || document.id == 0) {
                callback.onGif(null, new ReelsError(500, "The GIF could not be uploaded."));
                return;
            }
            final ReelsRepository.GifUpload gif = new ReelsRepository.GifUpload();
            gif.id = Long.toString(document.id);
            gif.width = document.w;
            gif.height = document.h;
            gif.input = toInputDocument(document);
            callback.onGif(gif, null);
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private static NativeByteBuffer buffer(byte[] part) {
        try {
            final NativeByteBuffer buffer = new NativeByteBuffer(part.length);
            buffer.writeBytes(part);
            buffer.rewind();
            return buffer;
        } catch (Exception error) {
            return null;
        }
    }

    private static TLRPC.InputDocument toInputDocument(TLRPC.Document document) {
        final TLRPC.TL_inputDocument input = new TLRPC.TL_inputDocument();
        input.id = document.id;
        input.access_hash = document.access_hash;
        input.file_reference = document.file_reference;
        return input;
    }
}
