package com.flowseal.tgwsandroid;

import androidx.annotation.Nullable;
import java.nio.ByteBuffer;
import org.chromium.net.CronetException;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

/**
 * Java adapter around UrlRequest.Callback.
 *
 * The bundled Cronet API exposes Java platform types whose nullability is not
 * represented consistently to Kotlin across releases. In particular,
 * UrlResponseInfo may be null for failures/cancellation before response headers.
 * Keeping the binary override in Java avoids Kotlin override/nullability
 * mismatches while preserving the runtime contract.
 */
final class CronetDiagnosticCallback extends UrlRequest.Callback {
    interface Listener {
        void onHeaders(UrlRequest request, UrlResponseInfo info, String detail);
        void onFailure(UrlRequest request, @Nullable UrlResponseInfo info, CronetException error);
    }

    private final Listener listener;

    CronetDiagnosticCallback(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void onRedirectReceived(
            UrlRequest request,
            UrlResponseInfo info,
            String newLocationUrl) {
        listener.onHeaders(request, info, "redirect=" + newLocationUrl);
        request.cancel();
    }

    @Override
    public void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
        listener.onHeaders(request, info, "statusText=" + info.getHttpStatusText());
        request.cancel();
    }

    @Override
    public void onReadCompleted(
            UrlRequest request,
            UrlResponseInfo info,
            ByteBuffer byteBuffer) {
        // The diagnostic cancels as soon as headers arrive, so body reads are unused.
    }

    @Override
    public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
        listener.onHeaders(request, info, "request completed");
    }

    @Override
    public void onFailed(
            UrlRequest request,
            UrlResponseInfo info,
            CronetException error) {
        listener.onFailure(request, info, error);
    }
}
